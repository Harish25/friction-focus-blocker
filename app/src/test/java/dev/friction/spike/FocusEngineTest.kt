package dev.friction.spike

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FocusEngineTest {
    private val utc = ZoneId.of("UTC")
    private val start = Instant.parse("2026-09-27T12:00:00Z").toEpochMilli()
    private class Clock(var wall: Long, var elapsed: Long = 1_000, var boot: Int = 1) {
        fun now() = Stamp(wall, elapsed, boot)
        fun step(ms: Long) { wall += ms; elapsed += ms }
    }
    private fun engine(cap: Long? = null) = FocusEngine(FocusState(interventionMs = 2_000, resetMs = 5_000)).apply {
        configure("a", AppRule(Policy.Interrupted, 3_000, 2_000, cap)); activate()
    }
    private fun use(e: FocusEngine, c: Clock, ms: Long, pkg: String? = "a", overlay: Boolean = false) {
        e.observe(pkg, overlay, c.now(), utc)
        repeat((ms / 100).toInt()) { c.step(100); e.observe(pkg, overlay, c.now(), utc) }
    }
    @Test fun offNeverAccumulatesAndBlockedIsImmediate() {
        val e = engine(); val c = Clock(start)
        e.deactivate(); use(e, c, 10_000)
        assertEquals(0, e.state.usage["a"]!!.daily)
        e.configure("b", AppRule(Policy.Blocked)); e.activate()
        assertEquals(Barrier.Blocked, e.barrier("b")); assertEquals(Barrier.None, e.barrier("other"))
        e.deactivate(); assertEquals(Barrier.None, e.barrier("b"))
    }
    @Test fun cumulativeShortVisitsAndIndependentApps() {
        val e = engine(); val c = Clock(start)
        e.deactivate(); e.configure("b", AppRule(Policy.Interrupted, 3_000, 2_000)); e.activate()
        use(e, c, 2_000); use(e, c, 1_000, "b"); use(e, c, 1_000)
        assertEquals(Barrier.Ordinary, e.barrier("a"))
        assertEquals(1_000, e.state.usage["b"]!!.used)
        assertEquals(Barrier.None, e.barrier("b"))
    }
    @Test fun countdownRequiresVisibleOverlayAndResumesAfterExit() {
        val e = engine(); val c = Clock(start)
        use(e, c, 3_000); use(e, c, 700, overlay = true)
        assertEquals(1_300, e.state.usage["a"]!!.remaining)
        use(e, c, 2_000, null); use(e, c, 500) // Pending without visible overlay cannot advance.
        assertEquals(1_300, e.state.usage["a"]!!.remaining)
        use(e, c, 1_300, overlay = true)
        assertEquals(Barrier.None, e.barrier("a"))
        assertEquals(3_000, e.state.usage["a"]!!.daily)
        use(e, c, 1_900); assertEquals(Barrier.None, e.barrier("a"))
        use(e, c, 100); assertEquals(Barrier.Ordinary, e.barrier("a"))
    }
    @Test fun overlayPresenceDoesNotResetSessionButAbsenceDoes() {
        val e = engine(); val c = Clock(start)
        e.state.interventionMs = 10_000
        use(e, c, 3_000); use(e, c, 6_000, overlay = true)
        assertEquals(4_000, e.state.usage["a"]!!.remaining)
        use(e, c, 4_900, null); assertEquals(4_000, e.state.usage["a"]!!.remaining)
        use(e, c, 100, null)
        assertEquals(0, e.state.usage["a"]!!.remaining); assertEquals(0, e.state.usage["a"]!!.used)
        assertEquals(3_000, e.state.usage["a"]!!.daily)
    }
    @Test fun shortReturnRestartsContinuousAbsence() {
        val e = engine(); val c = Clock(start)
        use(e, c, 1_000); use(e, c, 4_000, null); use(e, c, 100); use(e, c, 4_000, null)
        assertEquals(1_100, e.state.usage["a"]!!.used)
    }
    @Test fun capPrecedesSimultaneousInterventionAndSurvivesToggles() {
        val e = engine(3_000); val c = Clock(start)
        use(e, c, 3_000)
        assertEquals(Barrier.DailyCap, e.barrier("a"))
        use(e, c, 4_000, overlay = true)
        assertEquals(2_000, e.state.usage["a"]!!.remaining)
        e.deactivate(); use(e, c, 2_000); e.activate()
        assertEquals(Barrier.DailyCap, e.barrier("a")); assertEquals(0, e.state.usage["a"]!!.remaining)
    }
    @Test fun capReachedBeforeOrdinaryThresholdClampsUsage() {
        val e = engine(2_550); val c = Clock(start)
        use(e, c, 3_000)
        assertEquals(2_550, e.state.usage["a"]!!.used)
        assertEquals(2_550, e.state.usage["a"]!!.daily)
        assertEquals(Barrier.DailyCap, e.barrier("a"))
    }
    @Test fun midnightSplitsActiveIntervalWithoutResettingSession() {
        val e = engine(); val c = Clock(Instant.parse("2026-09-27T23:59:59.500Z").toEpochMilli())
        use(e, c, 1_000)
        assertEquals(1_000, e.state.usage["a"]!!.used)
        assertEquals(500, e.state.usage["a"]!!.daily)
        assertEquals("2026-09-28", e.state.usage["a"]!!.day)
    }
    @Test fun midnightLiftsCapAndKeepsPendingWithoutChargingDailyOverlay() {
        val e = engine(3_000); val c = Clock(Instant.parse("2026-09-27T23:59:56Z").toEpochMilli())
        use(e, c, 3_000)
        e.observe("a", true, c.now(), utc, ordinaryVisible = false)
        repeat(20) { c.step(100); e.observe("a", true, c.now(), utc, ordinaryVisible = false) }
        assertEquals(Barrier.Ordinary, e.barrier("a"))
        assertEquals(2_000, e.state.usage["a"]!!.remaining)
        assertEquals(0, e.state.usage["a"]!!.daily)
    }
    @Test fun monotonicAbsenceIgnoresSameBootClockJumps() {
        val e = engine(); val c = Clock(start)
        use(e, c, 1_000); use(e, c, 1_000, null)
        c.wall += 86_400_000
        e.observe(null, false, c.now(), utc)
        assertEquals(1_000, e.state.usage["a"]!!.used)
        c.wall -= 2 * 86_400_000
        use(e, c, 4_000, null)
        assertEquals(0, e.state.usage["a"]!!.used)
    }
    @Test fun dateChangesResetTotalsAndZoneUsesCurrentLocalDay() {
        val e = engine(); val c = Clock(Instant.parse("2026-09-27T23:00:00Z").toEpochMilli())
        use(e, c, 1_000)
        e.observe("a", false, c.now(), ZoneId.of("Asia/Tokyo"))
        assertEquals(0, e.state.usage["a"]!!.daily); assertEquals(1_000, e.state.usage["a"]!!.used)
        e.observe("a", false, c.now(), utc)
        assertEquals("2026-09-27", e.state.usage["a"]!!.day)
    }
    @Test fun longSchedulerStallsAndWindowTransitionsAreNotCharged() {
        val e = engine(); val c = Clock(start)
        use(e, c, 1_000); c.step(10_000); e.observe("a", false, c.now(), utc)
        assertEquals(1_000, e.state.usage["a"]!!.daily)
        c.step(500); e.observe(null, false, c.now(), utc)
        assertEquals(1_000, e.state.usage["a"]!!.daily)
    }
    @Test fun recoveryPreservesPendingAndDoesNotChargeDowntime() {
        val e = engine(); val c = Clock(start)
        use(e, c, 3_000); use(e, c, 500, overlay = true)
        val restored = FocusEngine(FocusCodec.decode(FocusCodec.encode(e.state)))
        c.step(1_000); restored.recover(c.now(), utc)
        assertEquals(1_500, restored.state.usage["a"]!!.remaining)
        use(restored, c, 500, overlay = true)
        assertEquals(1_000, restored.state.usage["a"]!!.remaining)
        assertEquals(3_000, restored.state.usage["a"]!!.daily)
    }
    @Test fun rebootUsesWallAbsenceAndBackwardRebootClockIsConservative() {
        val e = engine(); val c = Clock(start)
        use(e, c, 2_000)
        c.boot++; c.elapsed = 100; c.wall -= 10_000
        e.recover(c.now(), utc)
        assertEquals(2_000, e.state.usage["a"]!!.used)
        c.boot++; c.elapsed = 100; c.wall += 6_000
        e.recover(c.now(), utc)
        assertEquals(0, e.state.usage["a"]!!.used)
        assertEquals(2_000, e.state.usage["a"]!!.daily)
    }
    @Test fun longDowntimeResetsSessionButPreservesCurrentDayCap() {
        val e = engine(3_000); val c = Clock(start)
        use(e, c, 3_000); c.step(6_000); e.recover(c.now(), utc)
        assertEquals(0, e.state.usage["a"]!!.remaining)
        assertEquals(Barrier.DailyCap, e.barrier("a"))
    }
    @Test fun configurationIsGatedAtEngineAndDailyTotalsSurviveEdits() {
        val e = engine(); val c = Clock(start)
        use(e, c, 1_000)
        assertThrows(IllegalStateException::class.java) { e.configure("a", AppRule()) }
        assertThrows(IllegalStateException::class.java) { e.settings(1_000, 1_000, listOf("uri")) }
        e.deactivate(); e.configure("a", AppRule(Policy.Allowed)); e.configure("a", AppRule(Policy.Interrupted))
        assertEquals(1_000, e.state.usage["a"]!!.daily)
    }
    @Test fun essentialPackagesCannotBeConfiguredOrEnforcedEvenFromSavedRules() {
        val state = FocusState(on = true, rules = mutableMapOf("phone" to AppRule(Policy.Blocked)))
        val e = FocusEngine(state, setOf("phone"))
        assertEquals(Barrier.None, e.barrier("phone"))
        e.deactivate()
        assertThrows(IllegalArgumentException::class.java) { e.configure("phone", AppRule(Policy.Blocked)) }
    }
    @Test fun unknownBootIdentityUsesWallRecoveryInsteadOfUptime() {
        val e = engine(); val c = Clock(start)
        use(e, c, 2_000)
        e.state.checkpoint = Stamp(c.wall, 0, -1)
        e.recover(Stamp(c.wall + 1_000, 9_000_000, -1), utc)
        assertEquals(2_000, e.state.usage["a"]!!.used)
    }
    @Test fun localMidnightUsesZoneRulesAcrossDaylightSavingDates() {
        val zone = ZoneId.of("America/Toronto")
        val midnight = java.time.LocalDate.of(2026, 11, 2).atStartOfDay(zone).toInstant().toEpochMilli()
        val e = engine(); val c = Clock(midnight - 500)
        e.observe("a", false, c.now(), zone)
        c.step(1_000); e.observe("a", false, c.now(), zone)
        assertEquals("2026-11-02", e.state.usage["a"]!!.day)
        assertEquals(500, e.state.usage["a"]!!.daily)
        assertEquals(1_000, e.state.usage["a"]!!.used)
    }
    @Test fun invalidDurationsAndUnknownSchemaAreRejected() {
        val e = engine(); e.deactivate()
        assertThrows(IllegalArgumentException::class.java) { e.configure("a", AppRule(initialMs = -1)) }
        assertThrows(IllegalArgumentException::class.java) { FocusCodec.decode("{\"schema\":2}") }
        assertThrows(IllegalArgumentException::class.java) { FocusCodec.decode("{\"interventionMs\":0}") }
    }
    @Test fun deactivationRequiresThirtyVisibleSecondsAndExplicitConfirmation() {
        val e = engine(); val a = DeactivationAttempt()
        a.tick(0, true); a.tick(500, true); a.tick(600, false); a.tick(50_000, true)
        assertEquals(29_500, a.remaining); assertFalse(a.confirm(e)); assertTrue(e.state.on)
        for (i in 1..59) a.tick(50_000 + i * 500L, true)
        assertEquals(0, a.remaining); assertTrue(e.state.on)
        assertTrue(a.confirm(e)); assertFalse(e.state.on)
    }
    @Test fun pausedAndFreshDeactivationAttemptsReceiveNoHiddenCredit() {
        val a = DeactivationAttempt()
        a.tick(0, true); a.tick(1_000, true); a.pause(); a.tick(2_000, true)
        assertEquals(29_000, a.remaining)
        a.tick(20_000, true); assertEquals(29_000, a.remaining)
        assertEquals(30_000, DeactivationAttempt().remaining)
    }
}

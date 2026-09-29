package dev.friction.spike

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable enum class Policy { Allowed, Interrupted, Blocked }
@Serializable data class AppRule(
    val policy: Policy = Policy.Allowed,
    val initialMs: Long = 10 * 60_000,
    val repeatMs: Long = 5 * 60_000,
    val capMs: Long? = null,
)
@Serializable data class Stamp(val wall: Long, val elapsed: Long, val boot: Int)
@Serializable data class AppUsage(
    var used: Long = 0, var next: Long = 0, var remaining: Long = 0,
    var away: Stamp? = null, var daily: Long = 0, var day: String = "",
)
@Serializable data class FocusState(
    val schema: Int = 1,
    var on: Boolean = false,
    var interventionMs: Long = 30_000,
    var resetMs: Long = 25 * 60_000,
    val rules: MutableMap<String, AppRule> = mutableMapOf(),
    val usage: MutableMap<String, AppUsage> = mutableMapOf(),
    var photos: List<String> = emptyList(),
    var checkpoint: Stamp? = null,
)
enum class Barrier { None, Ordinary, DailyCap, Blocked }

/** Pure evaluator: no Android, disk, timer threads, or historical event log. */
class FocusEngine(val state: FocusState, private val excluded: Set<String> = emptySet()) {
    private var previous: Stamp? = null
    private var previousPackage: String? = null
    private var previousOverlay = false
    private var previousOrdinary = false
    private var previousZone: String? = null

    fun configure(pkg: String, rule: AppRule) {
        check(!state.on) { "Turn Friction off before editing" }
        require(pkg !in excluded)
        require(rule.initialMs in 1_000..86_400_000 && rule.repeatMs in 1_000..86_400_000)
        require(rule.capMs == null || rule.capMs in 1_000..86_400_000)
        state.rules[pkg] = rule
    }
    fun settings(intervention: Long, reset: Long, photos: List<String>) {
        check(!state.on)
        require(intervention in 1_000..3_600_000 && reset in 1_000..86_400_000)
        state.interventionMs = intervention
        state.resetMs = reset
        state.photos = photos.distinct().take(30)
    }
    fun activate() { if (!state.on) { clearSessions(); state.on = true }; forgetObservation() }
    fun deactivate() { state.on = false; clearSessions(); forgetObservation() }
    private fun clearSessions() = state.usage.values.forEach { clearSession(it, 0) }
    private fun clearSession(u: AppUsage, initial: Long) {
        u.used = 0; u.next = initial; u.remaining = 0; u.away = null
    }
    fun forgetObservation() { previous = null; previousPackage = null; previousOverlay = false }
    fun recover(now: Stamp, zone: ZoneId) {
        state.usage.forEach { (pkg, u) ->
            rollover(u, date(now.wall, zone))
            if (state.on && u.away == null) u.away = state.checkpoint ?: now
            reconcileAbsence(u, state.rules[pkg]?.initialMs ?: 0, now)
        }
        forgetObservation()
        state.checkpoint = now
    }
    fun barrier(pkg: String?): Barrier {
        if (!state.on || pkg == null || pkg in excluded) return Barrier.None
        val rule = state.rules[pkg] ?: return Barrier.None
        if (rule.policy == Policy.Blocked) return Barrier.Blocked
        if (rule.policy != Policy.Interrupted) return Barrier.None
        val u = state.usage[pkg] ?: return Barrier.None
        if (rule.capMs != null && u.daily >= rule.capMs) return Barrier.DailyCap
        return if (u.remaining > 0) Barrier.Ordinary else Barrier.None
    }
    fun observe(pkg: String?, overlayVisible: Boolean, now: Stamp, zone: ZoneId, ordinaryVisible: Boolean = overlayVisible) {
        val target = pkg?.takeIf { it !in excluded && state.on }
        val old = previous
        val delta = if (old != null && old.boot == now.boot && target != null &&
            target == previousPackage && overlayVisible == previousOverlay && ordinaryVisible == previousOrdinary)
            (now.elapsed - old.elapsed).takeIf { it in 0..1_000 } ?: 0 else 0
        state.rules.forEach { (p, rule) ->
            if (rule.policy != Policy.Interrupted) return@forEach
            val u = state.usage.getOrPut(p) { AppUsage(next = rule.initialMs) }
            if (u.next == 0L) u.next = rule.initialMs
            if (!state.on) { rollover(u, date(now.wall, zone)); return@forEach }
            reconcileAbsence(u, rule.initialMs, now)
            if (target != p) {
                if (u.away == null) u.away = now
                rollover(u, date(now.wall, zone))
            } else {
                u.away = null // Viewing an overlay is presence, never absence.
                val continuousWall = old != null && previousZone == zone.id &&
                    kotlin.math.abs((now.wall - old.wall) - (now.elapsed - old.elapsed)) < 2_000
                if (delta > 0 && continuousWall && date(old!!.wall, zone) != date(now.wall, zone)) {
                    val midnight = Instant.ofEpochMilli(now.wall).atZone(zone).toLocalDate()
                        .atStartOfDay(zone).toInstant().toEpochMilli()
                    val before = (midnight - old.wall).coerceIn(0, delta)
                    rollover(u, date(old.wall, zone))
                    advance(u, rule, before, overlayVisible, ordinaryVisible)
                    rollover(u, date(now.wall, zone))
                    advance(u, rule, delta - before, overlayVisible, ordinaryVisible)
                } else {
                    rollover(u, date(now.wall, zone))
                    advance(u, rule, delta, overlayVisible, ordinaryVisible)
                }
            }
        }
        // Daily totals remain calendar based even for apps subsequently changed to Allowed/Blocked.
        state.usage.values.forEach { rollover(it, date(now.wall, zone)) }
        previous = now; previousPackage = target; previousOverlay = overlayVisible; previousZone = zone.id; previousOrdinary = ordinaryVisible
        state.checkpoint = now
    }
    private fun advance(u: AppUsage, r: AppRule, delta: Long, overlay: Boolean, ordinary: Boolean) {
        if (r.capMs != null && u.daily >= r.capMs) return
        if (u.remaining > 0) {
            if (ordinary) {
                u.remaining = (u.remaining - delta).coerceAtLeast(0)
                if (u.remaining == 0L) u.next = u.used + r.repeatMs
            }
        } else if (!overlay) {
            val charged = minOf(delta, (u.next - u.used).coerceAtLeast(0),
                r.capMs?.let { (it - u.daily).coerceAtLeast(0) } ?: Long.MAX_VALUE)
            u.used += charged; u.daily += charged
            if (u.used >= u.next) u.remaining = state.interventionMs
        }
    }
    private fun reconcileAbsence(u: AppUsage, initial: Long, now: Stamp) {
        val away = u.away ?: return
        val elapsed = if (away.boot >= 0 && away.boot == now.boot && now.elapsed >= away.elapsed)
            now.elapsed - away.elapsed else now.wall - away.wall
        if (elapsed < 0) u.away = now
        else if (elapsed >= state.resetMs) {
            clearSession(u, initial)
            u.away = now
        }
    }
    private fun rollover(u: AppUsage, day: String) {
        if (u.day != day) { u.daily = 0; u.day = day }
    }
    private fun date(wall: Long, zone: ZoneId) = Instant.ofEpochMilli(wall).atZone(zone).toLocalDate().toString()
}

/** Memory only: retained through rotation, deliberately not serialized across process death. */
class DeactivationAttempt {
    var remaining = 30_000L
        private set
    private var previous: Long? = null
    fun tick(elapsed: Long, visible: Boolean) {
        val old = previous
        if (visible && old != null) {
            val delta = (elapsed - old).takeIf { it in 0..1_000 } ?: 0
            remaining = (remaining - delta).coerceAtLeast(0)
        }
        previous = if (visible) elapsed else null
    }
    fun pause() { previous = null }
    fun confirm(engine: FocusEngine): Boolean {
        if (remaining != 0L) return false
        engine.deactivate(); return true
    }
}

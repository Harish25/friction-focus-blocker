package dev.friction.spike

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.*
import org.junit.Test

class FocusPresentationTest {
    @Test fun timingEditsPublishNewObservableValuesWithoutMutatingTheOldSnapshot() {
        val engine = FocusEngine(FocusState())
        val presentation = FocusPresentation()
        val original = presentation.publish(engine.state)
        Snapshot.sendApplyNotifications()
        var invalidated = false
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Unit, { invalidated = true }) {
                assertEquals(30_000, presentation.state.value!!.interventionMs)
            }
            engine.settings(45_000, 1_800_000, listOf("content://selected"))
            presentation.publish(engine.state)
            Snapshot.sendApplyNotifications()
            assertTrue("Compose readers must be invalidated by the settings edit", invalidated)
            assertEquals(45_000, presentation.state.value!!.interventionMs)
            assertEquals(1_800_000, presentation.state.value!!.resetMs)
            assertEquals(30_000, original.interventionMs)
            assertTrue(original.photos.isEmpty())
        } finally { observer.stop(); observer.clear() }
    }
    @Test fun livePolicyAndUsageChangesCannotSilentlyChangePublishedRows() {
        val engine = FocusEngine(FocusState())
        engine.configure("brave", AppRule(Policy.Blocked))
        engine.state.usage["brave"] = AppUsage(daily = 1234)
        val presentation = FocusPresentation()
        val original = presentation.publish(engine.state)
        engine.configure("brave", AppRule(Policy.Allowed))
        engine.state.usage["brave"]!!.daily = 2000
        val current = presentation.publish(engine.state)
        assertEquals(Policy.Blocked, original.rules["brave"]!!.policy)
        assertEquals(1234, original.usage["brave"]!!.daily)
        assertEquals(Policy.Allowed, current.rules["brave"]!!.policy)
        assertEquals(2000, current.usage["brave"]!!.daily)
    }
    private val apps = listOf("Brave" to "brave", "Alarm" to "alarm", "Video" to "video")
    @Test fun changingBraveToAllowedMovesItBelowDividerAndKeepsOtherRestrictedAppsFirst() {
        val rules = mutableMapOf("brave" to AppRule(Policy.Blocked), "video" to AppRule(Policy.Interrupted))
        assertEquals(listOf("brave", "video", "alarm"), appListRows(apps, rules, "").apps.map { it.second })
        rules["brave"] = AppRule(Policy.Allowed)
        val rows = appListRows(apps, rules, "")
        assertEquals(listOf("video", "alarm", "brave"), rows.apps.map { it.second })
        assertEquals("alarm", rows.dividerBefore)
    }
    @Test fun searchHasNoDividerEvenWhenItMatchesBothGroups() {
        val rules = mapOf("brave" to AppRule(Policy.Blocked))
        val rows = appListRows(apps, rules, "a")
        assertEquals(listOf("brave", "alarm"), rows.apps.map { it.second })
        assertNull(rows.dividerBefore)
        assertNull(appListRows(apps, rules, "missing").dividerBefore)
    }
    @Test fun dividerRequiresBothGroupsAndExplicitAllowedIsNotRestricted() {
        assertNull(appListRows(apps, mapOf("brave" to AppRule(Policy.Allowed)), "").dividerBefore)
        assertNull(appListRows(apps, apps.associate { it.second to AppRule(Policy.Blocked) }, "").dividerBefore)
        assertNull(appListRows(emptyList(), emptyMap(), "").dividerBefore)
    }
}

package dev.friction.spike

import androidx.datastore.core.DataStoreFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class PersistenceAndMediaTest {
    @Test fun atomicDataStoreRoundTripRestoresAllEnforcementFields() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("friction-test").toFile()
        val file = File(dir, "state.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = FocusState(on = true, photos = listOf("content://selected/1"), checkpoint = Stamp(100_000, 5_000, 1))
        state.rules["a"] = AppRule(Policy.Interrupted, 10_000, 5_000, 60_000)
        state.usage["a"] = AppUsage(10_000, 10_000, 25_000, null, 20_000, "1970-01-01")
        try {
            val store = DataStoreFactory.create(FocusSerializer, scope = scope, produceFile = { file })
            store.updateData { FocusCodec.decode(FocusCodec.encode(state)) }
            // Mutating the live evaluator must not mutate DataStore's detached checkpoint.
            state.usage["a"]!!.daily = 99_999
            assertEquals(20_000, store.data.first().usage["a"]!!.daily)
            scope.coroutineContext[Job]!!.cancelAndJoin()
            val restoredScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val restoredStore = DataStoreFactory.create(FocusSerializer, scope = restoredScope, produceFile = { file })
                val restored = FocusEngine(FocusCodec.decode(FocusCodec.encode(restoredStore.data.first())))
                restored.recover(Stamp(101_000, 6_000, 1), ZoneId.of("UTC"))
                assertTrue(restored.state.on)
                assertEquals(25_000, restored.state.usage["a"]!!.remaining)
                assertEquals(20_000, restored.state.usage["a"]!!.daily)
                assertEquals(5_000, restored.state.rules["a"]!!.repeatMs)
                assertEquals(listOf("content://selected/1"), restored.state.photos)
                assertEquals(Barrier.Ordinary, restored.barrier("a"))
            } finally { restoredScope.coroutineContext[Job]!!.cancelAndJoin() }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); dir.deleteRecursively() }
    }
    @Test fun truncatedOrInvalidStateIsNotSilentlyReplacedByOff() = runBlocking<Unit> {
        assertThrows(Exception::class.java) { FocusCodec.decode("{\"on\":true,") }
        assertThrows(Exception::class.java) { FocusCodec.decode("{\"usage\":{\"a\":{\"daily\":-1}}}") }
    }
    @Test fun absentDeletedRevokedAndMalformedPhotosUseFallback() = runBlocking<Unit> {
        assertNull(loadOrFallback<String>(null) { error("Must not decode an absent photo") })
        assertNull(loadOrFallback<String>("content://deleted") { throw IOException("deleted") })
        assertNull(loadOrFallback<String>("content://revoked") { throw SecurityException("revoked") })
        assertNull(loadOrFallback<String>("bad image") { throw IllegalArgumentException("decode failed") })
        assertEquals("image", loadOrFallback("content://valid") { "image" })
    }
    @Test fun spikeMigrationRetainsOnAndPendingButUsesUsableDefaults() {
        val state = migrateLegacySpike(true, "a", 10_000, 6_871, 0, 100_000, emptySet())
        assertTrue(state.on)
        assertEquals(6_871, state.usage["a"]!!.remaining)
        assertEquals(10_000, state.usage["a"]!!.used)
        assertEquals(600_000, state.rules["a"]!!.initialMs)
        assertEquals(300_000, state.rules["a"]!!.repeatMs)
        assertEquals(30_000, state.interventionMs)
        assertEquals(0, state.usage["a"]!!.daily)
        assertEquals(100_000, state.usage["a"]!!.away!!.wall)
        val excluded = migrateLegacySpike(true, "phone", 10_000, 1_000, 0, 0, setOf("phone"))
        assertTrue(excluded.on); assertTrue(excluded.rules.isEmpty())
        val off = migrateLegacySpike(false, "a", 10_000, 1_000, 0, 0, emptySet())
        assertEquals(0, off.usage["a"]!!.remaining)
    }
    @Test fun mediaCancellationIsPropagated() = runBlocking<Unit> {
        var cancelled = false
        try { loadOrFallback<String>("uri") { throw CancellationException() } }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}

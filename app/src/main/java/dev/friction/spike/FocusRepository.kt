package dev.friction.spike

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.core.DataStoreFactory
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first

/** One process-wide owner, main-thread mutations, immutable DataStore snapshots on an IO writer. */
class FocusRepository private constructor(private val context: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val snapshots = Channel<Pair<Long, FocusState>>(Channel.CONFLATED)
    private val file = java.io.File(context.filesDir, "focus-v1.json")
    private val dataStore = DataStoreFactory.create(FocusSerializer, produceFile = { file })
    var engine: FocusEngine? = null
        private set
    val ready = mutableStateOf(false)
    val presentation = FocusPresentation()
    val error = mutableStateOf("")
    val saving = mutableStateOf(false)
    val savedOn = mutableStateOf<Boolean?>(null)
    private var lastSave = 0L
    private var generation = 0L
    private val excluded = recoveryPackages(context)
    init {
        scope.launch {
            try {
                val exists = withContext(Dispatchers.IO) { file.exists() }
                val loaded = dataStore.data.first()
                val state = if (exists) FocusCodec.decode(FocusCodec.encode(loaded)) else migrateSpike()
                savedOn.value = state.on
                engine = FocusEngine(state, excluded).also { it.recover(now(), ZoneId.systemDefault()) }
                ready.value = true
                checkpoint(true)
            } catch (e: Exception) {
                error.value = "Saved state could not be loaded (${e.javaClass.simpleName}). Enforcement unavailable. Use Android Accessibility settings for recovery."
            }
        }
        scope.launch {
            for (snapshot in snapshots) {
                var candidate = snapshot
                while (true) {
                    try {
                        dataStore.updateData { candidate.second }
                        savedOn.value = candidate.second.on
                        error.value = ""
                        saving.value = candidate.first != generation
                        break
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        error.value = "Saving failed (${e.javaClass.simpleName}); recent changes may not survive a restart."
                        delay(2_000)
                        snapshots.tryReceive().getOrNull()?.let { candidate = it }
                    }
                }
            }
        }
    }
    private suspend fun migrateSpike(): FocusState = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("spike", Context.MODE_PRIVATE)
        migrateLegacySpike(
            prefs.getBoolean("armed", false), prefs.getString("target", "").orEmpty(),
            prefs.getLong("used", 0), prefs.getLong("remaining", 0),
            prefs.getLong("away", 0), prefs.getLong("checkpoint", 0), excluded,
        )
    }
    fun now() = Stamp(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1))
    fun activate() { engine?.activate(); changed() }
    fun confirm(attempt: DeactivationAttempt) { engine?.let { if (attempt.confirm(it)) changed() } }
    fun configure(pkg: String, rule: AppRule) { engine?.configure(pkg, rule); changed() }
    fun settings(intervention: Long, reset: Long, photos: List<String>) {
        engine?.settings(intervention, reset, photos); changed()
    }
    private fun changed() { checkpoint(true) }
    fun checkpoint(force: Boolean = false) {
        val e = engine ?: return
        val elapsed = SystemClock.elapsedRealtime()
        if (!force && elapsed - lastSave < 1_000) return
        lastSave = elapsed
        generation++
        saving.value = true
        // Detach mutable engine state; DataStore must never see a mutable live object.
        snapshots.trySend(generation to presentation.publish(e.state))
    }
    fun recoverService() { engine?.recover(now(), ZoneId.systemDefault()); checkpoint(true) }
    companion object {
        @Volatile private var instance: FocusRepository? = null
        fun get(context: Context): FocusRepository = instance ?: synchronized(this) {
            instance ?: FocusRepository(context.applicationContext as Application).also { instance = it }
        }
    }
}

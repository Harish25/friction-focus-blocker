package dev.friction.spike

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.doOnAttach
import java.time.ZoneId
import kotlinx.coroutines.*

class FocusService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: FocusRepository
    private var overlay: InterventionView? = null
    private var photoJob: Job? = null
    private var overlayKey: Pair<String, Barrier>? = null
    private var waitingPackage: String? = null
    private var homeRequestedAt = 0L
    private var restored = false
    private var lastPackage: String? = null
    private var lastBarrier = Barrier.None

    override fun onServiceConnected() {
        repo = FocusRepository.get(this)
        connected.value = true
        problem.value = ""
        restored = false
        handler.removeCallbacks(tick)
        handler.post(tick)
    }
    private val tick = object : Runnable {
        override fun run() { reconcile(); handler.postDelayed(this, 200) }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (::repo.isInitialized) reconcile()
    }
    private fun foreground(): String? {
        val visible = windows
        if (visible.any { it.type == AccessibilityWindowInfo.TYPE_SYSTEM && (it.isActive || it.isFocused) }) return null
        val apps = visible.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (apps.size != 1 || apps.any { it.isInPictureInPictureMode }) return null
        return apps.single().root?.packageName?.toString()
    }
    private fun reconcile() {
        val engine = repo.engine ?: return
        if (!restored) { repo.recoverService(); restored = true }
        val now = repo.now()
        val awake = getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val pkg = if (awake) foreground() else null
        observed.value = pkg ?: "Paused: system, locked, unknown, or unsupported window layout"
        if (waitingPackage != null && pkg != waitingPackage) waitingPackage = null
        if (waitingPackage != null && now.elapsed - homeRequestedAt > 2_000) {
            waitingPackage = null
            problem.value = "Home was requested but not observed. Use system navigation or Accessibility settings."
        }
        val target = pkg?.takeIf { waitingPackage == null }
        val visible = overlay != null && overlayKey?.first == target && awake && overlay!!.isShown
        engine.observe(target, visible, now, ZoneId.systemDefault(), visible && overlayKey?.second == Barrier.Ordinary)
        val barrier = engine.barrier(target)
        if (barrier != Barrier.None && target != null) showOverlay(target, barrier) else removeOverlay()
        overlay?.update(engine.state.usage[target]?.remaining ?: 0, engine.state.interventionMs)
        repo.checkpoint(target != lastPackage || barrier != lastBarrier)
        lastPackage = target; lastBarrier = barrier
    }
    private fun showOverlay(pkg: String, barrier: Barrier) {
        if (overlayKey == (pkg to barrier) && overlay != null) return
        removeOverlay()
        val appName = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            catch (_: Exception) { pkg }
        val container = InterventionView(
            this, barrier, appName, repo.engine?.state?.usage?.get(pkg)?.used ?: 0,
            exit = {
                if (performGlobalAction(GLOBAL_ACTION_HOME)) {
                    waitingPackage = pkg; homeRequestedAt = SystemClock.elapsedRealtime()
                    removeOverlay()
                    repo.engine?.observe(null, false, repo.now(), ZoneId.systemDefault())
                    repo.checkpoint(true)
                } else problem.value = "Android rejected Exit-to-Home. Use system navigation or recovery settings."
            },
            deactivate = { openScreen(Intent(this, MainActivity::class.java).putExtra("deactivate", true)) },
            recovery = { openScreen(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        )
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE)
            .apply { gravity = Gravity.TOP or Gravity.START }
        try {
            getSystemService(WindowManager::class.java).addView(container, params)
            overlay = container; overlayKey = pkg to barrier; container.requestFocus()
            container.doOnAttach {
                if (overlay === container) {
                    photoJob = scope.launch { container.rotatePhotos(repo.engine!!.state.photos.toList()) }
                }
            }
        } catch (e: RuntimeException) {
            problem.value = "Overlay unavailable (${e.javaClass.simpleName}). Enforcement is unavailable."
        }
    }
    private fun openScreen(intent: Intent) {
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        catch (e: RuntimeException) { problem.value = "Could not open recovery screen: ${e.javaClass.simpleName}" }
    }
    private fun removeOverlay() {
        photoJob?.cancel(); photoJob = null
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null; overlayKey = null
    }
    override fun onInterrupt() {
        removeOverlay()
        if (::repo.isInitialized) {
            repo.engine?.observe(null, false, repo.now(), ZoneId.systemDefault()); repo.checkpoint(true)
        }
        problem.value = "Accessibility was interrupted; verify enforcement."
    }
    override fun onDestroy() { disconnect(); scope.cancel(); super.onDestroy() }
    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    private fun disconnect() {
        handler.removeCallbacks(tick)
        if (::repo.isInitialized) {
            repo.engine?.observe(null, false, repo.now(), ZoneId.systemDefault())
            repo.engine?.forgetObservation(); repo.checkpoint(true)
        }
        restored = false; removeOverlay(); connected.value = false
    }
    companion object {
        val connected = mutableStateOf(false)
        val observed = mutableStateOf("No observation yet")
        val problem = mutableStateOf("")
    }
}

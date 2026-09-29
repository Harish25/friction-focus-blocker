package dev.friction.spike

import android.app.KeyguardManager
import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.animation.ValueAnimator
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class OffViewModel : ViewModel() {
    var attempt by mutableStateOf<DeactivationAttempt?>(null)
    var photo by mutableStateOf<String?>(null)
    var message by mutableStateOf("")
    fun begin(photos: List<String>) {
        if (attempt == null) {
            attempt = DeactivationAttempt(); photo = MotivationPhotos.next(photos)
            message = MotivationMessages.choose()
        }
    }
    fun cancel() { attempt = null; photo = null; message = "" }
}

class MainActivity : ComponentActivity() {
    private var resumed = false
    private val off by viewModels<OffViewModel>()
    private var requestOff by mutableStateOf(false)
    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; off.attempt?.pause(); super.onPause() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); requestOff = intent.getBooleanExtra("deactivate", false) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a new explicit route starts an attempt; process restoration never resumes one.
        requestOff = savedInstanceState == null && intent.getBooleanExtra("deactivate", false)
        intent.removeExtra("deactivate")
        val repo = FocusRepository.get(this)
        setContent {
            MaterialTheme(colorScheme = FrictionColors) {
                Surface(Modifier.fillMaxSize()) {
                    val state = repo.presentation.state.value
                    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
                    LaunchedEffect(repo.ready.value) {
                        while (repo.ready.value) {
                            if (!FocusService.connected.value) {
                                repo.engine?.observe(null, false, repo.now(), java.time.ZoneId.systemDefault())
                                repo.checkpoint()
                            }
                            delay(1000)
                        }
                    }
                    LaunchedEffect(Unit) {
                        apps = withContext(Dispatchers.IO) {
                            val excluded = recoveryPackages(this@MainActivity)
                            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                                .filter { it.activityInfo.packageName !in excluded }
                                .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
                                .distinctBy { it.second }.sortedBy { it.first.lowercase() }
                        }
                    }
                    LaunchedEffect(requestOff, repo.ready.value) {
                        if (requestOff && state != null) {
                            if (state.on) off.begin(state.photos)
                            requestOff = false
                        }
                    }
                    if (!repo.ready.value || state == null) {
                        Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                            Text("Friction", style = MaterialTheme.typography.headlineLarge)
                            Text(repo.error.value.ifBlank { "Loading saved focus state…" })
                            Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility settings") }
                        }
                    } else if (off.attempt != null && state.on) {
                        DeactivationScreen(repo, off)
                    } else {
                        var query by remember { mutableStateOf("") }
                        var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
                        var globalEdit by remember { mutableStateOf(false) }
                        var photoMessage by remember { mutableStateOf("") }
                        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
                            if (uris.isNotEmpty() && !state.on) {
                                var missingGrant = false
                                uris.forEach { uri ->
                                    try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                    catch (_: SecurityException) { missingGrant = true }
                                }
                                val old = state.photos
                                val selected = uris.map { it.toString() }
                                repo.settings(state.interventionMs, state.resetMs, selected)
                                releasePhotos(old - selected.toSet())
                                photoMessage = if (missingGrant) "Some photos may need reselecting after restart. Text fallback remains available." else "Photos selected."
                            }
                        }
                        LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item {
                                Spacer(Modifier.height(12.dp))
                                Text("Friction", style = MaterialTheme.typography.headlineLarge)
                                Text("Focus state: ${if (state.on) "ON" else "OFF"}", style = MaterialTheme.typography.titleLarge)
                                Text("Saved state: ${repo.savedOn.value?.let { if (it) "ON" else "OFF" } ?: "not yet saved"}")
                                Text(if (FocusService.connected.value) "Accessibility service connected" else "Enforcement unavailable: accessibility service disconnected")
                                Text(FocusService.observed.value)
                                if (FocusService.problem.value.isNotBlank()) Text(FocusService.problem.value)
                                if (repo.error.value.isNotBlank()) Text(repo.error.value)
                            }
                            item {
                                if (state.on) Button(onClick = { off.begin(state.photos) }) { Text("Turn off Friction…") }
                                else Button(onClick = { repo.activate() }) { Text("Turn on Friction") }
                                Text("Single-window use only. Split-screen, PiP, system surfaces and unknown windows pause enforcement. Phone hardening tests remain pending.")
                            }
                            item {
                                Text("Setup & recovery", style = MaterialTheme.typography.titleMedium)
                                Text("Friction uses Accessibility to observe foreground package names and block selected apps. It does not read screen text. Home, calling, Settings and uninstall remain accessible.")
                                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility settings") }
                            }
                            item {
                                Text("Focus settings", style = MaterialTheme.typography.titleMedium)
                                Text("Ordinary pause: ${state.interventionMs / 1000}s · Session reset: ${state.resetMs / 1000}s away")
                                Text("Manual turn-off always requires 30 visible seconds and confirmation.")
                                OutlinedButton(enabled = !state.on, onClick = { globalEdit = true }) { Text("Edit timing") }
                                Text("Motivation photos: ${state.photos.size}. Missing photos use a text fallback.")
                                OutlinedButton(enabled = !state.on, onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose / replace photos") }
                                if (state.photos.isNotEmpty()) TextButton(enabled = !state.on, onClick = {
                                    val old = state.photos
                                    repo.settings(state.interventionMs, state.resetMs, emptyList()); releasePhotos(old)
                                }) { Text("Remove photos") }
                                if (photoMessage.isNotBlank()) Text(photoMessage)
                                if (state.on) Text("Turn Friction off to edit policies, timing or photos.")
                            }
                            item {
                                Text("Apps", style = MaterialTheme.typography.titleLarge)
                                Text("Interrupted and Blocked apps appear first. Allowed apps follow below. Recovery and calling apps are excluded.")
                                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Find an app") }, modifier = Modifier.fillMaxWidth())
                            }
                            val configuredMissing = state.rules.keys.filter { p -> apps.none { it.second == p } }
                                .filter { it !in recoveryPackages(this@MainActivity) }.map { it to it }
                            val rows = appListRows(apps + configuredMissing, state.rules, query)
                            rows.apps.forEach { app ->
                                if (app.second == rows.dividerBefore) item(key = "apps-divider") {
                                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                                    Text("Allowed apps", style = MaterialTheme.typography.titleMedium)
                                }
                                item(key = app.second) {
                                    val rule = state.rules[app.second] ?: AppRule()
                                    OutlinedCard(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(app.first, style = MaterialTheme.typography.titleMedium)
                                            Text(app.second, style = MaterialTheme.typography.bodySmall)
                                            Text(rule.policy.name)
                                            if (rule.policy == Policy.Interrupted) {
                                                Text("Initial ${rule.initialMs / 1000}s · Repeat ${rule.repeatMs / 1000}s · Cap ${rule.capMs?.let { "${it / 1000}s" } ?: "off"}")
                                                Text("Today: ${(state.usage[app.second]?.daily ?: 0) / 1000}s while ON")
                                            }
                                            TextButton(enabled = !state.on, onClick = { editing = app }) { Text("Edit policy") }
                                        }
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(20.dp)) }
                        }
                        editing?.let { app ->
                            RuleEditor(app.first, state.rules[app.second] ?: AppRule(), onDismiss = { editing = null }) { rule ->
                                if (!state.on) repo.configure(app.second, rule)
                                editing = null
                            }
                        }
                        if (globalEdit) TimingEditor(state.interventionMs, state.resetMs, { globalEdit = false }) { intervention, reset ->
                            if (!state.on) repo.settings(intervention, reset, state.photos)
                            globalEdit = false
                        }
                    }
                }
            }
        }
    }
    private fun releasePhotos(photos: List<String>) {
        photos.forEach { try { contentResolver.releasePersistableUriPermission(it.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { } }
    }
    @Composable private fun DeactivationScreen(repo: FocusRepository, model: OffViewModel) {
        val attempt = model.attempt ?: return
        var remaining by remember(attempt) { mutableLongStateOf(attempt.remaining) }
        var visible by remember { mutableStateOf(false) }
        BackHandler { model.cancel() }
        LaunchedEffect(attempt) {
            while (true) {
                visible = resumed && window.decorView.hasWindowFocus() && getSystemService(PowerManager::class.java).isInteractive &&
                    !getSystemService(KeyguardManager::class.java).isKeyguardLocked
                attempt.tick(SystemClock.elapsedRealtime(), visible)
                remaining = attempt.remaining
                delay(100)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
            val availableHeight = maxHeight
            val needsScroll = availableHeight < 560.dp || LocalDensity.current.fontScale > 1.4f
            val layout = if (needsScroll) Modifier.fillMaxWidth().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()
            Column(layout) {
                Text("Pause for what matters", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(model.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                val photoLayout = if (needsScroll) Modifier.fillMaxWidth().height(maxOf(220.dp, availableHeight * .6f))
                    else Modifier.fillMaxWidth().weight(1f)
                MotivationPhotoCarousel(repo.engine!!.state.photos, model, visible, photoLayout)
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Friction stays ON until you confirm.", style = MaterialTheme.typography.bodyMedium)
                    Text(resources.getQuantityString(R.plurals.countdown_seconds, ((remaining + 999) / 1000).toInt(), ((remaining + 999) / 1000).toInt()), style = MaterialTheme.typography.titleLarge)
                    Button(modifier = Modifier.fillMaxWidth(), enabled = remaining == 0L,
                        onClick = { repo.confirm(attempt); model.cancel() }) { Text("Confirm: turn off Friction") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { model.cancel() }) { Text("Keep focusing") }
                }
            }
        }
    }
    @Composable private fun MotivationPhotoCarousel(references: List<String>, model: OffViewModel, visible: Boolean, modifier: Modifier) {
        var bitmap by remember(model.attempt) { mutableStateOf<android.graphics.Bitmap?>(null) }
        var position by remember(model.attempt) { mutableStateOf("") }
        LaunchedEffect(model.attempt, references, visible) {
            if (!visible || references.isEmpty()) return@LaunchedEffect
            val start = references.indexOf(model.photo).coerceAtLeast(0)
            val candidates = (references.drop(start) + references.take(start)).toMutableList()
            var index = 0
            while (currentCoroutineContext().isActive && candidates.isNotEmpty()) {
                val reference = candidates[index]
                val loaded = MotivationPhotos.load(this@MainActivity, reference)
                if (loaded == null) {
                    candidates.removeAt(index)
                    if (candidates.isNotEmpty()) index %= candidates.size
                    continue
                }
                bitmap = loaded
                model.photo = reference
                position = if (candidates.size > 1) getString(R.string.photo_position, index + 1, candidates.size) else ""
                if (candidates.size == 1) return@LaunchedEffect
                delay(4_000)
                index = (index + 1) % candidates.size
            }
            bitmap = null
            position = ""
        }
        Box(modifier.clip(RoundedCornerShape(16.dp))) {
            Crossfade(bitmap, animationSpec = tween(if (ValueAnimator.areAnimatorsEnabled()) 450 else 0), label = "Motivation photo") { image ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    image?.let { Image(it.asImageBitmap(), "Your motivation photo", Modifier.fillMaxSize()) }
                        ?: Text("Make space for what matters to you.")
                }
            }
            if (position.isNotBlank()) Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
            ) { Text(position, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium) }
        }
    }

}

@Composable private fun SecondsField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}
private fun milliseconds(text: String, maxSeconds: Long = 86_400): Long? =
    text.toLongOrNull()?.takeIf { it in 1..maxSeconds }?.times(1000)

@Composable private fun RuleEditor(name: String, initial: AppRule, onDismiss: () -> Unit, save: (AppRule) -> Unit) {
    var policy by remember { mutableStateOf(initial.policy) }
    var first by remember { mutableStateOf((initial.initialMs / 1000).toString()) }
    var repeat by remember { mutableStateOf((initial.repeatMs / 1000).toString()) }
    var cap by remember { mutableStateOf(initial.capMs?.div(1000)?.toString().orEmpty()) }
    val valid = policy != Policy.Interrupted || (milliseconds(first) != null && milliseconds(repeat) != null && (cap.isBlank() || milliseconds(cap) != null))
    AlertDialog(onDismissRequest = onDismiss, title = { Text(name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Policy.entries.forEach { p -> Row { RadioButton(policy == p, { policy = p }); Text(p.name, Modifier.padding(top = 12.dp)) } }
            if (policy == Policy.Interrupted) {
                SecondsField("Initial use (seconds)", first) { first = it }
                SecondsField("Repeat use (seconds)", repeat) { repeat = it }
                SecondsField("Daily cap (seconds, blank = off)", cap) { cap = it }
                Text("Use whole seconds from 1 to 86400. Daily totals are retained when policies change.")
            }
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = {
        save(AppRule(policy, milliseconds(first) ?: initial.initialMs, milliseconds(repeat) ?: initial.repeatMs, milliseconds(cap)))
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun TimingEditor(initial: Long, resetInitial: Long, dismiss: () -> Unit, save: (Long, Long) -> Unit) {
    var duration by remember { mutableStateOf((initial / 1000).toString()) }
    var reset by remember { mutableStateOf((resetInitial / 1000).toString()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Timing") }, text = {
        Column {
            SecondsField("Ordinary pause (1–3600 seconds)", duration) { duration = it }
            SecondsField("Session reset (1–86400 seconds away)", reset) { reset = it }
            Text("Defaults: 30 seconds pause, 1500 seconds (25 minutes) away. Manual turn-off remains 30 seconds.")
        }
    }, confirmButton = { TextButton(enabled = milliseconds(duration, 3600) != null && milliseconds(reset) != null,
        onClick = { save(milliseconds(duration, 3600)!!, milliseconds(reset)!!) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

package dev.friction.spike

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.widget.*
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.*

/** Service-owned presentation only: the evaluator remains the sole owner of countdown time. */
@SuppressLint("ViewConstructor") // Created only by the service, never inflated from XML.
class InterventionView(
    context: Context,
    private val barrier: Barrier,
    appName: String,
    usedMs: Long,
    exit: () -> Unit,
    deactivate: () -> Unit,
    recovery: () -> Unit,
) : ScrollView(context) {
    private val ink = FrictionColors.onSurface.toArgb()
    private val primary = FrictionColors.primary.toArgb()
    private val surface = FrictionColors.surface.toArgb()
    private val body: LinearLayout
    private val timer: TextView
    private val progress: ProgressBar
    private val photoFrame: FrameLayout
    private val photos = arrayOf(ImageView(context), ImageView(context))
    private val fallback: TextView
    private val position: TextView
    private var activePhoto = 0
    private var hasPhoto = false

    init {
        isFillViewport = true
        isFocusableInTouchMode = true
        clipToPadding = true
        setBackgroundColor(surface)
        setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }
        addView(body, LayoutParams(-1, -2))
        fun space(height: Int) { body.addView(View(context), LinearLayout.LayoutParams(1, dp(height))) }
        body.addView(label("Pause for what matters", 20f, ink))
        space(8)
        body.addView(label(MotivationMessages.choose(), 16f, FrictionColors.onSurfaceVariant.toArgb()))
        space(12)
        val description = when (barrier) {
            Barrier.Ordinary -> "$appName · ${usedMs / 60_000}m ${(usedMs / 1000) % 60}s of use."
            Barrier.DailyCap -> "$appName has reached its daily limit.\nA fresh allowance begins tomorrow."
            else -> "$appName is paused while you focus."
        }
        photoFrame = FrameLayout(context).apply {
            background = rounded(FrictionColors.surfaceContainerLow.toArgb(), 16)
            clipToOutline = true
        }
        // Fill the space above the actions; retain a usable image height when scrolling is needed.
        body.addView(photoFrame, LinearLayout.LayoutParams(-1, dp(220), 1f))
        fallback = label("Make space for the people,\nplaces and moments you love.", 20f, ink).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        photoFrame.addView(fallback, FrameLayout.LayoutParams(-1, -1))
        photos.forEach { photo ->
            photo.scaleType = ImageView.ScaleType.FIT_CENTER
            photo.visibility = View.INVISIBLE
            photo.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            photoFrame.addView(photo, FrameLayout.LayoutParams(-1, -1))
        }
        photoFrame.contentDescription = "Your motivation photos"
        position = label("", 12f, FrictionColors.onSurface.toArgb()).apply {
            background = rounded(FrictionColors.surface.copy(alpha = .92f).toArgb(), 20)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        photoFrame.addView(position, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(12)
        })
        space(16)
        body.addView(label(description, 14f, ink).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        space(16)
        val timerCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(timerCard, LinearLayout.LayoutParams(-1, -2))
        val timerRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        timerCard.addView(timerRow)
        timer = label("", 20f, ink).apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }
        timerRow.addView(timer, LinearLayout.LayoutParams(0, -2, 1f))
        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(primary)
            progressBackgroundTintList = ColorStateList.valueOf(FrictionColors.secondaryContainer.toArgb())
            visibility = if (barrier == Barrier.Ordinary) View.VISIBLE else View.GONE
        }
        timerCard.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(12) })
        space(16)
        body.addView(action(context.getString(R.string.exit_home), primary, FrictionColors.onPrimary.toArgb(), exit), LinearLayout.LayoutParams(-1, -2))
        space(10)
        body.addView(action(context.getString(R.string.turn_off), surface, primary, deactivate, outlined = true), LinearLayout.LayoutParams(-1, -2))
        space(6)
        body.addView(action(context.getString(R.string.recovery_settings), surface, primary, recovery, 14f), LinearLayout.LayoutParams(-1, -2))
    }

    fun update(remainingMs: Long, durationMs: Long) {
        val seconds = (remainingMs + 999) / 1000
        val text = when (barrier) {
            Barrier.Ordinary -> resources.getQuantityString(R.plurals.countdown_seconds, seconds.toInt(), seconds.toInt())
            Barrier.DailyCap -> "Done for today"
            else -> "Focus is on"
        }
        if (timer.text.toString() != text) {
            timer.text = text
            timer.contentDescription = if (barrier == Barrier.Ordinary) "$seconds seconds remaining" else text
        }
        progress.progress = (remainingMs * 1000 / durationMs.coerceAtLeast(1)).coerceIn(0, 1000).toInt()
    }

    /** Rotate while this screen is attached; no timer state changes and no interaction bypass. */
    suspend fun rotatePhotos(references: List<String>) {
        if (references.isEmpty()) return
        val first = MotivationPhotos.next(references)
        val start = references.indexOf(first).coerceAtLeast(0)
        val candidates = (references.drop(start) + references.take(start)).toMutableList()
        var index = 0
        while (currentCoroutineContext().isActive && isAttachedToWindow && candidates.isNotEmpty()) {
            val reference = candidates[index]
            val bitmap = MotivationPhotos.load(context, reference)
            currentCoroutineContext().ensureActive()
            if (!isAttachedToWindow) return
            if (bitmap == null) {
                candidates.removeAt(index)
                if (candidates.isNotEmpty()) index %= candidates.size
                continue
            }
            val previous = photos[activePhoto]
            val next = photos[1 - activePhoto]
            next.animate().cancel()
            next.setImageBitmap(bitmap)
            next.alpha = 0f
            next.visibility = View.VISIBLE
            fallback.visibility = View.INVISIBLE
            val transitionMs = if (hasPhoto && ValueAnimator.areAnimatorsEnabled()) 450L else 0L
            next.animate().alpha(1f).setDuration(transitionMs).withEndAction(null).start()
            previous.animate().cancel()
            previous.animate().alpha(0f).setDuration(transitionMs).withEndAction {
                previous.visibility = View.INVISIBLE
                previous.setImageDrawable(null)
            }.start()
            activePhoto = 1 - activePhoto
            hasPhoto = true
            position.text = context.getString(R.string.photo_position, index + 1, candidates.size)
            position.visibility = if (candidates.size > 1) View.VISIBLE else View.GONE
            if (candidates.size == 1) return
            delay(4_000)
            index = (index + 1) % candidates.size
        }
        if (candidates.size <= 1) position.visibility = View.GONE
        if (candidates.isEmpty()) {
            photos.forEach { it.animate().cancel(); it.visibility = View.INVISIBLE; it.setImageDrawable(null) }
            fallback.visibility = View.VISIBLE
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (event.keyCode == KeyEvent.KEYCODE_BACK) true else super.dispatchKeyEvent(event)

    override fun onDetachedFromWindow() {
        photos.forEach { it.animate().cancel(); it.setImageDrawable(null) }
        super.onDetachedFromWindow()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float, color: Int) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(color); includeFontPadding = false
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun action(text: String, fill: Int, color: Int, click: () -> Unit, size: Float = 14f, outlined: Boolean = false) = Button(context).apply {
        this.text = text
        textSize = size
        isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(color)
        minHeight = dp(52)
        minimumHeight = dp(52)
        setPadding(dp(20), dp(14), dp(20), dp(14))
        val shape = rounded(fill, 28).apply {
            if (outlined) setStroke(dp(1), FrictionColors.outline.toArgb())
        }
        background = RippleDrawable(ColorStateList.valueOf(FrictionColors.primary.copy(alpha = .12f).toArgb()), shape, rounded(-1, 28))
        stateListAnimator = null
        setOnClickListener { click() }
    }
}

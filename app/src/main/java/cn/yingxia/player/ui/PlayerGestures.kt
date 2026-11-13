package cn.yingxia.player.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.SeekBar
import cn.yingxia.player.core.*
import kotlin.math.abs
import kotlin.math.hypot

/** One recognizer owns a touch stream. A second finger zooms and never commits a seek. CANCEL never commits a seek or a tap. */
internal class PlayerGestureView(context: Context) : View(context) {
    var snapshot: () -> PlaybackState = { PlaybackState() }
    var fullscreen = false
    var locked = false
    var holdSpeed = 2f
    var sensitivity = 1f
    var zoom = 1f
    var panX = 0f
    var panY = 0f
    var command: (PlayerCommand) -> Unit = {}
    var tap: () -> Unit = {}
    var preview: (Long?) -> Unit = {}
    var vertical: (GestureMode, Float, Boolean) -> Unit = { _, _, _ -> }
    var onTransform: (Float, Float, Float) -> Unit = { _, _, _ -> }
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var mode = GestureMode.PENDING
    private var downX = 0f
    private var downY = 0f
    private var start = 0L
    private var duration = 0L
    private var target = 0L
    private var moved = false
    private var active = false
    private var double = false
    private var lastUp = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var pinchBase = 1f
    private var pinchZoom = 1f
    private var pinchX = 0f
    private var pinchY = 0f
    private val singleTap = Runnable { lastUp = 0; tap() }
    private val hold = Runnable {
        if (active && !moved && !double && mode == GestureMode.PENDING && snapshot().isPlaying && !locked) {
            mode = GestureMode.BOOST
            command(PlayerCommand.BeginBoost(holdSpeed))
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }
    init { isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun performClick(): Boolean { super.performClick(); tap(); return true }
    private fun dragSlop() = slop * gestureSlopScale(sensitivity)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked) { cancelGesture(); return true }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true; moved = false; mode = GestureMode.PENDING
                downX = event.x; downY = event.y
                start = snapshot().positionMs; duration = snapshot().durationMs
                double = lastUp != 0L && event.eventTime - lastUp <= ViewConfiguration.getDoubleTapTimeout() &&
                    abs(lastX - event.x) < slop * 5 && abs(lastY - event.y) < slop * 5
                if (double) handler.removeCallbacks(singleTap)
                if (!double) handler.postDelayed(hold, 450)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) beginPinch(event)
            MotionEvent.ACTION_MOVE -> if (active && mode == GestureMode.PINCH && event.pointerCount >= 2) movePinch(event) else if (active) {
                val dx = event.x - downX; val dy = event.y - downY
                val threshold = dragSlop()
                if (abs(dx) > threshold || abs(dy) > threshold) { moved = true; handler.removeCallbacks(hold) }
                if (mode == GestureMode.PENDING && moved) {
                    mode = chooseDrag(dx, dy, threshold, fullscreen, downX < width / 2f)
                    if (mode == GestureMode.BRIGHTNESS || mode == GestureMode.VOLUME) vertical(mode, 0f, true)
                }
                when (mode) {
                    GestureMode.SEEK -> {
                        val span = (duration / 3).coerceIn(10_000, 180_000)
                        target = PlaybackRules.seek(start + (dx / width.coerceAtLeast(1) * span).toLong(), duration)
                        preview(target)
                    }
                    GestureMode.BRIGHTNESS, GestureMode.VOLUME -> vertical(mode, -dy / height.coerceAtLeast(1), false)
                    else -> Unit
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == GestureMode.PINCH && event.pointerCount <= 2) {
                active = false; moved = true; mode = GestureMode.PENDING; lastUp = 0
            }
            MotionEvent.ACTION_CANCEL -> cancelGesture()
            MotionEvent.ACTION_UP -> if (active) {
                handler.removeCallbacks(hold)
                when (mode) {
                    GestureMode.SEEK -> { preview(null); command(PlayerCommand.Seek(target)) }
                    GestureMode.BOOST -> command(PlayerCommand.EndBoost)
                    GestureMode.PINCH -> Unit
                    GestureMode.PENDING -> if (!moved) {
                        if (double) { lastUp = 0; command(PlayerCommand.Toggle) }
                        else {
                            lastUp = event.eventTime; lastX = event.x; lastY = event.y
                            handler.postDelayed(singleTap, ViewConfiguration.getDoubleTapTimeout().toLong())
                        }
                    }
                    else -> Unit
                }
                active = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
    private fun beginPinch(event: MotionEvent) {
        handler.removeCallbacks(hold); handler.removeCallbacks(singleTap)
        if (mode == GestureMode.BOOST) command(PlayerCommand.EndBoost)
        if (mode == GestureMode.SEEK) preview(null)
        mode = GestureMode.PINCH; moved = true; lastUp = 0; active = true
        pinchBase = span(event).coerceAtLeast(1f); pinchZoom = zoom
        val (x, y) = centroid(event); pinchX = x; pinchY = y
        parent?.requestDisallowInterceptTouchEvent(true)
    }
    private fun movePinch(event: MotionEvent) {
        val next = (pinchZoom * span(event) / pinchBase).coerceIn(1f, 4f)
        val (x, y) = centroid(event)
        val limitX = width * (next - 1f)
        val limitY = height * (next - 1f)
        panX = if (next <= 1.01f) 0f else (panX + x - pinchX).coerceIn(-limitX, limitX)
        panY = if (next <= 1.01f) 0f else (panY + y - pinchY).coerceIn(-limitY, limitY)
        pinchX = x; pinchY = y; zoom = next
        onTransform(zoom, panX, panY)
    }
    private fun span(event: MotionEvent) = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    private fun centroid(event: MotionEvent) = (event.getX(0) + event.getX(1)) / 2f to (event.getY(0) + event.getY(1)) / 2f
    fun cancelGesture() {
        handler.removeCallbacks(hold); handler.removeCallbacks(singleTap)
        if (mode == GestureMode.BOOST) command(PlayerCommand.EndBoost)
        preview(null); active = false; lastUp = 0; mode = GestureMode.PENDING
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    override fun onDetachedFromWindow() { cancelGesture(); super.onDetachedFromWindow() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { if (!hasWindowFocus) cancelGesture(); super.onWindowFocusChanged(hasWindowFocus) }
}

/** SeekBar retains native keyboard/TalkBack support and distinguishes UP from CANCEL. */
internal class CommitSeekBar(context: Context) : SeekBar(context) {
    var tracking = false
    var cancelled = false
    var durationMs = 0L
    var commit: (Long) -> Unit = {}
    var preview: (Long?) -> Unit = {}
    init {
        max = 10_000
        contentDescription = "播放进度"
        val pink = android.graphics.Color.rgb(251, 114, 153)
        val tint = android.content.res.ColorStateList.valueOf(pink)
        progressTintList = tint
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(110, 255, 255, 255))
        val thumbPx = (10 * resources.displayMetrics.density).toInt().coerceAtLeast(8)
        thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pink); setSize(thumbPx, thumbPx) }
        thumbOffset = thumbPx / 2
        setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { tracking = true; cancelled = false }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val position = durationMs * progress / max
                    if (tracking) preview(position) else commit(position)
                }
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (!cancelled) commit(durationMs * progress / max)
                tracking = false; preview(null)
            }
        })
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelled = true
        return super.onTouchEvent(event)
    }
}

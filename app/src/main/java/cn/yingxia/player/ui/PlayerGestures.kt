package cn.yingxia.player.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.SeekBar
import cn.yingxia.player.core.*
import kotlin.math.abs

/** One recognizer owns a touch stream. CANCEL never commits a seek or a tap. */
internal class PlayerGestureView(context: Context) : View(context) {
    var snapshot: () -> PlaybackState = { PlaybackState() }
    var fullscreen = false
    var locked = false
    var holdSpeed = 2f
    var command: (PlayerCommand) -> Unit = {}
    var tap: () -> Unit = {}
    var preview: (Long?) -> Unit = {}
    var vertical: (GestureMode, Float, Boolean) -> Unit = { _, _, _ -> }
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
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelGesture()
            MotionEvent.ACTION_MOVE -> if (active) {
                val dx = event.x - downX; val dy = event.y - downY
                if (abs(dx) > slop || abs(dy) > slop) { moved = true; handler.removeCallbacks(hold) }
                if (mode == GestureMode.PENDING && moved) {
                    mode = chooseDrag(dx, dy, slop, fullscreen, downX < width / 2f)
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
            MotionEvent.ACTION_UP -> if (active) {
                handler.removeCallbacks(hold)
                when (mode) {
                    GestureMode.SEEK -> { preview(null); command(PlayerCommand.Seek(target)) }
                    GestureMode.BOOST -> command(PlayerCommand.EndBoost)
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
        // Keep the hit target tall, but draw the track in its lower portion.
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val topPad = (height * 0.62f).toInt()
            val bottomPad = (height * 0.06f).toInt()
            if (paddingTop != topPad || paddingBottom != bottomPad) setPadding(paddingLeft, topPad, paddingRight, bottomPad)
        }
        val tint = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(224, 138, 79))
        progressTintList = tint; thumbTintList = tint
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

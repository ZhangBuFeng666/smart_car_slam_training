package com.example.icarcontroller

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

enum class FullscreenDriveEdge {
    LEFT,
    RIGHT
}

data class FullscreenDriveButtonSpec(
    val command: String,
    val edge: FullscreenDriveEdge
)

data class FullscreenDriveLayoutContract(
    val usesOuterGroupPanels: Boolean,
    val usesGroupTitles: Boolean,
    val keepsCenterUnobstructed: Boolean,
    val controlLayout: String
)

data class FullscreenDriveVisualContract(
    val glassAlpha: Float,
    val pressedAlpha: Float,
    val fixedTouchSizeDp: Int,
    val stopUsesTranslucentRed: Boolean
)

data class FullscreenSpeedSelection(
    val progress: Int,
    val speed: Double,
    val turn: Double
)

class RequestLatencyMeasurement(
    private val startedAtMillis: Long,
    private val onCompleted: (Long) -> Unit
) {
    private var completed = false

    @Synchronized
    fun complete(completedAtMillis: Long) {
        if (completed) return
        completed = true
        onCompleted((completedAtMillis - startedAtMillis).coerceAtLeast(0L))
    }
}

enum class HoldGestureAction {
    NONE,
    START,
    STOP
}

class HoldGestureTracker {
    private var activePointerId: Int? = null

    fun onDown(pointerId: Int): HoldGestureAction {
        if (activePointerId != null) return HoldGestureAction.NONE
        activePointerId = pointerId
        return HoldGestureAction.START
    }

    fun activePointerId(): Int? = activePointerId

    fun onMove(activePointerPresent: Boolean, insideBounds: Boolean): HoldGestureAction =
        if (activePointerId != null && (!activePointerPresent || !insideBounds)) stop() else HoldGestureAction.NONE

    fun onPointerUp(pointerId: Int): HoldGestureAction =
        if (activePointerId == pointerId) stop() else HoldGestureAction.NONE

    fun onUp(): HoldGestureAction = if (activePointerId != null) stop() else HoldGestureAction.NONE

    fun onCancel(): HoldGestureAction = onUp()

    private fun stop(): HoldGestureAction {
        activePointerId = null
        return HoldGestureAction.STOP
    }
}

enum class FullscreenDriveExitAction {
    FORCE_STOP,
    RESTORE_STREAM,
    REMOVE_OVERLAY,
    RESTORE_PORTRAIT,
    RESTORE_SYSTEM_UI
}

fun fullscreenDriveButtonSpecs(): List<FullscreenDriveButtonSpec> = listOf(
    FullscreenDriveButtonSpec("front", FullscreenDriveEdge.LEFT),
    FullscreenDriveButtonSpec("back", FullscreenDriveEdge.LEFT),
    FullscreenDriveButtonSpec("left", FullscreenDriveEdge.LEFT),
    FullscreenDriveButtonSpec("right", FullscreenDriveEdge.LEFT),
    FullscreenDriveButtonSpec("turn_left", FullscreenDriveEdge.RIGHT),
    FullscreenDriveButtonSpec("stop", FullscreenDriveEdge.RIGHT),
    FullscreenDriveButtonSpec("turn_right", FullscreenDriveEdge.RIGHT)
)

fun fullscreenDriveLayoutContract(): FullscreenDriveLayoutContract =
    FullscreenDriveLayoutContract(
        usesOuterGroupPanels = InteractionSpec.cameraFullscreenUsesControlPanels(),
        usesGroupTitles = false,
        keepsCenterUnobstructed = true,
        controlLayout = InteractionSpec.cameraFullscreenControlLayout()
    )

fun fullscreenDriveVisualContract(): FullscreenDriveVisualContract =
    FullscreenDriveVisualContract(
        glassAlpha = InteractionSpec.cameraGlassButtonAlpha().coerceAtMost(0.18f),
        pressedAlpha = 0.34f,
        fixedTouchSizeDp = 64,
        stopUsesTranslucentRed = true
    )

fun fullscreenDriveExitActions(): List<FullscreenDriveExitAction> = listOf(
    FullscreenDriveExitAction.FORCE_STOP,
    FullscreenDriveExitAction.RESTORE_STREAM,
    FullscreenDriveExitAction.REMOVE_OVERLAY,
    FullscreenDriveExitAction.RESTORE_PORTRAIT,
    FullscreenDriveExitAction.RESTORE_SYSTEM_UI
)

fun fullscreenSpeedSelection(progress: Int): FullscreenSpeedSelection {
    val boundedProgress = progress.coerceIn(0, 30)
    val speed = (0.05 + boundedProgress / 100.0).coerceIn(0.05, 0.35)
    return FullscreenSpeedSelection(
        progress = boundedProgress,
        speed = speed,
        turn = (0.45 + speed * 2.0).coerceIn(0.55, 1.15)
    )
}

fun fullscreenCameraHudText(snapshot: CameraViewSnapshot): String = when (snapshot.state) {
    CameraViewState.LIVE -> if (snapshot.fps > 0) "LIVE / ${snapshot.fps} FPS" else "LIVE / FPS --"
    CameraViewState.CONNECTING -> "CONNECTING / FPS --"
    CameraViewState.BUSY -> "BUSY / FPS --"
    CameraViewState.MISSING -> "MISSING / FPS --"
    CameraViewState.DISCONNECTED -> "DISCONNECTED / FPS --"
    CameraViewState.IDLE -> "IDLE / FPS --"
}

class FullscreenDriveOverlay(
    context: Context,
    val streamView: MjpegStreamView,
    private val directionLabel: (String) -> String,
    private val onMoveStart: (String) -> Unit,
    private val isDirectionActive: (String) -> Boolean,
    private val onStop: () -> Unit,
    private val onExit: () -> Unit,
    private val onSpeedProgressChanged: (Int) -> Unit,
    initialSpeedProgress: Int,
    initialSnapshot: CameraViewSnapshot,
    initialMotion: String
) : FrameLayout(context) {
    private val visualContract = fullscreenDriveVisualContract()
    private val liveText = hudText()
    private val speedText = hudText()
    private val motionText = hudText()
    private val latencyText = hudText()
    private val speedLimitText = hudText()
    private val speedSeekBar = SeekBar(context).apply {
        max = 30
        contentDescription = "Fullscreen speed limit"
        progressTintList = ColorStateList.valueOf(withAlpha(Color.WHITE, 0.82f))
        progressBackgroundTintList = ColorStateList.valueOf(withAlpha(Color.WHITE, 0.24f))
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val selection = fullscreenSpeedSelection(progress)
                renderSpeed(selection)
                onSpeedProgressChanged(selection.progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }
    private lateinit var topHudRow: LinearLayout
    private lateinit var exitButton: TextView
    private lateinit var translationControls: FrameLayout
    private lateinit var turningControls: LinearLayout
    private lateinit var speedControl: LinearLayout

    init {
        setBackgroundColor(Color.BLACK)
        isFocusableInTouchMode = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        addView(streamView, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        addTopHud()
        addTranslationControls()
        addTurningControls()
        addSpeedLimit()
        updateSpeedProgress(initialSpeedProgress)
        updateCameraSnapshot(initialSnapshot)
        updateMotion(initialMotion)
        setOnApplyWindowInsetsListener { _, insets ->
            applySafeInsets(insets)
            insets
        }
    }

    fun updateSpeedProgress(progress: Int) {
        val selection = fullscreenSpeedSelection(progress)
        if (speedSeekBar.progress != selection.progress) {
            speedSeekBar.progress = selection.progress
        }
        renderSpeed(selection)
    }

    fun updateCameraSnapshot(snapshot: CameraViewSnapshot) {
        liveText.text = fullscreenCameraHudText(snapshot)
    }

    fun updateControlLatency(latencyMillis: Long) {
        latencyText.text = "CTRL ${latencyMillis.coerceAtLeast(0L)} ms"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    private fun renderSpeed(selection: FullscreenSpeedSelection) {
        speedText.text = String.format(Locale.US, "SPEED %.2f m/s", selection.speed)
        speedLimitText.text = String.format(Locale.US, "LIMIT %.2f m/s", selection.speed)
        speedLimitText.contentDescription = String.format(
            Locale.US,
            "Speed limit %.2f meters per second, turn %.2f radians per second",
            selection.speed,
            selection.turn
        )
    }

    fun updateMotion(label: String) {
        motionText.text = "MOTION $label"
    }

    private fun addTopHud() {
        topHudRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(liveText, weightedHudParams())
            addView(speedText, weightedHudParams(6))
            addView(motionText, weightedHudParams(6))
            addView(latencyText.apply { text = "CTRL -- ms" }, weightedHudParams(6))
        }
        addView(topHudRow, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(38),
            Gravity.TOP or Gravity.START
        ).apply {
            setMargins(dp(14), dp(10), dp(76), 0)
        })

        exitButton = controlButton("X", "Exit fullscreen", isStop = false) { onExit() }
        addView(exitButton, LayoutParams(
            dp(52),
            dp(52),
            Gravity.TOP or Gravity.END
        ).apply {
            setMargins(0, dp(10), dp(14), 0)
        })
    }

    private fun addTranslationControls() {
        val size = dp(visualContract.fixedTouchSizeDp)
        translationControls = FrameLayout(context)
        translationControls.addView(moveControl("^", "front"), clusterParams(size, size, size, 0))
        translationControls.addView(moveControl("v", "back"), clusterParams(size, size, size, size * 2))
        translationControls.addView(moveControl("<", "left"), clusterParams(size, size, 0, size))
        translationControls.addView(moveControl(">", "right"), clusterParams(size, size, size * 2, size))
        addView(translationControls, LayoutParams(size * 3, size * 3, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(14)
        })
    }

    private fun addTurningControls() {
        val size = dp(visualContract.fixedTouchSizeDp)
        turningControls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(moveControl("CCW", "turn_left"), LinearLayout.LayoutParams(size, size))
            addView(stopControl(), LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(8)
            })
            addView(moveControl("CW", "turn_right"), LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(8)
            })
        }
        addView(turningControls, LayoutParams(
            size * 3 + dp(16),
            size,
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply {
            marginEnd = dp(14)
        })
    }

    private fun addSpeedLimit() {
        speedLimitText.gravity = Gravity.CENTER
        speedControl = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(speedLimitText, LinearLayout.LayoutParams(dp(142), dp(30)))
            addView(speedSeekBar, LinearLayout.LayoutParams(dp(196), dp(30)))
        }
        addView(speedControl, LayoutParams(
            dp(210),
            dp(62),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(12)
        })
    }

    private fun moveControl(label: String, command: String): TextView =
        controlButton(label, directionLabel(command), isStop = false) {
            onMoveStart(command)
            postDelayed({
                if (isDirectionActive(command)) {
                    onStop()
                }
            }, 320L)
        }.also { button ->
            installHoldBehavior(button, onDown = {
                onMoveStart(command)
            })
        }

    private fun stopControl(): TextView =
        controlButton("STOP", "Stop", isStop = true) {
            onStop()
        }.also { button ->
            installHoldBehavior(button, onDown = {
                onStop()
            })
        }

    private fun controlButton(
        label: String,
        description: String,
        isStop: Boolean,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        contentDescription = description
        gravity = Gravity.CENTER
        includeFontPadding = false
        textSize = if (label.length > 2) 11f else 22f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        background = glassBackground(isStop, pressed = false)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installHoldBehavior(button: TextView, onDown: () -> Unit) {
        val tracker = HoldGestureTracker()
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (tracker.onDown(event.getPointerId(event.actionIndex)) == HoldGestureAction.START) {
                        setHoldPressed(view, button, pressed = true)
                        onDown()
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    handleHoldAction(
                        tracker.onPointerUp(event.getPointerId(event.actionIndex)),
                        view,
                        button
                    )
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val pointerIndex = tracker.activePointerId()?.let(event::findPointerIndex) ?: -1
                    val inside = pointerIndex >= 0 &&
                        event.getX(pointerIndex) >= 0f && event.getX(pointerIndex) < view.width.toFloat() &&
                        event.getY(pointerIndex) >= 0f && event.getY(pointerIndex) < view.height.toFloat()
                    handleHoldAction(
                        tracker.onMove(pointerIndex >= 0, inside),
                        view,
                        button
                    )
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handleHoldAction(tracker.onUp(), view, button)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handleHoldAction(tracker.onCancel(), view, button)
                    true
                }
                else -> true
            }
        }
    }

    private fun handleHoldAction(action: HoldGestureAction, view: View, button: TextView) {
        if (action != HoldGestureAction.STOP) return
        setHoldPressed(view, button, pressed = false)
        onStop()
    }

    private fun setHoldPressed(view: View, button: TextView, pressed: Boolean) {
        view.background = glassBackground(button.text == "STOP", pressed)
        view.scaleX = if (pressed) InteractionSpec.pressFeedbackScale() else 1f
        view.scaleY = if (pressed) InteractionSpec.pressFeedbackScale() else 1f
    }

    private fun applySafeInsets(insets: WindowInsets) {
        val safe = safeInsets(insets)
        (topHudRow.layoutParams as LayoutParams).apply {
            setMargins(dp(14) + safe.left, dp(10) + safe.top, dp(76) + safe.right, 0)
            topHudRow.layoutParams = this
        }
        (exitButton.layoutParams as LayoutParams).apply {
            setMargins(0, dp(10) + safe.top, dp(14) + safe.right, 0)
            exitButton.layoutParams = this
        }
        (translationControls.layoutParams as LayoutParams).apply {
            marginStart = dp(14) + safe.left
            translationControls.layoutParams = this
        }
        (turningControls.layoutParams as LayoutParams).apply {
            marginEnd = dp(14) + safe.right
            turningControls.layoutParams = this
        }
        (speedControl.layoutParams as LayoutParams).apply {
            bottomMargin = dp(12) + safe.bottom
            speedControl.layoutParams = this
        }
    }

    @Suppress("DEPRECATION")
    private fun safeInsets(insets: WindowInsets): SafeInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val values = insets.getInsets(
                WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.systemGestures()
            )
            return SafeInsets(values.left, values.top, values.right, values.bottom)
        }

        var safe = SafeInsets(
            insets.systemWindowInsetLeft,
            insets.systemWindowInsetTop,
            insets.systemWindowInsetRight,
            insets.systemWindowInsetBottom
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            insets.displayCutout?.let { cutout ->
                safe = safe.maxWith(SafeInsets(
                    cutout.safeInsetLeft,
                    cutout.safeInsetTop,
                    cutout.safeInsetRight,
                    cutout.safeInsetBottom
                ))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val gestures = insets.systemGestureInsets
            safe = safe.maxWith(SafeInsets(
                gestures.left,
                gestures.top,
                gestures.right,
                gestures.bottom
            ))
        }
        return safe
    }

    private fun hudText(): TextView = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        textSize = 11f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
        setPadding(dp(10), 0, dp(10), 0)
        background = roundedGlass(Color.WHITE, 0.13f, Color.WHITE, 0.28f)
    }

    private fun glassBackground(isStop: Boolean, pressed: Boolean): GradientDrawable {
        val fill = if (isStop) Color.rgb(210, 44, 62) else Color.WHITE
        val border = if (isStop) Color.rgb(255, 116, 126) else Color.WHITE
        val alpha = if (pressed) visualContract.pressedAlpha else visualContract.glassAlpha
        val borderAlpha = if (pressed) 0.92f else 0.46f
        return roundedGlass(fill, alpha, border, borderAlpha)
    }

    private fun roundedGlass(
        fillColor: Int,
        fillAlpha: Float,
        borderColor: Int,
        borderAlpha: Float
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8).toFloat()
        setColor(withAlpha(fillColor, fillAlpha))
        setStroke(dp(1), withAlpha(borderColor, borderAlpha))
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha.coerceIn(0f, 1f) * 255).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun weightedHudParams(marginDp: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(34), 1f).apply {
            marginStart = dp(marginDp)
        }

    private fun clusterParams(
        width: Int,
        height: Int,
        left: Int,
        top: Int
    ): LayoutParams = LayoutParams(width, height).apply {
        leftMargin = left
        topMargin = top
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class SafeInsets(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        fun maxWith(other: SafeInsets): SafeInsets = SafeInsets(
            maxOf(left, other.left),
            maxOf(top, other.top),
            maxOf(right, other.right),
            maxOf(bottom, other.bottom)
        )
    }
}

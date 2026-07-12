package com.example.icarcontroller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
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

class FullscreenDriveOverlay(
    context: Context,
    val streamView: MjpegStreamView,
    private val directionLabel: (String) -> String,
    private val onMoveStart: (String) -> Unit,
    private val isDirectionActive: (String) -> Boolean,
    private val onStop: () -> Unit,
    private val onExit: () -> Unit,
    initialSpeed: Double,
    initialTurn: Double,
    initialMotion: String
) : FrameLayout(context) {
    private val visualContract = fullscreenDriveVisualContract()
    private val speedText = hudText()
    private val motionText = hudText()
    private val latencyText = hudText()
    private val speedLimitText = hudText()

    init {
        setBackgroundColor(Color.BLACK)
        isFocusableInTouchMode = true

        addView(streamView, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        addTopHud()
        addTranslationControls()
        addTurningControls()
        addSpeedLimit()
        updateSpeed(initialSpeed, initialTurn)
        updateMotion(initialMotion)
    }

    fun updateSpeed(speed: Double, turn: Double) {
        speedText.text = String.format(Locale.US, "SPEED %.2f m/s", speed)
        speedLimitText.text = String.format(Locale.US, "LIMIT %.2f m/s", speed)
        speedLimitText.contentDescription = String.format(
            Locale.US,
            "Speed limit %.2f meters per second, turn %.2f radians per second",
            speed,
            turn
        )
    }

    fun updateMotion(label: String) {
        motionText.text = "MOTION $label"
    }

    private fun addTopHud() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(hudText().apply { text = "LIVE / FPS --" }, weightedHudParams())
            addView(speedText, weightedHudParams(6))
            addView(motionText, weightedHudParams(6))
            addView(latencyText.apply { text = "CTRL -- ms" }, weightedHudParams(6))
        }
        addView(row, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(38),
            Gravity.TOP or Gravity.START
        ).apply {
            setMargins(dp(14), dp(10), dp(76), 0)
        })

        addView(controlButton("X", "Exit fullscreen", isStop = false) { onExit() }, LayoutParams(
            dp(52),
            dp(52),
            Gravity.TOP or Gravity.END
        ).apply {
            setMargins(0, dp(10), dp(14), 0)
        })
    }

    private fun addTranslationControls() {
        val size = dp(visualContract.fixedTouchSizeDp)
        val cluster = FrameLayout(context)
        cluster.addView(moveControl("^", "front"), clusterParams(size, size, size, 0))
        cluster.addView(moveControl("v", "back"), clusterParams(size, size, size, size * 2))
        cluster.addView(moveControl("<", "left"), clusterParams(size, size, 0, size))
        cluster.addView(moveControl(">", "right"), clusterParams(size, size, size * 2, size))
        addView(cluster, LayoutParams(size * 3, size * 3, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(14)
        })
    }

    private fun addTurningControls() {
        val size = dp(visualContract.fixedTouchSizeDp)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(moveControl("CCW", "turn_left"), LinearLayout.LayoutParams(size, size))
            addView(stopControl(), LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(8)
            })
            addView(moveControl("CW", "turn_right"), LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(8)
            })
        }
        addView(row, LayoutParams(
            size * 3 + dp(16),
            size,
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply {
            marginEnd = dp(14)
        })
    }

    private fun addSpeedLimit() {
        speedLimitText.gravity = Gravity.CENTER
        addView(speedLimitText, LayoutParams(
            dp(142),
            dp(36),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(12)
        })
    }

    private fun moveControl(label: String, command: String): TextView =
        controlButton(label, directionLabel(command), isStop = false) {
            dispatchWithLatency { onMoveStart(command) }
            postDelayed({
                if (isDirectionActive(command)) {
                    dispatchWithLatency(onStop)
                }
            }, 320L)
        }.also { button ->
            installHoldBehavior(button, onDown = {
                dispatchWithLatency { onMoveStart(command) }
            })
        }

    private fun stopControl(): TextView =
        controlButton("STOP", "Stop", isStop = true) {
            dispatchWithLatency(onStop)
        }.also { button ->
            installHoldBehavior(button, onDown = {
                dispatchWithLatency(onStop)
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
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.background = glassBackground(button.text == "STOP", pressed = true)
                    view.scaleX = InteractionSpec.pressFeedbackScale()
                    view.scaleY = InteractionSpec.pressFeedbackScale()
                    onDown()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.background = glassBackground(button.text == "STOP", pressed = false)
                    view.scaleX = 1f
                    view.scaleY = 1f
                    dispatchWithLatency(onStop)
                    true
                }
                else -> true
            }
        }
    }

    private fun dispatchWithLatency(action: () -> Unit) {
        val startedAt = SystemClock.elapsedRealtime()
        action()
        latencyText.text = "CTRL ${SystemClock.elapsedRealtime() - startedAt} ms"
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
}

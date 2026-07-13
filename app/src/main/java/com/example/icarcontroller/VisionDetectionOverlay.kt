package com.example.icarcontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

class VisionDetectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var palette = ParkingThemeSpec.palette(ParkingThemeMode.LIGHT)
    private var snapshot = VisionPerceptionSnapshot.empty()
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        setWillNotDraw(false)
        contentDescription = "视觉检测框覆盖层"
    }

    fun setPalette(value: ParkingPalette) {
        palette = value
        invalidate()
    }

    fun render(snapshot: VisionPerceptionSnapshot) {
        this.snapshot = snapshot
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (snapshot.detections.isEmpty()) return

        val width = width.toFloat()
        val height = height.toFloat()
        snapshot.detections.forEach { detection ->
            val rect = RectF(
                detection.left * width,
                detection.top * height,
                detection.right * width,
                detection.bottom * height
            )
            val strokeColor = when (detection.severity) {
                VisionDetectionSeverity.WARNING -> color(palette.danger)
                VisionDetectionSeverity.INFO -> color(palette.accent)
                VisionDetectionSeverity.NORMAL -> color(palette.accent)
            }
            boxPaint.color = strokeColor
            canvas.drawRect(rect, boxPaint)

            val label = "%s %.0f%%".format(detection.label, detection.confidence * 100f)
            val textWidth = labelPaint.measureText(label) + dp(10f)
            val labelRect = RectF(rect.left, rect.top - dp(18f), rect.left + textWidth, rect.top)
            fillPaint.color = withAlpha(strokeColor, 210)
            canvas.drawRect(labelRect, fillPaint)
            labelPaint.color = Color.WHITE
            canvas.drawText(label, rect.left + dp(5f), rect.top - dp(5f), labelPaint)
        }
    }

    private fun withAlpha(value: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(value), Color.green(value), Color.blue(value))

    private fun color(value: String): Int = Color.parseColor(value)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

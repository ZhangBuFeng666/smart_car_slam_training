package com.example.icarcontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class VisionDetectionOverlayView(context: Context) : View(context) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private var detections: List<VisionDetection> = emptyList()

    fun setDetections(items: List<VisionDetection>) {
        detections = items
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        detections.forEach { detection ->
            val color = classColor(detection.classId)
            val rect = VisionOverlaySpec.mapBox(
                detection.box,
                width,
                height,
                SOURCE_WIDTH,
                SOURCE_HEIGHT
            )
            boxPaint.color = color
            canvas.drawRect(RectF(rect.left, rect.top, rect.right, rect.bottom), boxPaint)

            val label = "${VisionUiSpec.detectionLabel(detection)} ${VisionUiSpec.detectionConfidence(detection)}"
            val labelWidth = labelPaint.measureText(label) + dp(14f)
            val labelTop = (rect.top - dp(28f)).coerceAtLeast(0f)
            val labelRight = (rect.left + labelWidth).coerceAtMost(width.toFloat())
            labelBackgroundPaint.color = Color.argb(220, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRoundRect(
                RectF(rect.left, labelTop, labelRight, labelTop + dp(26f)),
                dp(5f),
                dp(5f),
                labelBackgroundPaint
            )
            canvas.drawText(label, rect.left + dp(7f), labelTop + dp(18f), labelPaint)
        }
    }

    private fun classColor(classId: Int): Int = COLORS[classId.mod(COLORS.size)]

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val SOURCE_WIDTH = 640
        const val SOURCE_HEIGHT = 480
        val COLORS = intArrayOf(
            Color.rgb(98, 185, 232),
            Color.rgb(68, 205, 143),
            Color.rgb(230, 80, 88),
            Color.rgb(222, 170, 70),
            Color.rgb(82, 160, 235),
            Color.rgb(194, 130, 224),
            Color.rgb(80, 205, 213),
            Color.rgb(240, 120, 72),
            Color.rgb(220, 70, 70)
        )
    }
}

package com.example.icarcontroller

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class ParkingVisionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var canvasStyle = ParkingCanvasStyle.from(ParkingThemeSpec.palette(ParkingThemeMode.DARK))
    private val backgroundPaint = paint(color(canvasStyle.visionSky), Paint.Style.FILL)
    private val floorPaint = paint(color(canvasStyle.visionRoad), Paint.Style.FILL)
    private val lanePaint = paint(color(canvasStyle.visionLane), Paint.Style.STROKE, 2f)
    private val vehiclePaint = paint(color(canvasStyle.vehicle), Paint.Style.FILL)
    private val blueVehiclePaint = paint(color(canvasStyle.blueVehicle), Paint.Style.FILL)
    private val detectionPaint = paint(color(canvasStyle.detection), Paint.Style.STROKE, 2f)
    private val warningPaint = paint(color(canvasStyle.warning), Paint.Style.STROKE, 2f)
    private val scanPaint = paint(color(canvasStyle.scan), Paint.Style.STROKE, 1.5f)
    private val labelBackground = paint(color(canvasStyle.visionLabelBackground), Paint.Style.FILL)
    private val labelPaint = paint(color(canvasStyle.visionLabelText), Paint.Style.FILL).apply {
        textSize = dp(9f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private var scanProgress = 0f
    private var hostActive = true
    private val scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            scanProgress = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        contentDescription = "停车场视觉识别预览，包含车位、车辆颜色和禁停区域检测框"
    }

    fun setPalette(palette: ParkingPalette) {
        setCanvasStyle(ParkingCanvasStyle.from(palette))
    }

    fun setCanvasStyle(style: ParkingCanvasStyle) {
        canvasStyle = style
        backgroundPaint.color = color(style.visionSky)
        floorPaint.color = color(style.visionRoad)
        lanePaint.color = color(style.visionLane)
        vehiclePaint.color = color(style.vehicle)
        blueVehiclePaint.color = color(style.blueVehicle)
        detectionPaint.color = color(style.detection)
        warningPaint.color = color(style.warning)
        scanPaint.color = color(style.scan)
        labelBackground.color = color(style.visionLabelBackground)
        labelPaint.color = color(style.visionLabelText)
        invalidate()
    }

    fun setActive(active: Boolean) {
        hostActive = active
        if (active && isAttachedToWindow) {
            if (!scanAnimator.isStarted) scanAnimator.start()
        } else {
            scanAnimator.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (hostActive && !scanAnimator.isStarted) scanAnimator.start()
    }

    override fun onDetachedFromWindow() {
        scanAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        canvas.drawRoundRect(RectF(0f, 0f, width, height), dp(8f), dp(8f), backgroundPaint)

        val floor = Path().apply {
            moveTo(width * 0.12f, height)
            lineTo(width * 0.32f, height * 0.32f)
            lineTo(width * 0.68f, height * 0.32f)
            lineTo(width * 0.9f, height)
            close()
        }
        canvas.drawPath(floor, floorPaint)
        canvas.drawLine(width * 0.49f, height, width * 0.5f, height * 0.34f, lanePaint)
        canvas.drawLine(width * 0.22f, height, width * 0.38f, height * 0.34f, lanePaint)
        canvas.drawLine(width * 0.78f, height, width * 0.62f, height * 0.34f, lanePaint)

        val firstCar = RectF(width * 0.18f, height * 0.48f, width * 0.42f, height * 0.77f)
        val secondCar = RectF(width * 0.59f, height * 0.42f, width * 0.78f, height * 0.66f)
        canvas.drawRoundRect(firstCar, dp(7f), dp(7f), blueVehiclePaint)
        canvas.drawRoundRect(secondCar, dp(7f), dp(7f), vehiclePaint)
        drawDetection(canvas, firstCar, "BLUE CAR  96%", detectionPaint)
        drawDetection(canvas, secondCar, "B-07 OCCUPIED", detectionPaint)

        val warningZone = RectF(width * 0.69f, height * 0.72f, width * 0.91f, height * 0.91f)
        canvas.drawRect(warningZone, warningPaint)
        drawLabel(canvas, warningZone.left, warningZone.top, "NO PARKING", true)

        val scanY = height * (0.24f + scanProgress * 0.68f)
        canvas.drawLine(width * 0.08f, scanY, width * 0.92f, scanY, scanPaint)
        canvas.drawText("CAM 01  |  B2 EAST", dp(12f), dp(22f), labelPaint)
        canvas.drawText("LOCAL PREVIEW", width - dp(98f), dp(22f), labelPaint)
    }

    private fun drawDetection(canvas: Canvas, rect: RectF, label: String, paint: Paint) {
        canvas.drawRect(rect, paint)
        drawLabel(canvas, rect.left, rect.top, label, false)
    }

    private fun drawLabel(canvas: Canvas, left: Float, top: Float, label: String, warning: Boolean) {
        val width = labelPaint.measureText(label) + dp(12f)
        val labelRect = RectF(left, top - dp(21f), left + width, top)
        labelBackground.color = color(
            if (warning) canvasStyle.warningLabelBackground else canvasStyle.detectionLabelBackground
        )
        canvas.drawRect(labelRect, labelBackground)
        val previousTextColor = labelPaint.color
        if (warning) labelPaint.color = color(canvasStyle.warningLabelText)
        canvas.drawText(label, left + dp(6f), top - dp(6f), labelPaint)
        labelPaint.color = previousTextColor
    }

    private fun paint(color: Int, style: Paint.Style, strokeWidth: Float = 0f): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.style = style
            this.strokeWidth = dp(strokeWidth)
        }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun color(value: String): Int = Color.parseColor(value)
}

package com.example.icarcontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class ParkingMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var canvasStyle = ParkingCanvasStyle.from(ParkingThemeSpec.palette(ParkingThemeMode.DARK))
    private val backgroundPaint = paint(color(canvasStyle.mapBackground), Paint.Style.FILL)
    private val gridPaint = paint(color(canvasStyle.grid), Paint.Style.STROKE, 1f)
    private val lanePaint = paint(color(canvasStyle.mapLane), Paint.Style.STROKE, 2f)
    private val slotPaint = paint(color(canvasStyle.parkingSlot), Paint.Style.STROKE, 1.4f)
    private val occupiedPaint = paint(color(canvasStyle.occupiedSlot), Paint.Style.FILL)
    private val warningPaint = paint(color(canvasStyle.mapWarning), Paint.Style.FILL)
    private val routePaint = paint(color(canvasStyle.route), Paint.Style.STROKE, 4f).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routeHaloPaint = paint(color(canvasStyle.routeHalo), Paint.Style.STROKE, 12f).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = paint(color(canvasStyle.mapText), Paint.Style.FILL, 1f).apply {
        textSize = dp(11f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val mutedTextPaint = paint(color(canvasStyle.mapMutedText), Paint.Style.FILL, 1f).apply {
        textSize = dp(9f)
    }
    private val checkpointPaint = paint(color(canvasStyle.checkpoint), Paint.Style.FILL)
    private val checkpointStrokePaint = paint(color(canvasStyle.checkpointStroke), Paint.Style.STROKE, 2f)
    private val robotPaint = paint(color(canvasStyle.robot), Paint.Style.FILL)
    private val checkpoints = listOf(
        Checkpoint("A", 0.18f, 0.28f),
        Checkpoint("B", 0.48f, 0.24f),
        Checkpoint("C", 0.80f, 0.32f),
        Checkpoint("D", 0.72f, 0.72f),
        Checkpoint("E", 0.30f, 0.76f)
    )
    private var selectedIndex = 0
    private var checkpointListener: ((String) -> Unit)? = null

    init {
        contentDescription = "B2 停车场巡逻路线图，可点选检查点"
        isClickable = true
    }

    fun setOnCheckpointSelectedListener(listener: (String) -> Unit) {
        checkpointListener = listener
    }

    fun setPalette(palette: ParkingPalette) {
        setCanvasStyle(ParkingCanvasStyle.from(palette))
    }

    fun setCanvasStyle(style: ParkingCanvasStyle) {
        canvasStyle = style
        backgroundPaint.color = color(style.mapBackground)
        gridPaint.color = color(style.grid)
        lanePaint.color = color(style.mapLane)
        slotPaint.color = color(style.parkingSlot)
        occupiedPaint.color = color(style.occupiedSlot)
        warningPaint.color = color(style.mapWarning)
        routePaint.color = color(style.route)
        routeHaloPaint.color = color(style.routeHalo)
        textPaint.color = color(style.mapText)
        mutedTextPaint.color = color(style.mapMutedText)
        checkpointPaint.color = color(style.checkpoint)
        checkpointStrokePaint.color = color(style.checkpointStroke)
        robotPaint.color = color(style.robot)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        val inset = dp(14f)
        canvas.drawRoundRect(RectF(0f, 0f, width, height), dp(8f), dp(8f), backgroundPaint)

        for (index in 1..5) {
            val y = inset + (height - inset * 2) * index / 6f
            canvas.drawLine(inset, y, width - inset, y, gridPaint)
        }
        for (index in 1..7) {
            val x = inset + (width - inset * 2) * index / 8f
            canvas.drawLine(x, inset, x, height - inset, gridPaint)
        }

        val lane = RectF(width * 0.13f, height * 0.17f, width * 0.87f, height * 0.83f)
        canvas.drawRoundRect(lane, dp(26f), dp(26f), lanePaint)
        lanePaint.pathEffect = DashPathEffect(floatArrayOf(dp(10f), dp(8f)), 0f)
        canvas.drawRoundRect(RectF(width * 0.23f, height * 0.31f, width * 0.77f, height * 0.69f), dp(18f), dp(18f), lanePaint)
        lanePaint.pathEffect = null

        drawParkingRow(canvas, width * 0.18f, height * 0.08f, width * 0.68f, true)
        drawParkingRow(canvas, width * 0.18f, height * 0.84f, width * 0.68f, false)

        val route = Path().apply {
            moveTo(width * checkpoints[0].x, height * checkpoints[0].y)
            checkpoints.drop(1).forEach { lineTo(width * it.x, height * it.y) }
            close()
        }
        canvas.drawPath(route, routeHaloPaint)
        canvas.drawPath(route, routePaint)

        checkpoints.forEachIndexed { index, checkpoint ->
            val x = width * checkpoint.x
            val y = height * checkpoint.y
            val radius = if (index == selectedIndex) dp(15f) else dp(12f)
            canvas.drawCircle(x, y, radius, checkpointPaint)
            canvas.drawCircle(x, y, radius, checkpointStrokePaint)
            val labelWidth = textPaint.measureText(checkpoint.label)
            canvas.drawText(checkpoint.label, x - labelWidth / 2f, y + dp(4f), textPaint)
        }

        val robot = checkpoints[selectedIndex]
        val robotX = width * robot.x
        val robotY = height * robot.y - dp(24f)
        val marker = Path().apply {
            moveTo(robotX, robotY - dp(8f))
            lineTo(robotX - dp(7f), robotY + dp(7f))
            lineTo(robotX + dp(7f), robotY + dp(7f))
            close()
        }
        canvas.drawPath(marker, robotPaint)
        canvas.drawText("B2 PATROL ROUTE", inset, height - dp(12f), mutedTextPaint)
    }

    private fun drawParkingRow(canvas: Canvas, startX: Float, top: Float, rowWidth: Float, upper: Boolean) {
        val slotWidth = rowWidth / 6f
        val slotHeight = dp(27f)
        repeat(6) { index ->
            val left = startX + index * slotWidth
            val rect = RectF(left + dp(2f), top, left + slotWidth - dp(2f), top + slotHeight)
            if ((upper && index == 4) || (!upper && index == 1)) {
                canvas.drawRect(rect, if (upper) warningPaint else occupiedPaint)
            }
            canvas.drawRect(rect, slotPaint)
            canvas.drawText(
                if (upper) "A-${index + 1}" else "B-${index + 1}",
                rect.left + dp(4f),
                rect.bottom - dp(7f),
                mutedTextPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val nearest = checkpoints.indices.minByOrNull { index ->
            val checkpoint = checkpoints[index]
            hypot(event.x - width * checkpoint.x, event.y - height * checkpoint.y)
        } ?: return true
        val checkpoint = checkpoints[nearest]
        if (hypot(event.x - width * checkpoint.x, event.y - height * checkpoint.y) <= dp(46f)) {
            selectedIndex = nearest
            invalidate()
            checkpointListener?.invoke(checkpoint.label)
            contentDescription = "B2 停车场巡逻路线图，已选择检查点 ${checkpoint.label}"
            super.performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        selectedIndex = (selectedIndex + 1) % checkpoints.size
        val checkpoint = checkpoints[selectedIndex]
        checkpointListener?.invoke(checkpoint.label)
        contentDescription = "B2 停车场巡逻路线图，已选择检查点 ${checkpoint.label}"
        invalidate()
        return super.performClick()
    }

    private fun paint(color: Int, style: Paint.Style, strokeWidth: Float = 0f): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.style = style
            this.strokeWidth = dp(strokeWidth)
        }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun color(value: String): Int = Color.parseColor(value)

    private data class Checkpoint(val label: String, val x: Float, val y: Float)
}

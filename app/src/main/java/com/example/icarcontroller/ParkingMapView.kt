package com.example.icarcontroller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.floor
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
    private val waypointRoutePaint = paint(color(canvasStyle.checkpoint), Paint.Style.STROKE, 3f).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(5f)), 0f)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var liveMap: NavigationMapData? = null
    private var liveMapBitmap: Bitmap? = null
    private var livePose: NavigationPose? = null
    private var liveGoal: NavigationPose? = null
    private var livePath: List<NavigationPoint> = emptyList()
    private val selectedWaypoints = mutableListOf<NavigationPoint>()
    private enum class MapTouchMode { NONE, START, WAYPOINTS }

    private var mapTouchMode = MapTouchMode.NONE
    private var routeStartPose: NavigationPose? = null
    private var mapZoom = 1f
    private var mapPanX = 0f
    private var mapPanY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchMoved = false
    private var scaleGestureActive = false
    private val checkpoints = listOf(
        Checkpoint("A", 0.18f, 0.28f),
        Checkpoint("B", 0.48f, 0.24f),
        Checkpoint("C", 0.80f, 0.32f),
        Checkpoint("D", 0.72f, 0.72f),
        Checkpoint("E", 0.30f, 0.76f)
    )
    private var selectedIndex = 0
    private var checkpointListener: ((String) -> Unit)? = null
    private var waypointListener: ((NavigationPoint?, String?) -> Unit)? = null
    private var routeStartListener: ((NavigationPose?, String?) -> Unit)? = null
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaleGestureActive = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldZoom = mapZoom
                val newZoom = (oldZoom * detector.scaleFactor).coerceIn(1f, 5f)
                if (newZoom == oldZoom) return true
                val ratio = newZoom / oldZoom
                mapPanX = detector.focusX - width / 2f +
                    (mapPanX - (detector.focusX - width / 2f)) * ratio
                mapPanY = detector.focusY - height / 2f +
                    (mapPanY - (detector.focusY - height / 2f)) * ratio
                mapZoom = newZoom
                clampMapPan()
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaleGestureActive = false
            }
        })

    init {
        contentDescription = "B2 停车场巡逻路线图，可点选检查点"
        isClickable = true
    }

    fun setOnCheckpointSelectedListener(listener: (String) -> Unit) {
        checkpointListener = listener
    }

    fun setOnWaypointSelectedListener(listener: (NavigationPoint?, String?) -> Unit) {
        waypointListener = listener
    }

    fun setOnRouteStartSelectedListener(listener: (NavigationPose?, String?) -> Unit) {
        routeStartListener = listener
    }

    fun setStartSelectionMode(enabled: Boolean) {
        mapTouchMode = if (enabled) MapTouchMode.START else MapTouchMode.NONE
        contentDescription = if (enabled) "ROS 实时地图，触摸空闲区域设置路线起点"
        else "ROS 实时地图，包含小车位姿、目标点和规划路径"
        invalidate()
    }

    fun setWaypointEditMode(enabled: Boolean) {
        mapTouchMode = if (enabled) MapTouchMode.WAYPOINTS else MapTouchMode.NONE
        contentDescription = if (enabled) "ROS 实时地图，触摸空闲区域依次添加巡逻点"
        else "ROS 实时地图，包含小车位姿、目标点和规划路径"
        invalidate()
    }

    fun isWaypointEditMode(): Boolean = mapTouchMode == MapTouchMode.WAYPOINTS

    fun isStartSelectionMode(): Boolean = mapTouchMode == MapTouchMode.START

    fun routeWaypoints(): List<NavigationPoint> = selectedWaypoints.toList()

    fun routeStartPose(): NavigationPose? = routeStartPose

    fun undoWaypoint(): Int {
        if (selectedWaypoints.isNotEmpty()) selectedWaypoints.removeAt(selectedWaypoints.lastIndex)
        invalidate()
        return selectedWaypoints.size
    }

    fun clearWaypoints(): Int {
        selectedWaypoints.clear()
        routeStartPose = null
        invalidate()
        return 0
    }

    fun appendReturnToStart(): Boolean {
        val start = routeStartPose ?: return false
        selectedWaypoints += NavigationPoint(start.x, start.y)
        invalidate()
        waypointListener?.invoke(selectedWaypoints.last(), null)
        return true
    }

    fun zoomIn() = setMapZoom(mapZoom * 1.35f)

    fun zoomOut() = setMapZoom(mapZoom / 1.35f)

    fun resetMapViewport() {
        mapZoom = 1f
        mapPanX = 0f
        mapPanY = 0f
        invalidate()
    }

    private fun setMapZoom(value: Float) {
        mapZoom = value.coerceIn(1f, 5f)
        clampMapPan()
        invalidate()
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
        waypointRoutePaint.color = color(style.checkpoint)
        robotPaint.color = color(style.robot)
        liveMap?.let { liveMapBitmap = buildMapBitmap(it) }
        invalidate()
    }

    fun applyNavigationSnapshot(snapshot: NavigationSnapshot) {
        if (snapshot.mapReset) {
            liveMapBitmap?.recycle()
            liveMapBitmap = null
            liveMap = null
            livePose = null
            liveGoal = null
            livePath = emptyList()
            selectedWaypoints.clear()
            routeStartPose = null
            resetMapViewport()
        }
        snapshot.map?.let { map ->
            liveMap = map
            liveMapBitmap?.recycle()
            liveMapBitmap = buildMapBitmap(map)
        }
        livePose = snapshot.pose
        liveGoal = snapshot.goal
        livePath = snapshot.path
        if (liveMap != null) {
            contentDescription = "ROS 实时地图，包含小车位姿、目标点和规划路径"
        }
        invalidate()
    }

    fun hasLiveMap(): Boolean = liveMap != null

    fun hasLivePose(): Boolean = livePose != null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        val inset = dp(14f)
        canvas.drawRoundRect(RectF(0f, 0f, width, height), dp(8f), dp(8f), backgroundPaint)
        val map = liveMap
        val bitmap = liveMapBitmap
        if (map != null && bitmap != null) {
            drawLiveMap(canvas, map, bitmap, height, inset)
            return
        }

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

    private fun drawLiveMap(
        canvas: Canvas,
        map: NavigationMapData,
        bitmap: Bitmap,
        height: Float,
        inset: Float
    ) {
        val destination = liveMapDestination(map)
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)

        if (routeStartPose != null || selectedWaypoints.isNotEmpty()) {
            val selectedRoute = Path()
            routeStartPose?.let { start ->
                val screen = worldToScreen(start.x, start.y, map, destination)
                selectedRoute.moveTo(screen.first, screen.second)
            }
            selectedWaypoints.forEachIndexed { index, point ->
                val screen = worldToScreen(point.x, point.y, map, destination)
                if (index == 0 && routeStartPose == null) selectedRoute.moveTo(screen.first, screen.second)
                else selectedRoute.lineTo(screen.first, screen.second)
            }
            if (selectedWaypoints.isNotEmpty()) canvas.drawPath(selectedRoute, waypointRoutePaint)
            routeStartPose?.let { start ->
                val screen = worldToScreen(start.x, start.y, map, destination)
                canvas.drawCircle(screen.first, screen.second, dp(12f), robotPaint)
                canvas.drawCircle(screen.first, screen.second, dp(12f), checkpointStrokePaint)
                canvas.drawText(
                    "S",
                    screen.first - textPaint.measureText("S") / 2f,
                    screen.second + dp(4f),
                    textPaint
                )
            }
            selectedWaypoints.forEachIndexed { index, point ->
                val screen = worldToScreen(point.x, point.y, map, destination)
                canvas.drawCircle(screen.first, screen.second, dp(11f), checkpointPaint)
                canvas.drawCircle(screen.first, screen.second, dp(11f), checkpointStrokePaint)
                val label = (index + 1).toString()
                canvas.drawText(
                    label,
                    screen.first - textPaint.measureText(label) / 2f,
                    screen.second + dp(4f),
                    textPaint
                )
            }
        }

        if (livePath.size >= 2) {
            val path = Path()
            livePath.forEachIndexed { index, point ->
                val screen = worldToScreen(point.x, point.y, map, destination)
                if (index == 0) path.moveTo(screen.first, screen.second)
                else path.lineTo(screen.first, screen.second)
            }
            canvas.drawPath(path, routeHaloPaint)
            canvas.drawPath(path, routePaint)
        }

        liveGoal?.let { goal ->
            val screen = worldToScreen(goal.x, goal.y, map, destination)
            canvas.drawCircle(screen.first, screen.second, dp(9f), checkpointPaint)
            canvas.drawCircle(screen.first, screen.second, dp(9f), checkpointStrokePaint)
        }

        livePose?.let { pose ->
            val screen = worldToScreen(pose.x, pose.y, map, destination)
            val angle = (pose.yaw - map.origin.yaw).toFloat()
            val marker = Path().apply {
                moveTo(0f, -dp(11f))
                lineTo(-dp(8f), dp(8f))
                lineTo(dp(8f), dp(8f))
                close()
            }
            canvas.save()
            canvas.translate(screen.first, screen.second)
            canvas.rotate(-Math.toDegrees(angle.toDouble()).toFloat() + 90f)
            canvas.drawPath(marker, robotPaint)
            canvas.restore()
        }
        canvas.drawText("ROS MAP · LIVE", inset, height - dp(10f), mutedTextPaint)
    }

    private fun worldToScreen(
        x: Double,
        y: Double,
        map: NavigationMapData,
        destination: RectF
    ): Pair<Float, Float> {
        val dx = x - map.origin.x
        val dy = y - map.origin.y
        val cosYaw = kotlin.math.cos(map.origin.yaw)
        val sinYaw = kotlin.math.sin(map.origin.yaw)
        val gridX = (cosYaw * dx + sinYaw * dy) / map.resolution
        val gridY = (-sinYaw * dx + cosYaw * dy) / map.resolution
        val screenX = destination.left + (gridX / map.width * destination.width()).toFloat()
        val screenY = destination.bottom - (gridY / map.height * destination.height()).toFloat()
        return screenX to screenY
    }

    private fun screenToWorld(
        screenX: Float,
        screenY: Float,
        map: NavigationMapData,
        destination: RectF
    ): NavigationPoint {
        val gridX = (screenX - destination.left) / destination.width() * map.width
        val gridY = (destination.bottom - screenY) / destination.height() * map.height
        val localX = gridX * map.resolution
        val localY = gridY * map.resolution
        val cosYaw = kotlin.math.cos(map.origin.yaw)
        val sinYaw = kotlin.math.sin(map.origin.yaw)
        return NavigationPoint(
            map.origin.x + cosYaw * localX - sinYaw * localY,
            map.origin.y + sinYaw * localX + cosYaw * localY
        )
    }

    private fun liveMapDestination(map: NavigationMapData): RectF {
        val inset = dp(14f)
        val labelSpace = dp(22f)
        val availableWidth = width - inset * 2f
        val availableHeight = height - inset * 2f - labelSpace
        val scale = minOf(availableWidth / map.width, availableHeight / map.height)
        val drawnWidth = map.width * scale * mapZoom
        val drawnHeight = map.height * scale * mapZoom
        val left = (width - drawnWidth) / 2f + mapPanX
        val top = inset + (availableHeight - drawnHeight) / 2f + mapPanY
        return RectF(left, top, left + drawnWidth, top + drawnHeight)
    }

    private fun clampMapPan() {
        if (mapZoom <= 1f) {
            mapPanX = 0f
            mapPanY = 0f
            return
        }
        val maxPanX = width * (mapZoom - 1f) / 2f
        val maxPanY = height * (mapZoom - 1f) / 2f
        mapPanX = mapPanX.coerceIn(-maxPanX, maxPanX)
        mapPanY = mapPanY.coerceIn(-maxPanY, maxPanY)
    }

    private fun buildMapBitmap(map: NavigationMapData): Bitmap {
        val pixels = IntArray(map.cells.size)
        val unknownColor = color(canvasStyle.grid)
        val freeColor = color(canvasStyle.mapBackground)
        val occupiedColor = color(canvasStyle.mapText)
        for (sourceY in 0 until map.height) {
            val targetY = map.height - 1 - sourceY
            for (x in 0 until map.width) {
                val value = map.cells[sourceY * map.width + x]
                pixels[targetY * map.width + x] = when {
                    value < 0 -> unknownColor
                    value >= 50 -> occupiedColor
                    else -> freeColor
                }
            }
        }
        return Bitmap.createBitmap(pixels, map.width, map.height, Bitmap.Config.ARGB_8888)
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
        val map = liveMap
        if (map != null) {
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    touchMoved = false
                }
                MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (mapZoom > 1f) {
                        mapPanX += dx
                        mapPanY += dy
                        clampMapPan()
                        invalidate()
                    }
                    if (hypot(dx, dy) > dp(2f)) touchMoved = true
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_POINTER_DOWN -> touchMoved = true
            }
            val selecting = mapTouchMode != MapTouchMode.NONE
            if (event.actionMasked == MotionEvent.ACTION_UP && selecting && !touchMoved && !scaleGestureActive) {
                val destination = liveMapDestination(map)
                if (!destination.contains(event.x, event.y)) {
                    notifyMapSelectionError("请点击地图范围内的空闲区域")
                    return true
                }
                val gridX = floor((event.x - destination.left) / destination.width() * map.width).toInt()
                val gridY = floor((destination.bottom - event.y) / destination.height() * map.height).toInt()
                val cellIndex = gridY * map.width + gridX
                if (gridX !in 0 until map.width || gridY !in 0 until map.height ||
                    cellIndex !in map.cells.indices || map.cells[cellIndex] < 0 || map.cells[cellIndex] >= 50
                ) {
                    notifyMapSelectionError("该位置是障碍物或未知区域")
                    return true
                }
                val point = screenToWorld(event.x, event.y, map, destination)
                if (mapTouchMode == MapTouchMode.START) {
                    routeStartPose = NavigationPose(point.x, point.y, 0.0)
                    selectedWaypoints.clear()
                    mapTouchMode = MapTouchMode.NONE
                    routeStartListener?.invoke(routeStartPose, null)
                } else {
                    selectedWaypoints += point
                    waypointListener?.invoke(point, null)
                }
                invalidate()
                super.performClick()
            } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                super.performClick()
            }
            return true
        }
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

    private fun notifyMapSelectionError(message: String) {
        if (mapTouchMode == MapTouchMode.START) routeStartListener?.invoke(null, message)
        else waypointListener?.invoke(null, message)
    }

    override fun performClick(): Boolean {
        if (liveMap != null) return super.performClick()
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

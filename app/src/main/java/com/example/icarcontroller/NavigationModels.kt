package com.example.icarcontroller

import org.json.JSONObject

data class NavigationPoint(
    val x: Double,
    val y: Double,
    val yaw: Double = 0.0,
    val hasExplicitYaw: Boolean = false
)

data class NavigationPose(val x: Double, val y: Double, val yaw: Double)

data class NavigationWaypointStatus(
    val state: String,
    val total: Int,
    val currentIndex: Int,
    val missed: List<Int>,
    val message: String,
    val phase: String = "",
    val targetYaw: Double? = null,
    val actualYaw: Double? = null,
    val yawErrorDegrees: Double? = null,
    val retryCount: Int = 0
)

data class NavigationMapData(
    val generation: Long,
    val width: Int,
    val height: Int,
    val resolution: Double,
    val origin: NavigationPose,
    val cells: IntArray
)

data class NavigationSnapshot(
    val mapGeneration: Long,
    val mapReset: Boolean,
    val map: NavigationMapData?,
    val pose: NavigationPose?,
    val goal: NavigationPose?,
    val path: List<NavigationPoint>,
    val waypoints: NavigationWaypointStatus
)

object NavigationSnapshotParser {
    @JvmStatic
    fun parse(raw: String): NavigationSnapshot = parse(JSONObject(raw))

    @JvmStatic
    fun parse(root: JSONObject): NavigationSnapshot {
        val mapObject = root.optJSONObject("map")
        return NavigationSnapshot(
            mapGeneration = root.optLong("map_generation", 0L),
            mapReset = root.optBoolean("map_reset", false),
            map = mapObject?.let(::parseMap),
            pose = root.optJSONObject("pose")?.let(::parsePose),
            goal = root.optJSONObject("goal")?.let(::parsePose),
            path = root.optJSONArray("path")?.let { array ->
                (0 until array.length()).map { index ->
                    val point = array.getJSONObject(index)
                    NavigationPoint(point.getDouble("x"), point.getDouble("y"))
                }
            }.orEmpty(),
            waypoints = root.optJSONObject("waypoints")?.let { status ->
                NavigationWaypointStatus(
                    state = status.optString("state", "idle"),
                    total = status.optInt("total", 0),
                    currentIndex = status.optInt("current_index", -1),
                    missed = status.optJSONArray("missed")?.let { array ->
                        (0 until array.length()).map(array::getInt)
                    }.orEmpty(),
                    message = status.optString("message", ""),
                    phase = status.optString("phase", ""),
                    targetYaw = status.optionalDouble("target_yaw"),
                    actualYaw = status.optionalDouble("actual_yaw"),
                    yawErrorDegrees = status.optionalDouble("yaw_error_deg"),
                    retryCount = status.optInt("retry_count", 0)
                )
            } ?: NavigationWaypointStatus("idle", 0, -1, emptyList(), "")
        )
    }

    private fun parseMap(obj: JSONObject): NavigationMapData {
        val width = obj.getInt("width")
        val height = obj.getInt("height")
        require(width > 0 && height > 0) { "map dimensions must be positive" }
        val expectedSize = width * height
        val cells = IntArray(expectedSize)
        val encoded = obj.getJSONArray("data_rle")
        require(encoded.length() % 2 == 0) { "map RLE must contain value/count pairs" }
        var cursor = 0
        var index = 0
        while (index < encoded.length()) {
            val value = encoded.getInt(index)
            val count = encoded.getInt(index + 1)
            require(count > 0 && cursor + count <= expectedSize) { "invalid map RLE count" }
            cells.fill(value, cursor, cursor + count)
            cursor += count
            index += 2
        }
        require(cursor == expectedSize) { "map RLE size does not match dimensions" }
        return NavigationMapData(
            generation = obj.getLong("generation"),
            width = width,
            height = height,
            resolution = obj.getDouble("resolution"),
            origin = parsePose(obj.getJSONObject("origin")),
            cells = cells
        )
    }

    private fun parsePose(obj: JSONObject): NavigationPose = NavigationPose(
        x = obj.getDouble("x"),
        y = obj.getDouble("y"),
        yaw = obj.optDouble("yaw", 0.0)
    )

    private fun JSONObject.optionalDouble(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name).takeIf(Double::isFinite) else null
}

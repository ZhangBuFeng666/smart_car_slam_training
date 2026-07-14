package com.example.icarcontroller

import org.json.JSONObject

class VisionApi(host: String, private val port: Int = 8200) {
    private val normalizedHost = host
        .trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .trimEnd('/')

    fun healthUrl(): String = "$baseUrl/health"

    fun detectionsUrl(): String = "$baseUrl/vision/detections"

    fun streamUrl(): String = "$baseUrl/vision/stream"

    private val baseUrl: String
        get() = "http://$normalizedHost:$port"
}

data class VisionModelState(
    val ready: Boolean = false,
    val device: String? = null,
    val fp16: Boolean = false
)

data class VisionSummary(
    val total: Int = 0,
    val parkingSlots: Int = 0,
    val cars: Int = 0,
    val signs: Int = 0,
    val obstacles: Int = 0
)

data class VisionBox(
    val left: Double = 0.0,
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0
)

data class VisionDetection(
    val classId: Int,
    val label: String,
    val confidence: Double,
    val box: VisionBox,
    val direction: String? = null,
    val directionConfidence: Double? = null,
    val stableDirection: String? = null
)

data class VisionSnapshot(
    val state: String,
    val error: String?,
    val model: VisionModelState,
    val inferenceMs: Double,
    val sourceFps: Double,
    val updatedAt: Double?,
    val summary: VisionSummary,
    val detections: List<VisionDetection>
)

object VisionSnapshotParser {
    @JvmStatic
    fun parse(json: String): VisionSnapshot {
        val root = JSONObject(json)
        val model = root.optJSONObject("model") ?: JSONObject()
        val summary = root.optJSONObject("summary") ?: JSONObject()
        val rawDetections = root.optJSONArray("detections")
        val detections = buildList {
            if (rawDetections != null) {
                for (index in 0 until rawDetections.length()) {
                    val item = rawDetections.optJSONObject(index) ?: continue
                    val box = item.optJSONObject("box") ?: JSONObject()
                    add(
                        VisionDetection(
                            classId = item.optInt("class_id", -1),
                            label = item.optString("label", "unknown"),
                            confidence = item.optDouble("confidence", 0.0),
                            box = VisionBox(
                                left = box.optDouble("left", 0.0),
                                top = box.optDouble("top", 0.0),
                                right = box.optDouble("right", 0.0),
                                bottom = box.optDouble("bottom", 0.0)
                            ),
                            direction = item.optNullableString("direction"),
                            directionConfidence = if (
                                item.has("direction_confidence") && !item.isNull("direction_confidence")
                            ) item.optDouble("direction_confidence") else null,
                            stableDirection = item.optNullableString("stable_direction")
                        )
                    )
                }
            }
        }
        return VisionSnapshot(
            state = root.optString("state", "unknown"),
            error = root.optNullableString("error"),
            model = VisionModelState(
                ready = model.optBoolean("ready", false),
                device = model.optNullableString("device"),
                fp16 = model.optBoolean("fp16", false)
            ),
            inferenceMs = root.optDouble("inference_ms", 0.0),
            sourceFps = root.optDouble("source_fps", 0.0),
            updatedAt = if (root.has("updated_at") && !root.isNull("updated_at")) {
                root.optDouble("updated_at")
            } else null,
            summary = VisionSummary(
                total = summary.optInt("total", 0),
                parkingSlots = summary.optInt("parking_slots", 0),
                cars = summary.optInt("cars", 0),
                signs = summary.optInt("signs", 0),
                obstacles = summary.optInt("obstacles", 0)
            ),
            detections = detections
        )
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}

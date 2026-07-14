package com.example.icarcontroller

import org.json.JSONObject

data class ArrowTurnStatus(
    val running: Boolean,
    val phase: String?,
    val note: String?,
    val direction: String?,
    val distanceM: Double?,
    val trackId: Int?,
    val error: String? = null
)

object ArrowTurnStatusParser {
    fun parse(json: String): ArrowTurnStatus {
        val root = JSONObject(json)
        return ArrowTurnStatus(
            running = root.optBoolean("running", false),
            phase = optionalText(root, "phase"),
            note = optionalText(root, "note"),
            direction = optionalText(root, "direction"),
            distanceM = if (root.isNull("distance_m")) null else root.optDouble("distance_m"),
            trackId = if (root.isNull("track_id")) null else root.optInt("track_id"),
            error = optionalText(root, "error")
        )
    }

    private fun optionalText(root: JSONObject, key: String): String? {
        if (root.isNull(key)) return null
        val value = root.optString(key, "")
        return value.takeUnless { it.isBlank() || it == "null" }
    }
}

object ArrowTurnUiSpec {
    fun statusText(status: ArrowTurnStatus): String {
        if (!status.running && status.phase.isNullOrBlank()) {
            return status.error ?: "未运行"
        }
        val phase = when (status.phase) {
            "seek" -> "搜寻箭头"
            "advance" -> "再直行 0.5 m"
            "turn" -> "原地转向"
            "done" -> "已完成停车"
            "stopped" -> "已停止"
            else -> status.phase ?: if (status.running) "运行中" else "未运行"
        }
        val dir = when (status.direction) {
            "turn_left" -> "左转"
            "turn_right" -> "右转"
            else -> null
        }
        val dist = status.distanceM?.let { String.format("%.2f m", it) }
        return listOfNotNull(phase, dir, dist).joinToString(" · ")
    }
}

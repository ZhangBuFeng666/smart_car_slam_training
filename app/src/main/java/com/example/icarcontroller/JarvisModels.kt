package com.example.icarcontroller

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class JarvisAction {
    CHECK_STATUS,
    START_TASK,
    STOP_TASK,
    STOP_ALL,
    RECORD_EVENT,
    ASK_USER,
    GENERATE_REPORT
}

enum class JarvisMissionState {
    DRAFT,
    WAITING_CONFIRMATION,
    RUNNING,
    PAUSED,
    FAILED,
    CANCELLED,
    COMPLETED
}

enum class JarvisDecisionType(val wireValue: String) {
    CONTINUE("continue"),
    IGNORE("ignore"),
    PAUSE("pause"),
    FINISH("finish");

    companion object {
        fun fromWire(value: String): JarvisDecisionType =
            entries.firstOrNull { it.wireValue == value }
                ?: throw JarvisProtocolException("Unknown decision type: $value")
    }
}

data class JarvisMissionStep(
    val action: JarvisAction,
    val arguments: Map<String, Any?>
)

data class JarvisMissionPlan(
    val summary: String,
    val steps: List<JarvisMissionStep>,
    val completionCriteria: List<String>,
    val requiresConfirmation: Boolean
)

data class JarvisDecision(
    val decision: JarvisDecisionType,
    val eventId: String?,
    val note: String?
)

data class JarvisMission(
    val id: String,
    val state: JarvisMissionState,
    val plan: JarvisMissionPlan,
    val createdAt: String,
    val updatedAt: String,
    val pendingDecision: JarvisDecision?
)

data class JarvisTimelineEntry(
    val id: String,
    val missionId: String,
    val timestamp: String,
    val kind: String,
    val message: String,
    val metadata: Map<String, Any?>
)

data class JarvisVisionEvent(
    val missionId: String,
    val source: String,
    val eventType: String,
    val label: String,
    val confidence: Double,
    val position: String,
    val trackId: String,
    val imagePath: String,
    val timestamp: String,
    val metadata: Map<String, Any?>
)

data class JarvisReport(
    val missionId: String,
    val markdown: String,
    val createdAt: String
)

enum class JarvisControlTaskState {
    DRAFT, STARTING, RUNNING, COMPLETED, STOPPED, FAILED
}

data class JarvisControlTask(
    val id: String,
    val title: String,
    val kind: String,
    val state: JarvisControlTaskState,
    val steps: List<String>,
    val completedSteps: Int,
    val currentMessage: String,
    val currentValue: Double,
    val targetValue: Double,
    val unit: String,
    val result: String?
)

class JarvisProtocolException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

object JarvisJson {
    fun parseControlTask(obj: JSONObject): JarvisControlTask = wrap {
        JarvisControlTask(
            id = requiredString(obj, "id"),
            title = requiredString(obj, "title"),
            kind = requiredString(obj, "kind"),
            state = enumValue(requiredString(obj, "state")),
            steps = obj.getJSONArray("steps").toStringList(),
            completedSteps = obj.optInt("completed_steps", 0),
            currentMessage = requiredString(obj, "current_message"),
            currentValue = obj.optDouble("current_value", 0.0),
            targetValue = obj.optDouble("target_value", 0.0),
            unit = obj.optString("unit"),
            result = obj.optNullableString("result")
        )
    }
    @JvmStatic
    fun parseMission(json: String): JarvisMission = wrap {
        parseMission(JSONObject(json))
    }

    @JvmStatic
    fun parseTimelineEntry(json: String): JarvisTimelineEntry = wrap {
        parseTimelineEntry(JSONObject(json))
    }

    @JvmStatic
    fun parseVisionEvent(json: String): JarvisVisionEvent = wrap {
        parseVisionEvent(JSONObject(json))
    }

    @JvmStatic
    fun parseReport(json: String): JarvisReport = wrap {
        parseReport(JSONObject(json))
    }

    @JvmStatic
    fun parseReport(obj: JSONObject): JarvisReport = wrap {
        JarvisReport(
            missionId = requiredString(obj, "mission_id"),
            markdown = requiredString(obj, "markdown"),
            createdAt = requiredString(obj, "created_at")
        )
    }

    @JvmStatic
    fun parseMission(obj: JSONObject): JarvisMission = wrap {
        JarvisMission(
            id = requiredString(obj, "id"),
            state = enumValue(requiredString(obj, "state")),
            plan = parsePlan(obj.getJSONObject("plan")),
            createdAt = requiredString(obj, "created_at"),
            updatedAt = requiredString(obj, "updated_at"),
            pendingDecision = obj.optJSONObject("pending_decision")?.let { parseDecision(it) }
        )
    }

    fun parsePlan(obj: JSONObject): JarvisMissionPlan =
        JarvisMissionPlan(
            summary = requiredString(obj, "summary"),
            steps = obj.getJSONArray("steps").toObjectList { parseStep(it) },
            completionCriteria = obj.optJSONArray("completion_criteria")
                ?.toStringList()
                ?: emptyList(),
            requiresConfirmation = obj.optBoolean("requires_confirmation", false)
        )

    fun parseStep(obj: JSONObject): JarvisMissionStep =
        JarvisMissionStep(
            action = enumValue(requiredString(obj, "action")),
            arguments = obj.optJSONObject("arguments")?.toMap() ?: emptyMap()
        )

    fun parseDecision(obj: JSONObject): JarvisDecision =
        JarvisDecision(
            decision = JarvisDecisionType.fromWire(requiredString(obj, "decision")),
            eventId = obj.optNullableString("event_id"),
            note = obj.optNullableString("note")
        )

    fun parseTimelineEntry(obj: JSONObject): JarvisTimelineEntry =
        JarvisTimelineEntry(
            id = requiredString(obj, "id"),
            missionId = requiredString(obj, "mission_id"),
            timestamp = requiredString(obj, "timestamp"),
            kind = requiredString(obj, "kind"),
            message = requiredString(obj, "message"),
            metadata = obj.optJSONObject("metadata")?.toMap() ?: emptyMap()
        )

    fun parseVisionEvent(obj: JSONObject): JarvisVisionEvent {
        val confidence = obj.getDouble("confidence")
        if (confidence < 0.0 || confidence > 1.0) {
            throw JarvisProtocolException("Invalid confidence: $confidence")
        }
        return JarvisVisionEvent(
            missionId = requiredString(obj, "mission_id"),
            source = requiredString(obj, "source"),
            eventType = requiredString(obj, "event_type"),
            label = requiredString(obj, "label"),
            confidence = confidence,
            position = requiredString(obj, "position"),
            trackId = requiredString(obj, "track_id"),
            imagePath = requiredString(obj, "image_path"),
            timestamp = requiredString(obj, "timestamp"),
            metadata = obj.optJSONObject("metadata")?.toMap() ?: emptyMap()
        )
    }

    private inline fun <T> wrap(block: () -> T): T =
        try {
            block()
        } catch (ex: JarvisProtocolException) {
            throw ex
        } catch (ex: JSONException) {
            throw JarvisProtocolException("Invalid Jarvis JSON", ex)
        } catch (ex: IllegalArgumentException) {
            throw JarvisProtocolException("Invalid Jarvis value", ex)
        }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw JarvisProtocolException("Unknown enum value: $value")

    private fun requiredString(obj: JSONObject, name: String): String {
        val value = obj.getString(name)
        if (value.isBlank()) {
            throw JarvisProtocolException("Blank field: $name")
        }
        return value
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }

    private fun JSONArray.toObjectList(parser: (JSONObject) -> JarvisMissionStep): List<JarvisMissionStep> =
        (0 until length()).map { parser(getJSONObject(it)) }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        keys().forEach { key ->
            result[key] = when (val value = get(key)) {
                JSONObject.NULL -> null
                is JSONObject -> value.toMap()
                is JSONArray -> value.toList()
                else -> value
            }
        }
        return result
    }

    private fun JSONArray.toList(): List<Any?> =
        (0 until length()).map { index ->
            when (val value = get(index)) {
                JSONObject.NULL -> null
                is JSONObject -> value.toMap()
                is JSONArray -> value.toList()
                else -> value
            }
        }
}

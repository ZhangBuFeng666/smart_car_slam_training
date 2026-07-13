package com.example.icarcontroller

import org.json.JSONArray
import org.json.JSONObject

data class JarvisEncodedChatItem(val type: String, val payloadJson: String)

object JarvisChatItemCodec {
    @JvmStatic
    fun encode(item: JarvisChatItem): JarvisEncodedChatItem {
        val payload = JSONObject()
        val type = when (item) {
            is JarvisChatItem.UserMessage -> "user".also {
                payload.put("text", item.text).put("timestamp", item.timestamp)
            }
            is JarvisChatItem.AssistantMessage -> "assistant".also {
                payload.put("text", item.text).put("timestamp", item.timestamp)
            }
            is JarvisChatItem.SystemEvent -> "system".also {
                payload.put("text", item.text).put("timestamp", item.timestamp)
            }
            is JarvisChatItem.ErrorMessage -> "error".also {
                payload.put("title", item.title).put("detail", item.detail).put("timestamp", item.timestamp)
            }
            is JarvisChatItem.PlanCard -> "plan".also {
                payload.put("plan", item.plan.toJson()).put("timestamp", item.timestamp)
            }
            is JarvisChatItem.ControlTaskCard -> "control_task".also {
                payload.put("task", item.task.toJson()).put("timestamp", item.timestamp)
            }
            is JarvisChatItem.ProgressCard -> "progress".also {
                payload.put("mission", item.mission.toJson())
                    .put("timeline", JSONArray(item.timeline.map { it.toJson() }))
                    .put("timestamp", item.timestamp)
            }
            is JarvisChatItem.ReportCard -> "report".also {
                payload.put("report", item.report.toJson()).put("timestamp", item.timestamp)
            }
        }
        return JarvisEncodedChatItem(type, payload.toString())
    }

    @JvmStatic
    fun decode(encoded: JarvisEncodedChatItem): JarvisChatItem = try {
        val payload = JSONObject(encoded.payloadJson)
        val timestamp = payload.optString("timestamp")
        when (encoded.type) {
            "user" -> JarvisChatItem.UserMessage(payload.getString("text"), timestamp)
            "assistant" -> JarvisChatItem.AssistantMessage(payload.getString("text"), timestamp)
            "system" -> JarvisChatItem.SystemEvent(payload.getString("text"), timestamp)
            "error" -> JarvisChatItem.ErrorMessage(
                payload.getString("title"), payload.getString("detail"), timestamp
            )
            "plan" -> JarvisChatItem.PlanCard(
                JarvisJson.parsePlan(payload.getJSONObject("plan")), timestamp
            )
            "control_task" -> JarvisChatItem.ControlTaskCard(
                JarvisJson.parseControlTask(payload.getJSONObject("task")), timestamp
            )
            "progress" -> JarvisChatItem.ProgressCard(
                JarvisJson.parseMission(payload.getJSONObject("mission")),
                payload.getJSONArray("timeline").objects().map(JarvisJson::parseTimelineEntry),
                timestamp
            )
            "report" -> JarvisChatItem.ReportCard(
                JarvisJson.parseReport(payload.getJSONObject("report")), timestamp
            )
            else -> unsupportedItem()
        }
    } catch (_: Exception) {
        unsupportedItem()
    }

    private fun unsupportedItem(): JarvisChatItem =
        JarvisChatItem.SystemEvent("此条历史消息无法显示。", "")

    private fun JarvisMissionPlan.toJson(): JSONObject = JSONObject()
        .put("summary", summary)
        .put("steps", JSONArray(steps.map { it.toJson() }))
        .put("completion_criteria", JSONArray(completionCriteria))
        .put("requires_confirmation", requiresConfirmation)

    private fun JarvisMissionStep.toJson(): JSONObject = JSONObject()
        .put("action", action.name)
        .put("arguments", JSONObject(arguments))

    private fun JarvisControlTask.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("kind", kind)
        .put("state", state.name)
        .put("steps", JSONArray(steps))
        .put("completed_steps", completedSteps)
        .put("current_message", currentMessage)
        .put("current_value", currentValue)
        .put("target_value", targetValue)
        .put("unit", unit)
        .put("result", result ?: JSONObject.NULL)

    private fun JarvisMission.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("state", state.name)
        .put("plan", plan.toJson())
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .put("pending_decision", pendingDecision?.toJson() ?: JSONObject.NULL)

    private fun JarvisDecision.toJson(): JSONObject = JSONObject()
        .put("decision", decision.wireValue)
        .put("event_id", eventId ?: JSONObject.NULL)
        .put("note", note ?: JSONObject.NULL)

    private fun JarvisTimelineEntry.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("mission_id", missionId)
        .put("timestamp", timestamp)
        .put("kind", kind)
        .put("message", message)
        .put("metadata", JSONObject(metadata))

    private fun JarvisReport.toJson(): JSONObject = JSONObject()
        .put("mission_id", missionId)
        .put("markdown", markdown)
        .put("created_at", createdAt)

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).map { getJSONObject(it) }
}

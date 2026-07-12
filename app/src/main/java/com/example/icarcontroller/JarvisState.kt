package com.example.icarcontroller

enum class JarvisScreenMode {
    IDLE,
    CHATTING,
    PLAN_READY,
    CONFIRMING,
    RUNNING,
    DECISION_REQUIRED,
    REPORT_READY,
    ERROR
}

data class JarvisViewState(
    val mode: JarvisScreenMode,
    val missionId: String?,
    val plan: JarvisMissionPlan?,
    val timeline: List<JarvisTimelineEntry>,
    val pendingDecision: JarvisDecision?,
    val report: JarvisReport?,
    val loading: Boolean,
    val errorMessage: String?,
    val chatItems: List<JarvisChatItem>,
    val emergencyStopAvailable: Boolean
) {
    companion object {
        @JvmStatic
        fun initial(): JarvisViewState =
            JarvisViewState(
                mode = JarvisScreenMode.IDLE,
                missionId = null,
                plan = null,
                timeline = emptyList(),
                pendingDecision = null,
                report = null,
                loading = false,
                errorMessage = null,
                chatItems = emptyList(),
                emergencyStopAvailable = true
            )
    }
}

sealed class JarvisChatItem {
    data class UserMessage(val text: String, val timestamp: String) : JarvisChatItem()
    data class AssistantMessage(val text: String, val timestamp: String) : JarvisChatItem()
    data class PlanCard(val plan: JarvisMissionPlan, val timestamp: String) : JarvisChatItem()
    data class ProgressCard(
        val mission: JarvisMission,
        val timeline: List<JarvisTimelineEntry>,
        val timestamp: String
    ) : JarvisChatItem()
    data class ReportCard(val report: JarvisReport, val timestamp: String) : JarvisChatItem()
    data class ErrorMessage(val title: String, val detail: String, val timestamp: String) : JarvisChatItem()
    data class SystemEvent(val text: String, val timestamp: String) : JarvisChatItem()
}

sealed class JarvisEvent {
    object ChatStarted : JarvisEvent()
    object ConfirmationStarted : JarvisEvent()
    data class UserMessageSubmitted(val message: String) : JarvisEvent()
    data class SystemMessageAdded(val message: String) : JarvisEvent()
    data class ChatReply(val message: String) : JarvisEvent()
    data class PlanReady(val plan: JarvisMissionPlan) : JarvisEvent()
    data class MissionUpdated(
        val mission: JarvisMission,
        val timeline: List<JarvisTimelineEntry>
    ) : JarvisEvent()
    data class DecisionRequired(val decision: JarvisDecision) : JarvisEvent()
    data class ReportReady(val report: JarvisReport) : JarvisEvent()
    data class NetworkFailed(val message: String) : JarvisEvent()
}

object JarvisReducer {
    @JvmStatic
    fun reduce(state: JarvisViewState, event: JarvisEvent): JarvisViewState =
        when (event) {
            JarvisEvent.ChatStarted -> state.copy(
                mode = JarvisScreenMode.CHATTING,
                loading = true,
                errorMessage = null
            )
            JarvisEvent.ConfirmationStarted -> state.copy(
                mode = JarvisScreenMode.CONFIRMING,
                loading = true,
                errorMessage = null
            )
            is JarvisEvent.UserMessageSubmitted -> state.copy(
                mode = JarvisScreenMode.CHATTING,
                loading = true,
                errorMessage = null,
                chatItems = state.chatItems + listOf(
                    JarvisChatItem.UserMessage(event.message, timestamp()),
                    JarvisChatItem.SystemEvent("Jarvis 正在生成计划", timestamp())
                )
            )
            is JarvisEvent.SystemMessageAdded -> state.copy(
                chatItems = state.chatItems + JarvisChatItem.SystemEvent(event.message, timestamp())
            )
            is JarvisEvent.ChatReply -> state.copy(
                mode = JarvisScreenMode.IDLE,
                loading = false,
                errorMessage = null,
                chatItems = withoutTrailingLoading(state.chatItems) +
                    JarvisChatItem.AssistantMessage(event.message, timestamp())
            )
            is JarvisEvent.PlanReady -> state.copy(
                mode = JarvisScreenMode.PLAN_READY,
                plan = event.plan,
                loading = false,
                errorMessage = null,
                chatItems = withoutTrailingLoading(state.chatItems) + listOf(
                    JarvisChatItem.AssistantMessage("我已生成安全计划，确认后才会执行控制动作。", timestamp()),
                    JarvisChatItem.PlanCard(event.plan, timestamp())
                )
            )
            is JarvisEvent.MissionUpdated -> state.copy(
                mode = modeForMission(event.mission),
                missionId = event.mission.id,
                plan = event.mission.plan,
                timeline = event.timeline,
                pendingDecision = event.mission.pendingDecision,
                loading = false,
                errorMessage = null,
                chatItems = state.chatItems + JarvisChatItem.ProgressCard(
                    event.mission,
                    event.timeline,
                    timestamp()
                )
            )
            is JarvisEvent.DecisionRequired -> state.copy(
                mode = JarvisScreenMode.DECISION_REQUIRED,
                pendingDecision = event.decision,
                loading = false,
                errorMessage = null
            )
            is JarvisEvent.ReportReady -> state.copy(
                mode = JarvisScreenMode.REPORT_READY,
                report = event.report,
                loading = false,
                errorMessage = null,
                chatItems = state.chatItems + JarvisChatItem.ReportCard(event.report, timestamp())
            )
            is JarvisEvent.NetworkFailed -> state.copy(
                mode = JarvisScreenMode.ERROR,
                loading = false,
                errorMessage = event.message,
                chatItems = withoutTrailingLoading(state.chatItems) + JarvisChatItem.ErrorMessage(
                    "Jarvis 请求失败",
                    event.message,
                    timestamp()
                ),
                emergencyStopAvailable = true
            )
        }

    private fun modeForMission(mission: JarvisMission): JarvisScreenMode =
        when {
            mission.pendingDecision != null -> JarvisScreenMode.DECISION_REQUIRED
            mission.state == JarvisMissionState.WAITING_CONFIRMATION -> JarvisScreenMode.PLAN_READY
            mission.state == JarvisMissionState.RUNNING -> JarvisScreenMode.RUNNING
            mission.state == JarvisMissionState.COMPLETED -> JarvisScreenMode.REPORT_READY
            else -> JarvisScreenMode.IDLE
        }

    private fun timestamp(): String = ""

    private fun withoutTrailingLoading(items: List<JarvisChatItem>): List<JarvisChatItem> =
        if (items.lastOrNull() == JarvisChatItem.SystemEvent("Jarvis 正在生成计划", "")) {
            items.dropLast(1)
        } else {
            items
        }
}

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
                emergencyStopAvailable = true
            )
    }
}

sealed class JarvisEvent {
    object ChatStarted : JarvisEvent()
    object ConfirmationStarted : JarvisEvent()
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
            is JarvisEvent.PlanReady -> state.copy(
                mode = JarvisScreenMode.PLAN_READY,
                plan = event.plan,
                loading = false,
                errorMessage = null
            )
            is JarvisEvent.MissionUpdated -> state.copy(
                mode = modeForMission(event.mission),
                missionId = event.mission.id,
                plan = event.mission.plan,
                timeline = event.timeline,
                pendingDecision = event.mission.pendingDecision,
                loading = false,
                errorMessage = null
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
                errorMessage = null
            )
            is JarvisEvent.NetworkFailed -> state.copy(
                mode = JarvisScreenMode.ERROR,
                loading = false,
                errorMessage = event.message,
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
}

package com.example.icarcontroller

enum class DriveExitEvent {
    PAGE_CHANGE,
    THEME_CHANGE,
    APP_PAUSE
}

enum class StopDispatchLane {
    URGENT,
    MOVE_BARRIER
}

object DriveSafetySpec {
    @JvmStatic
    fun shouldStop(activeDirection: String?, event: DriveExitEvent): Boolean =
        event in DriveExitEvent.entries && !activeDirection.isNullOrBlank()

    @JvmStatic
    fun stopDispatchLanes(): List<StopDispatchLane> = listOf(
        StopDispatchLane.URGENT,
        StopDispatchLane.MOVE_BARRIER
    )
}

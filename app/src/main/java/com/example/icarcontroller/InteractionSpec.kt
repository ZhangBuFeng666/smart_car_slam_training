package com.example.icarcontroller

object InteractionSpec {
    @JvmStatic
    fun aiPresentation(): String = "full_page"

    @JvmStatic
    fun pageTransitionMillis(): Int = 240

    @JvmStatic
    fun pressFeedbackScale(): Float = 0.94f

    @JvmStatic
    fun sheetTransitionMillis(): Int = 260

    @JvmStatic
    fun contentBottomClearanceDp(): Int = 108

    @JvmStatic
    fun keyHeroHeightDp(): Int = 372

    @JvmStatic
    fun obsidianHeroHeightDp(): Int = 638

    @JvmStatic
    fun vehicleStageHeightDp(): Int = 312

    @JvmStatic
    fun obsidianPrimaryHeightDp(): Int = 58

    @JvmStatic
    fun obsidianActionGapDp(): Int = 16

    @JvmStatic
    fun obsidianBottomClearanceDp(): Int = 8

    @JvmStatic
    fun homeUsesDarkSystemBars(): Boolean = true

    @JvmStatic
    fun sheetPeekHeightDp(): Int = 260

    @JvmStatic
    fun modeTileHeightDp(): Int = 98

    @JvmStatic
    fun homeRailSideInsetDp(): Int = 18

    @JvmStatic
    fun remoteButtonSizeDp(): Int = 68

    @JvmStatic
    fun navSelectionScale(): Float = 1.06f

    @JvmStatic
    fun statusPulseMillis(): Int = 320

    @JvmStatic
    fun remoteRepeatMillis(): Int = 120

    @JvmStatic
    fun parkingDriveControlSizeDp(): Int = 78

    @JvmStatic
    fun parkingTaskRailHeightDp(): Int = 116

    @JvmStatic
    fun parkingVisionStageHeightDp(): Int = 300

    @JvmStatic
    fun parkingMapStageHeightDp(): Int = 320

    @JvmStatic
    fun parkingPatrolCheckpointCount(): Int = 5

    @JvmStatic
    fun driveUsesExplicitDirectionButtons(): Boolean = true

    @JvmStatic
    fun parkingDriveButtonElevationDp(): Int = 4

    @JvmStatic
    fun parkingThemeToggleSizeDp(): Int = 44

    @JvmStatic
    fun parkingThemeControlPlacement(): String = "global_chrome"

    @JvmStatic
    fun cameraMaxFrameBytes(): Int = 2_000_000

    @JvmStatic
    fun cameraTargetWidth(): Int = 640

    @JvmStatic
    fun cameraTargetHeight(): Int = 480

    @JvmStatic
    fun cameraLatencyTargetMillis(): Int = 300

    @JvmStatic
    fun cameraReconnectDelaysMillis(): IntArray = intArrayOf(1000, 2000, 4000, 5000)

    @JvmStatic
    fun cameraFullscreenOrientation(): String = "landscape"

    @JvmStatic
    fun cameraFullscreenControlLayout(): String = "edge_floating"

    @JvmStatic
    fun cameraFullscreenUsesControlPanels(): Boolean = false

    @JvmStatic
    fun cameraGlassButtonAlpha(): Float = 0.18f
}

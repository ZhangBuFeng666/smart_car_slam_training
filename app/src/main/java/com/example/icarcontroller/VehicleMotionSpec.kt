package com.example.icarcontroller

object VehicleMotionSpec {
    private const val AUTO_ROTATION_DURATION_MILLIS = 8000
    private const val RESUME_DELAY_MILLIS = 900

    @JvmStatic
    fun autoRotationDurationMillis(): Int = AUTO_ROTATION_DURATION_MILLIS

    @JvmStatic
    fun resumeDelayMillis(): Int = RESUME_DELAY_MILLIS

    @JvmStatic
    fun normalizeYawDegrees(degrees: Float): Float =
        ((degrees % 360f) + 360f) % 360f

    @JvmStatic
    fun verticalTranslationDp(): Float = 0f
}

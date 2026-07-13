package com.example.icarcontroller

enum class VisionDetectionSeverity {
    NORMAL,
    WARNING,
    INFO
}

data class VisionDetectionBox(
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val severity: VisionDetectionSeverity = VisionDetectionSeverity.NORMAL
)

data class VisionMetrics(
    val visibleSlots: Int,
    val occupiedSlots: Int,
    val alertCount: Int,
    val statusLabel: String
) {
    companion object {
        @JvmStatic
        fun empty(statusLabel: String): VisionMetrics = VisionMetrics(
            visibleSlots = 0,
            occupiedSlots = 0,
            alertCount = 0,
            statusLabel = statusLabel
        )
    }
}

data class VisionPerceptionSnapshot(
    val metrics: VisionMetrics,
    val detections: List<VisionDetectionBox>,
    val classLabels: List<String>
) {
    companion object {
        @JvmStatic
        fun empty(statusLabel: String = "等待相机"): VisionPerceptionSnapshot =
            VisionPerceptionSnapshot(
                metrics = VisionMetrics.empty(statusLabel),
                detections = emptyList(),
                classLabels = emptyList()
            )
    }
}

package com.example.icarcontroller

object VisionMockDataSource {
    @JvmStatic
    fun snapshot(connected: Boolean): VisionPerceptionSnapshot {
        if (!connected) {
            return VisionPerceptionSnapshot.empty("等待连接")
        }
        val detections = listOf(
            VisionDetectionBox(
                label = "积水",
                confidence = 0.91f,
                left = 0.33f,
                top = 0.58f,
                right = 0.56f,
                bottom = 0.85f,
                severity = VisionDetectionSeverity.WARNING
            ),
            VisionDetectionBox(
                label = "道面异物",
                confidence = 0.88f,
                left = 0.66f,
                top = 0.52f,
                right = 0.81f,
                bottom = 0.75f,
                severity = VisionDetectionSeverity.WARNING
            ),
            VisionDetectionBox(
                label = "禁停车辆",
                confidence = 0.84f,
                left = 0.12f,
                top = 0.38f,
                right = 0.34f,
                bottom = 0.67f,
                severity = VisionDetectionSeverity.WARNING
            ),
            VisionDetectionBox(
                label = "B-07 占用",
                confidence = 0.79f,
                left = 0.58f,
                top = 0.40f,
                right = 0.78f,
                bottom = 0.62f,
                severity = VisionDetectionSeverity.NORMAL
            )
        )
        return VisionPerceptionSnapshot(
            metrics = VisionMetrics(
                visibleSlots = 4,
                occupiedSlots = 1,
                alertCount = 3,
                statusLabel = "Mock 预览"
            ),
            detections = detections,
            classLabels = listOf(
                "积水",
                "道面异物",
                "禁停车辆",
                "车位编号",
                "占用状态",
                "行人"
            )
        )
    }
}

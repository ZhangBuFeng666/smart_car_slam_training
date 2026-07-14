package com.example.icarcontroller

import java.util.Locale
import kotlin.math.roundToInt

object VisionUiSpec {
    private val labelNames = mapOf(
        "parking_slot" to "停车位",
        "car" to "车辆",
        "no_parking_sign" to "禁停标志",
        "entrance_sign" to "入口标志",
        "exit_sign" to "出口标志",
        "direction_arrow" to "方向箭头",
        "stop_line" to "停止线",
        "roadblock" to "路障",
        "danger_sign" to "危险标志"
    )
    private val directionNames = mapOf(
        "go_straight" to "直行",
        "turn_left" to "左转",
        "turn_right" to "右转"
    )

    @JvmStatic
    fun labelText(label: String): String = labelNames[label] ?: label

    @JvmStatic
    fun directionText(direction: String): String = directionNames[direction] ?: direction

    @JvmStatic
    fun detectionLabel(detection: VisionDetection): String {
        val direction = detection.stableDirection ?: detection.direction
        val base = direction?.let(::directionText) ?: labelText(detection.label)
        val trackId = detection.trackId ?: return base
        return "$base#$trackId"
    }

    @JvmStatic
    fun detectionConfidence(detection: VisionDetection): String =
        confidenceText(detection.directionConfidence ?: detection.confidence)

    @JvmStatic
    fun modelText(ready: Boolean, device: String?, fp16: Boolean): String {
        if (!ready) return "模型加载中"
        val processor = if (device?.startsWith("cuda") == true) "CUDA" else "CPU"
        return "$processor · ${if (fp16) "FP16" else "FP32"}"
    }

    @JvmStatic
    fun performanceText(inferenceMs: Double, sourceFps: Double): String =
        String.format(Locale.US, "%.1f ms · %.1f FPS", inferenceMs, sourceFps)

    @JvmStatic
    fun confidenceText(confidence: Double): String =
        "${(confidence.coerceIn(0.0, 1.0) * 100).roundToInt()}%"
}

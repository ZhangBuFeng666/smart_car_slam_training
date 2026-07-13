package com.example.icarcontroller

import java.net.URLEncoder
import java.util.Locale

class CarApi(host: String, private val port: Int) {
    private val normalizedHost = host
        .trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .trimEnd('/')

    fun healthUrl(): String = "$baseUrl/health"

    fun statusUrl(): String = "$baseUrl/status"

    fun cameraStreamUrl(): String = "$baseUrl/camera/stream"

    fun cameraStatusUrl(): String = "$baseUrl/camera/status"

    fun cameraRestartUrl(): String = "$baseUrl/camera/restart"

    fun speakUrl(): String = "$baseUrl/speak"

    fun notifyUrl(event: String): String = "$baseUrl/notify/${encode(event)}"

    fun startTaskUrl(task: String): String = "$baseUrl/start/${encode(task)}"

    fun stopTaskUrl(task: String): String = "$baseUrl/stop/${encode(task)}"

    fun stopAllUrl(): String = "$baseUrl/stop/all"

    fun emergencyStopUrl(): String = "$baseUrl/emergency_stop"

    fun navigationInitialPoseUrl(x: Double, y: Double, yaw: Double): String =
        navigationPoseUrl("initial_pose", x, y, yaw)

    fun navigationGoalUrl(x: Double, y: Double, yaw: Double): String =
        navigationPoseUrl("goal", x, y, yaw)

    fun moveUrl(direction: String, speed: Double, turn: Double? = null): String {
        val speedText = speed.toFixedText()
        val turnPart = turn?.let { "&turn=${it.toFixedText()}" } ?: ""
        return "$baseUrl/move/${encode(direction)}?speed=$speedText$turnPart"
    }

    private val baseUrl: String
        get() = "http://$normalizedHost:$port"

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun navigationPoseUrl(kind: String, x: Double, y: Double, yaw: Double): String =
        "$baseUrl/navigation/$kind?x=${x.toFixedText()}&y=${y.toFixedText()}&yaw=${yaw.toFixedText()}"

    private fun Double.toFixedText(): String =
        String.format(Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')
}

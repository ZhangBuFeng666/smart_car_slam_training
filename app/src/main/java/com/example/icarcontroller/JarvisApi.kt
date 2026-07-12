package com.example.icarcontroller

import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class JarvisApi @JvmOverloads constructor(
    host: String,
    private val token: String,
    private val port: Int = 8100
) {
    private val normalizedHost = host
        .trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .trimEnd('/')

    fun healthUrl(): String = "$baseUrl/health"

    fun missionUrl(missionId: String): String =
        "$baseUrl/api/v1/missions/${encode(missionId)}"

    fun authorizationHeader(): String = "Bearer $token"

    fun chat(message: String, context: JSONObject = JSONObject()): JarvisMissionPlan {
        val body = JSONObject()
            .put("message", message)
            .put("context", context)
        val response = request("POST", "$baseUrl/api/v1/chat", body)
        return JarvisJson.parsePlan(response.getJSONObject("plan"))
    }

    fun createMission(plan: JarvisMissionPlan): JarvisMission {
        val response = request(
            "POST",
            "$baseUrl/api/v1/missions",
            JSONObject().put("plan", plan.toJson())
        )
        return JarvisJson.parseMission(response)
    }

    fun confirmMission(missionId: String): JarvisMission =
        JarvisJson.parseMission(request("POST", "$baseUrl/api/v1/missions/${encode(missionId)}/confirm"))

    fun cancelMission(missionId: String): JarvisMission =
        JarvisJson.parseMission(request("POST", "$baseUrl/api/v1/missions/${encode(missionId)}/cancel"))

    fun submitDecision(missionId: String, decision: JarvisDecision): JarvisDecision =
        JarvisJson.parseDecision(
            request(
                "POST",
                "$baseUrl/api/v1/missions/${encode(missionId)}/decisions",
                decision.toJson()
            )
        )

    fun getMission(missionId: String): JarvisMission =
        JarvisJson.parseMission(request("GET", missionUrl(missionId)))

    fun getTimeline(missionId: String): List<JarvisTimelineEntry> {
        val array = requestArray("GET", "$baseUrl/api/v1/missions/${encode(missionId)}/timeline")
        return (0 until array.length()).map { JarvisJson.parseTimelineEntry(array.getJSONObject(it)) }
    }

    fun postVisionEvent(event: JarvisVisionEvent): JSONObject =
        request("POST", "$baseUrl/api/v1/vision-events", event.toJson())

    fun getReport(missionId: String): JarvisReport =
        JarvisJson.parseReport(
            request("GET", "$baseUrl/api/v1/missions/${encode(missionId)}/report")
        )

    private val baseUrl: String
        get() = "http://$normalizedHost:$port"

    private fun request(method: String, url: String, body: JSONObject? = null): JSONObject =
        JSONObject(requestText(method, url, body))

    private fun requestArray(method: String, url: String): org.json.JSONArray =
        org.json.JSONArray(requestText(method, url, null))

    private fun requestText(method: String, url: String, body: JSONObject?): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 10000
            setRequestProperty("Authorization", authorizationHeader())
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                    it.write(body.toString())
                }
            }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val text = stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
            if (connection.responseCode !in 200..299) {
                throw JarvisProtocolException("Jarvis request failed: ${connection.responseCode}")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun JarvisMissionPlan.toJson(): JSONObject =
        JSONObject()
            .put("summary", summary)
            .put("steps", org.json.JSONArray(steps.map { it.toJson() }))
            .put("completion_criteria", org.json.JSONArray(completionCriteria))
            .put("requires_confirmation", requiresConfirmation)

    private fun JarvisMissionStep.toJson(): JSONObject =
        JSONObject()
            .put("action", action.name)
            .put("arguments", JSONObject(arguments))

    private fun JarvisDecision.toJson(): JSONObject =
        JSONObject()
            .put("decision", decision.wireValue)
            .put("event_id", eventId)
            .put("note", note)

    private fun JarvisVisionEvent.toJson(): JSONObject =
        JSONObject()
            .put("mission_id", missionId)
            .put("source", source)
            .put("event_type", eventType)
            .put("label", label)
            .put("confidence", confidence)
            .put("position", position)
            .put("track_id", trackId)
            .put("image_path", imagePath)
            .put("timestamp", timestamp)
            .put("metadata", JSONObject(metadata))
}

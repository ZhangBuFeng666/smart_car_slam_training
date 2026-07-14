package com.example.icarcontroller

import java.util.Locale

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
    fun vehicleStageHeightDp(): Int = 404

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
    fun parkingDriveControlSizeDp(): Int = 62

    @JvmStatic
    fun drivePortraitCameraWidthUnits(): Int = 10

    @JvmStatic
    fun drivePortraitCameraHeightUnits(): Int = 9

    @JvmStatic
    fun drivePortraitInfoPanelMode(): String = "speed_only"

    @JvmStatic
    fun driveAuxiliaryButtonPlacement(): String = "grid_corners"

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
    fun cameraGlassButtonAlpha(): Float = 0.12f

    @JvmStatic
    fun cameraDecodeSampleSize(sourceWidth: Int, sourceHeight: Int): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return 1
        }
        var sampleSize = 1
        while (
            sourceWidth / sampleSize >= cameraTargetWidth() * 2 &&
            sourceHeight / sampleSize >= cameraTargetHeight() * 2
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    @JvmStatic
    fun cameraHttp503State(responseText: String): String {
        val state = topLevelJsonStringField(responseText, "state")
            ?.lowercase(Locale.US)
        return when (state) {
            "busy", "missing" -> state
            else -> "disconnected"
        }
    }

    private fun topLevelJsonStringField(json: String, requestedKey: String): String? {
        var index = skipWhitespace(json, 0)
        if (index >= json.length || json[index] != '{') {
            return null
        }
        index += 1
        var requestedValue: String? = null

        while (true) {
            index = skipWhitespace(json, index)
            if (index >= json.length) {
                return null
            }
            if (json[index] == '}') {
                val endIndex = skipWhitespace(json, index + 1)
                return if (endIndex == json.length) requestedValue else null
            }
            val key = parseJsonString(json, index) ?: return null
            index = skipWhitespace(json, key.nextIndex)
            if (index >= json.length || json[index] != ':') {
                return null
            }
            index = skipWhitespace(json, index + 1)

            if (key.value == requestedKey && index < json.length && json[index] == '"') {
                val value = parseJsonString(json, index) ?: return null
                requestedValue = value.value
                index = value.nextIndex
            } else {
                index = parseJsonValue(json, index) ?: return null
            }

            index = skipWhitespace(json, index)
            when {
                index >= json.length -> return null
                json[index] == ',' -> index += 1
                json[index] == '}' -> {
                    val endIndex = skipWhitespace(json, index + 1)
                    return if (endIndex == json.length) requestedValue else null
                }
                else -> return null
            }
        }
    }

    private fun parseJsonString(json: String, startIndex: Int): ParsedJsonString? {
        if (startIndex >= json.length || json[startIndex] != '"') {
            return null
        }
        val value = StringBuilder()
        var index = startIndex + 1
        while (index < json.length) {
            val character = json[index]
            when {
                character == '"' -> return ParsedJsonString(value.toString(), index + 1)
                character != '\\' -> value.append(character)
                else -> {
                    index += 1
                    if (index >= json.length) {
                        return null
                    }
                    when (val escaped = json[index]) {
                        '"', '\\', '/' -> value.append(escaped)
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000c')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            if (index + 4 >= json.length) {
                                return null
                            }
                            val codePoint = json.substring(index + 1, index + 5).toIntOrNull(16)
                                ?: return null
                            value.append(codePoint.toChar())
                            index += 4
                        }
                        else -> return null
                    }
                }
            }
            index += 1
        }
        return null
    }

    private fun parseJsonValue(json: String, startIndex: Int): Int? {
        val index = skipWhitespace(json, startIndex)
        if (index >= json.length) {
            return null
        }
        return when (json[index]) {
            '"' -> parseJsonString(json, index)?.nextIndex
            '{' -> parseJsonObject(json, index)
            '[' -> parseJsonArray(json, index)
            't' -> parseJsonLiteral(json, index, "true")
            'f' -> parseJsonLiteral(json, index, "false")
            'n' -> parseJsonLiteral(json, index, "null")
            '-', in '0'..'9' -> parseJsonNumber(json, index)
            else -> null
        }
    }

    private fun parseJsonObject(json: String, startIndex: Int): Int? {
        var index = skipWhitespace(json, startIndex + 1)
        if (index < json.length && json[index] == '}') {
            return index + 1
        }
        while (index < json.length) {
            val key = parseJsonString(json, index) ?: return null
            index = skipWhitespace(json, key.nextIndex)
            if (index >= json.length || json[index] != ':') {
                return null
            }
            index = parseJsonValue(json, index + 1) ?: return null
            index = skipWhitespace(json, index)
            when {
                index >= json.length -> return null
                json[index] == ',' -> index = skipWhitespace(json, index + 1)
                json[index] == '}' -> return index + 1
                else -> return null
            }
        }
        return null
    }

    private fun parseJsonArray(json: String, startIndex: Int): Int? {
        var index = skipWhitespace(json, startIndex + 1)
        if (index < json.length && json[index] == ']') {
            return index + 1
        }
        while (index < json.length) {
            index = parseJsonValue(json, index) ?: return null
            index = skipWhitespace(json, index)
            when {
                index >= json.length -> return null
                json[index] == ',' -> index = skipWhitespace(json, index + 1)
                json[index] == ']' -> return index + 1
                else -> return null
            }
        }
        return null
    }

    private fun parseJsonLiteral(json: String, startIndex: Int, literal: String): Int? {
        val endIndex = startIndex + literal.length
        return if (endIndex <= json.length && json.regionMatches(startIndex, literal, 0, literal.length)) {
            endIndex
        } else {
            null
        }
    }

    private fun parseJsonNumber(json: String, startIndex: Int): Int? {
        var index = startIndex
        if (json[index] == '-') {
            index += 1
            if (index >= json.length) return null
        }
        when {
            json[index] == '0' -> index += 1
            json[index] in '1'..'9' -> while (index < json.length && json[index].isDigit()) index += 1
            else -> return null
        }
        if (index < json.length && json[index] == '.') {
            index += 1
            val fractionStart = index
            while (index < json.length && json[index].isDigit()) index += 1
            if (index == fractionStart) return null
        }
        if (index < json.length && (json[index] == 'e' || json[index] == 'E')) {
            index += 1
            if (index < json.length && (json[index] == '+' || json[index] == '-')) index += 1
            val exponentStart = index
            while (index < json.length && json[index].isDigit()) index += 1
            if (index == exponentStart) return null
        }
        return index
    }

    private fun skipWhitespace(value: String, startIndex: Int): Int {
        var index = startIndex
        while (index < value.length && value[index].isWhitespace()) {
            index += 1
        }
        return index
    }

    private data class ParsedJsonString(val value: String, val nextIndex: Int)
}

class CameraReparentGuard {
    private var skipNextDetach = false

    @Synchronized
    fun beginReparent() {
        skipNextDetach = true
    }

    @Synchronized
    fun endReparent() {
        skipNextDetach = false
    }

    @Synchronized
    fun onAttached() {
        skipNextDetach = false
    }

    @Synchronized
    fun shouldReleaseOnDetach(): Boolean {
        val shouldRelease = !skipNextDetach
        skipNextDetach = false
        return shouldRelease
    }
}

data class LatestValueOffer<T : Any>(
    val replaced: T?,
    val shouldScheduleDrain: Boolean
)

class LatestValueCoalescer<T : Any> {
    private val lock = Any()
    private var pending: T? = null
    private var drainScheduled = false

    fun offer(value: T): LatestValueOffer<T> = synchronized(lock) {
        val replaced = pending
        pending = value
        val shouldSchedule = !drainScheduled
        if (shouldSchedule) {
            drainScheduled = true
        }
        LatestValueOffer(replaced, shouldSchedule)
    }

    fun drain(): T? = synchronized(lock) {
        val value = pending
        pending = null
        drainScheduled = false
        value
    }

    fun clear(): T? = synchronized(lock) {
        val value = pending
        pending = null
        value
    }
}

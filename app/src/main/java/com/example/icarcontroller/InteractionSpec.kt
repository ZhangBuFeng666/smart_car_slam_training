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

        while (true) {
            index = skipWhitespace(json, index)
            if (index >= json.length || json[index] == '}') {
                return null
            }
            val key = parseJsonString(json, index) ?: return null
            index = skipWhitespace(json, key.nextIndex)
            if (index >= json.length || json[index] != ':') {
                return null
            }
            index = skipWhitespace(json, index + 1)

            if (key.value == requestedKey) {
                val value = parseJsonString(json, index) ?: return null
                val delimiterIndex = skipWhitespace(json, value.nextIndex)
                if (delimiterIndex >= json.length || (json[delimiterIndex] != ',' && json[delimiterIndex] != '}')) {
                    return null
                }
                return value.value
            }

            index = skipJsonValue(json, index) ?: return null
            index = skipWhitespace(json, index)
            when {
                index >= json.length -> return null
                json[index] == ',' -> index += 1
                json[index] == '}' -> return null
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

    private fun skipJsonValue(json: String, startIndex: Int): Int? {
        var index = startIndex
        var objectDepth = 0
        var arrayDepth = 0
        var inString = false
        var escaped = false
        while (index < json.length) {
            val character = json[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{' -> objectDepth += 1
                    '}' -> if (objectDepth > 0) objectDepth -= 1 else if (arrayDepth == 0) return index
                    '[' -> arrayDepth += 1
                    ']' -> if (arrayDepth > 0) arrayDepth -= 1 else return null
                    ',' -> if (objectDepth == 0 && arrayDepth == 0) return index
                }
            }
            index += 1
        }
        return if (!inString && objectDepth == 0 && arrayDepth == 0) index else null
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

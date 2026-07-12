package com.example.icarcontroller

import java.io.IOException
import java.io.InputStream

class MjpegFrameReader(
    private val input: InputStream,
    private val maxFrameBytes: Int,
) {
    init {
        require(maxFrameBytes > 0) { "maxFrameBytes must be positive" }
    }

    private val fallbackBuffer = ByteArray(maxFrameBytes)
    private val headerLineBuffer = ByteArray(MAX_HEADER_LINE_BYTES)

    @Throws(IOException::class)
    fun nextFrame(): ByteArray? {
        var previous = -1
        var lineLength = 0
        var lineOverflow = false
        var contentLength: Int? = null

        while (true) {
            val current = input.read()
            if (current == -1) {
                return null
            }

            if (current == LINE_FEED) {
                if (!lineOverflow) {
                    val normalizedLength = if (
                        lineLength > 0 && headerLineBuffer[lineLength - 1].toInt() == CARRIAGE_RETURN
                    ) {
                        lineLength - 1
                    } else {
                        lineLength
                    }
                    if (normalizedLength == 0) {
                        contentLength?.let { return readContentLengthFrame(it) }
                    } else {
                        parseContentLength(normalizedLength)?.let { contentLength = it }
                    }
                }
                lineLength = 0
                lineOverflow = false
            } else if (!lineOverflow) {
                if (lineLength == headerLineBuffer.size) {
                    lineOverflow = true
                } else {
                    headerLineBuffer[lineLength++] = current.toByte()
                }
            }

            if (previous == JPEG_MARKER && current == JPEG_START) {
                return readFallbackFrame()
            }
            previous = current
        }
    }

    private fun readContentLengthFrame(contentLength: Int): ByteArray {
        val frame = ByteArray(contentLength)
        var offset = 0
        while (offset < frame.size) {
            val read = input.read(frame, offset, frame.size - offset)
            if (read == -1) {
                throw IOException("Truncated JPEG payload")
            }
            if (read == 0) {
                val next = input.read()
                if (next == -1) {
                    throw IOException("Truncated JPEG payload")
                }
                frame[offset++] = next.toByte()
            } else {
                offset += read
            }
        }
        return frame
    }

    private fun readFallbackFrame(): ByteArray {
        if (maxFrameBytes < 2) {
            throw IOException("JPEG frame exceeds maximum of $maxFrameBytes bytes")
        }
        fallbackBuffer[0] = JPEG_MARKER.toByte()
        fallbackBuffer[1] = JPEG_START.toByte()
        var frameLength = 2
        var previous = JPEG_START

        while (true) {
            val current = input.read()
            if (current == -1) {
                throw IOException("Truncated JPEG frame")
            }
            if (frameLength == maxFrameBytes) {
                throw IOException("JPEG frame exceeds maximum of $maxFrameBytes bytes")
            }
            fallbackBuffer[frameLength++] = current.toByte()
            if (previous == JPEG_MARKER && current == JPEG_END) {
                return fallbackBuffer.copyOf(frameLength)
            }
            previous = current
        }
    }

    private fun parseContentLength(lineLength: Int): Int? {
        var colonIndex = 0
        while (colonIndex < lineLength && headerLineBuffer[colonIndex].toInt() != COLON) {
            colonIndex++
        }
        if (colonIndex != CONTENT_LENGTH_HEADER.size) {
            return null
        }
        for (index in CONTENT_LENGTH_HEADER.indices) {
            if (!asciiEqualsIgnoreCase(headerLineBuffer[index], CONTENT_LENGTH_HEADER[index])) {
                return null
            }
        }

        var valueStart = colonIndex + 1
        while (valueStart < lineLength && headerLineBuffer[valueStart].isHorizontalWhitespace()) {
            valueStart++
        }
        var valueEnd = lineLength
        while (valueEnd > valueStart && headerLineBuffer[valueEnd - 1].isHorizontalWhitespace()) {
            valueEnd--
        }
        if (valueStart == valueEnd) {
            throw IOException("Invalid Content-Length")
        }

        var value = 0
        for (index in valueStart until valueEnd) {
            val digit = (headerLineBuffer[index].toInt() and 0xff) - ASCII_ZERO
            if (digit !in 0..9) {
                throw IOException("Invalid Content-Length")
            }
            if (
                value > maxFrameBytes / 10 ||
                value == maxFrameBytes / 10 && digit > maxFrameBytes % 10
            ) {
                throw IOException("Content-Length exceeds maximum of $maxFrameBytes bytes")
            }
            value = value * 10 + digit
        }
        if (value == 0) {
            throw IOException("Content-Length must be positive")
        }
        return value
    }

    private fun Byte.isHorizontalWhitespace(): Boolean {
        val value = toInt()
        return value == SPACE || value == HORIZONTAL_TAB
    }

    private fun asciiEqualsIgnoreCase(left: Byte, right: Byte): Boolean =
        asciiLowercase(left.toInt() and 0xff) == asciiLowercase(right.toInt() and 0xff)

    private fun asciiLowercase(value: Int): Int =
        if (value in ASCII_UPPER_A..ASCII_UPPER_Z) value + ASCII_CASE_OFFSET else value

    private companion object {
        val CONTENT_LENGTH_HEADER = "Content-Length".encodeToByteArray()

        const val MAX_HEADER_LINE_BYTES = 8 * 1024
        const val LINE_FEED = '\n'.code
        const val CARRIAGE_RETURN = '\r'.code
        const val HORIZONTAL_TAB = '\t'.code
        const val SPACE = ' '.code
        const val COLON = ':'.code
        const val ASCII_ZERO = '0'.code
        const val ASCII_UPPER_A = 'A'.code
        const val ASCII_UPPER_Z = 'Z'.code
        const val ASCII_CASE_OFFSET = 'a'.code - 'A'.code
        const val JPEG_MARKER = 0xff
        const val JPEG_START = 0xd8
        const val JPEG_END = 0xd9
    }
}

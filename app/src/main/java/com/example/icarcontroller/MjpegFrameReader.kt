package com.example.icarcontroller

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class MjpegFrameReader(
    private val input: InputStream,
    private val maxFrameBytes: Int,
) {
    init {
        require(maxFrameBytes > 0) { "maxFrameBytes must be positive" }
    }

    @Throws(IOException::class)
    fun nextFrame(): ByteArray? {
        var previous = -1
        while (true) {
            val current = input.read()
            if (current == -1) {
                return null
            }
            if (previous == JPEG_MARKER && current == JPEG_START) {
                break
            }
            previous = current
        }

        val frame = ByteArrayOutputStream(minOf(maxFrameBytes, DEFAULT_CAPACITY))
        frame.writeBounded(JPEG_MARKER)
        frame.writeBounded(JPEG_START)
        previous = JPEG_START

        while (true) {
            val current = input.read()
            if (current == -1) {
                throw IOException("Truncated JPEG frame")
            }
            frame.writeBounded(current)
            if (previous == JPEG_MARKER && current == JPEG_END) {
                return frame.toByteArray()
            }
            previous = current
        }
    }

    private fun ByteArrayOutputStream.writeBounded(value: Int) {
        if (size() >= maxFrameBytes) {
            throw IOException("JPEG frame exceeds maximum of $maxFrameBytes bytes")
        }
        write(value)
    }

    private companion object {
        const val JPEG_MARKER = 0xff
        const val JPEG_START = 0xd8
        const val JPEG_END = 0xd9
        const val DEFAULT_CAPACITY = 8 * 1024
    }
}

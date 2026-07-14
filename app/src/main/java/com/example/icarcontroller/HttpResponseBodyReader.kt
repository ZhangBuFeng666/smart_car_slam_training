package com.example.icarcontroller

import java.io.InputStream

object HttpResponseBodyReader {
    fun read(input: InputStream?, contentLength: Long, includeBody: Boolean): String {
        if (!includeBody || input == null) return ""
        if (contentLength <= 0L || contentLength > Int.MAX_VALUE) {
            return input.bufferedReader().use { it.readText() }
        }

        val bytes = ByteArray(contentLength.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) break
            offset += count
        }
        return bytes.decodeToString(endIndex = offset)
    }
}

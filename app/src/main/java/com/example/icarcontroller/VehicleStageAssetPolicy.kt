package com.example.icarcontroller

import java.net.URI

object VehicleStageAssetPolicy {
    private const val ORIGIN = "https://icar.local"
    private const val URL_PREFIX = "/vehicle_stage/"
    private const val ASSET_PREFIX = "vehicle_stage/"

    @JvmStatic
    fun startUrl(): String = "$ORIGIN${URL_PREFIX}index.html"

    @JvmStatic
    fun assetPathFor(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        if (uri.scheme != "https" || uri.host != "icar.local") return null

        val rawPath = uri.rawPath ?: return null
        val decodedPath = uri.path ?: return null
        if (!rawPath.startsWith(URL_PREFIX) || !decodedPath.startsWith(URL_PREFIX)) return null
        if (decodedPath.contains('\\')) return null

        val relative = decodedPath.removePrefix(URL_PREFIX)
        if (relative.isBlank() || relative.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            return null
        }
        return ASSET_PREFIX + relative
    }

    @JvmStatic
    fun mimeType(assetPath: String): String = when {
        assetPath.endsWith(".html") -> "text/html"
        assetPath.endsWith(".css") -> "text/css"
        assetPath.endsWith(".js") -> "application/javascript"
        assetPath.endsWith(".glb") -> "model/gltf-binary"
        assetPath.endsWith(".md") -> "text/markdown"
        else -> "application/octet-stream"
    }
}

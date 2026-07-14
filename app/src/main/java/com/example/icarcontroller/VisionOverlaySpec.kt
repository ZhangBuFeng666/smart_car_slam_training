package com.example.icarcontroller

import kotlin.math.max

data class VisionOverlayRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

object VisionOverlaySpec {
    @JvmStatic
    fun mapBox(
        box: VisionBox,
        viewWidth: Int,
        viewHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): VisionOverlayRect {
        if (viewWidth <= 0 || viewHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return VisionOverlayRect(0f, 0f, 0f, 0f)
        }
        val scale = max(
            viewWidth.toFloat() / sourceWidth,
            viewHeight.toFloat() / sourceHeight
        )
        val offsetX = (viewWidth - sourceWidth * scale) / 2f
        val offsetY = (viewHeight - sourceHeight * scale) / 2f
        return VisionOverlayRect(
            left = offsetX + (box.left * sourceWidth * scale).toFloat(),
            top = offsetY + (box.top * sourceHeight * scale).toFloat(),
            right = offsetX + (box.right * sourceWidth * scale).toFloat(),
            bottom = offsetY + (box.bottom * sourceHeight * scale).toFloat()
        )
    }
}

package com.myp.sleepplayer.playback

internal enum class VideoDisplayShape {
    UNKNOWN,
    PORTRAIT,
    NON_PORTRAIT
}

internal object VideoDisplayGeometry {
    // Media3 VideoSize already includes the rotation applied by the renderer.
    fun fromDisplaySize(
        width: Int,
        height: Int,
        pixelWidthHeightRatio: Float
    ): VideoDisplayShape = fromFormat(width, height, pixelWidthHeightRatio, 0)

    fun fromFormat(
        width: Int,
        height: Int,
        pixelWidthHeightRatio: Float,
        rotationDegrees: Int
    ): VideoDisplayShape {
        if (width <= 0 || height <= 0 ||
            !pixelWidthHeightRatio.isFinite() || pixelWidthHeightRatio <= 0f ||
            rotationDegrees % 90 != 0
        ) return VideoDisplayShape.UNKNOWN

        // Compare in Double so a valid large pixel ratio cannot overflow Float.
        val displayWidth = width.toDouble() * pixelWidthHeightRatio.toDouble()
        val displayHeight = height.toDouble()
        val swapsAxes = rotationDegrees % 180 != 0
        val isPortrait = if (swapsAxes) {
            displayWidth > displayHeight
        } else {
            displayHeight > displayWidth
        }
        return if (isPortrait) VideoDisplayShape.PORTRAIT else VideoDisplayShape.NON_PORTRAIT
    }
}

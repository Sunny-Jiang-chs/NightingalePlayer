package com.myp.sleepplayer.playback

object VideoDisplayGeometryTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val portrait = VideoDisplayShape.PORTRAIT
        val nonPortrait = VideoDisplayShape.NON_PORTRAIT
        val unknown = VideoDisplayShape.UNKNOWN

        assertEquals(portrait, VideoDisplayGeometry.fromDisplaySize(1080, 1920, 1f))
        assertEquals(nonPortrait, VideoDisplayGeometry.fromDisplaySize(1920, 1080, 1f))
        assertEquals(nonPortrait, VideoDisplayGeometry.fromDisplaySize(1080, 1080, 1f))
        assertEquals(portrait, VideoDisplayGeometry.fromDisplaySize(1000, 1001, 1f))

        // Pixel aspect ratio can reverse the orientation suggested by raw dimensions.
        assertEquals(nonPortrait, VideoDisplayGeometry.fromDisplaySize(720, 1080, 2f))
        assertEquals(portrait, VideoDisplayGeometry.fromDisplaySize(1920, 1080, 0.5f))
        assertEquals(nonPortrait, VideoDisplayGeometry.fromDisplaySize(540, 1080, 2f))

        for (rotation in listOf(90, 270, -90, -270, 450, -450)) {
            assertEquals(portrait, VideoDisplayGeometry.fromFormat(1920, 1080, 1f, rotation))
            assertEquals(nonPortrait, VideoDisplayGeometry.fromFormat(1080, 1920, 1f, rotation))
            assertEquals(nonPortrait, VideoDisplayGeometry.fromFormat(1080, 1080, 1f, rotation))
        }
        for (rotation in listOf(0, 180, -180, 360, -360, 720)) {
            assertEquals(portrait, VideoDisplayGeometry.fromFormat(1080, 1920, 1f, rotation))
            assertEquals(nonPortrait, VideoDisplayGeometry.fromFormat(1920, 1080, 1f, rotation))
        }

        // Rotate the displayed rectangle, including its non-square pixels.
        assertEquals(portrait, VideoDisplayGeometry.fromFormat(720, 1080, 2f, 90))
        assertEquals(nonPortrait, VideoDisplayGeometry.fromFormat(1920, 1080, 0.5f, 270))
        assertEquals(nonPortrait, VideoDisplayGeometry.fromFormat(540, 1080, 2f, 90))
        // A renderer-corrected 1080x1920 size must not be rotated a second time.
        assertEquals(portrait, VideoDisplayGeometry.fromDisplaySize(1080, 1920, 1f))

        for ((width, height) in listOf(0 to 1080, 1920 to 0, -1 to 1080, 1920 to -1)) {
            assertEquals(unknown, VideoDisplayGeometry.fromDisplaySize(width, height, 1f))
            assertEquals(unknown, VideoDisplayGeometry.fromFormat(width, height, 1f, 90))
        }
        for (ratio in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(unknown, VideoDisplayGeometry.fromDisplaySize(1080, 1920, ratio))
            assertEquals(unknown, VideoDisplayGeometry.fromFormat(1080, 1920, ratio, 90))
        }
        for (rotation in listOf(-1, 1, 45, -45, 91, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(unknown, VideoDisplayGeometry.fromFormat(1080, 1920, 1f, rotation))
        }

        // Extreme but finite inputs remain classifiable without numeric overflow.
        assertEquals(nonPortrait, VideoDisplayGeometry.fromDisplaySize(Int.MAX_VALUE, 1, Float.MAX_VALUE))
        assertEquals(portrait, VideoDisplayGeometry.fromFormat(Int.MAX_VALUE, 1, Float.MAX_VALUE, 90))
        assertEquals(portrait, VideoDisplayGeometry.fromDisplaySize(1, Int.MAX_VALUE, Float.MIN_VALUE))
        assertEquals(nonPortrait, VideoDisplayGeometry.fromFormat(1, Int.MAX_VALUE, Float.MIN_VALUE, 270))
        println("Video display geometry: all checks passed")
    }

    private fun assertEquals(expected: VideoDisplayShape, actual: VideoDisplayShape) {
        check(expected == actual) { "Expected <$expected>, got <$actual>" }
    }
}

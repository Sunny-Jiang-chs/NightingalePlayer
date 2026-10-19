package com.myp.sleepplayer.playback

import java.nio.ByteBuffer

object H264SampleDependenciesTest {
    @JvmStatic
    fun main(args: Array<String>) {
        var checked = 0
        fun checkSample(expected: Boolean, vararg bytes: Int) {
            val buffer = ByteBuffer.allocateDirect(bytes.size + 12)
            buffer.put(bytes.map(Int::toByte).toByteArray())
            val position = buffer.position()
            check(H264SampleDependencies.isNonReferencePicture(buffer, position) == expected) {
                "Unexpected classification for ${bytes.toList()}"
            }
            check(buffer.position() == position) { "Parser moved the decoder's buffer position" }
            checked++
        }
        checkSample(true, 0, 0, 0, 1, 0x01, 0x80)
        checkSample(true, 0, 0, 1, 0x01, 0x80)
        checkSample(true, 0, 0, 1, 9, 0xf0, 0, 0, 1, 1, 0x80, 0, 0, 0, 1, 1, 0x80)
        checkSample(false, 0, 0, 1, 0x41, 0x80) // Reference P/B picture.
        checkSample(false, 0, 0, 1, 0x65, 0x80) // IDR.
        checkSample(false, 0, 0, 1, 1, 0x80, 0, 0, 1, 0x21, 0x80) // One reference slice.
        for (type in listOf(2, 3, 4, 5, 6, 7, 8, 12, 14, 20, 31)) {
            checkSample(false, 0, 0, 1, type, 0x80, 0, 0, 1, 1, 0x80)
        }
        checkSample(false, 0, 0, 1, 9, 0xf0) // No picture.
        checkSample(false, 0, 0, 1, 0x81, 0x80) // Forbidden bit.
        checkSample(false, 0, 0, 0, 2, 1, 0x80) // Length prefix, not Annex B.
        checkSample(false, 0, 0, 0, 1, 1) // Header only.
        checkSample(false, 0, 0, 1, 1, 0x80, 0, 0, 1) // Truncated final NAL.
        checkSample(true, 0, 0, 1, 1, 0x80, 0, 0, 3, 1, 0x80) // Escaped start-code bytes.
        println("H264 dependency safety: $checked checks passed")
    }
}

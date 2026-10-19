package com.myp.sleepplayer.playback

import java.nio.ByteBuffer

/** Conservative check for a complete Annex B access unit, as supplied by Media3's MP4 extractor. */
internal object H264SampleDependencies {
    fun isNonReferencePicture(data: ByteBuffer, size: Int): Boolean {
        if (size !in 5..data.limit()) return false
        var offset = 0
        var hasPicture = false
        while (offset < size) {
            val prefix = startCodeLength(data, offset, size)
            if (prefix == 0) return false
            val headerOffset = offset + prefix
            if (headerOffset >= size) return false
            val header = data.get(headerOffset).toInt() and 0xff
            if (header and 0x80 != 0) return false
            var next = headerOffset + 1
            while (next < size && startCodeLength(data, next, size) == 0) next++
            when (header and 0x1f) {
                1 -> {
                    // nal_ref_idc == 0: this picture cannot be used to predict another picture.
                    if (header and 0x60 != 0 || next <= headerOffset + 1) return false
                    hasPicture = true
                }
                9 -> if (next <= headerOffset + 1) return false // Access unit delimiter.
                // Preserve SPS/PPS, SEI, IDR, data partitions and extension/unknown NAL units.
                else -> return false
            }
            offset = next
        }
        return hasPicture
    }

    private fun startCodeLength(data: ByteBuffer, offset: Int, size: Int): Int {
        if (offset + 3 > size || data.get(offset) != 0.toByte() || data.get(offset + 1) != 0.toByte()) return 0
        if (data.get(offset + 2) == 1.toByte()) return 3
        return if (offset + 4 <= size && data.get(offset + 2) == 0.toByte() && data.get(offset + 3) == 1.toByte()) 4 else 0
    }
}

package com.myp.sleepplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Seeks before the midpoint and decodes to it under one elapsed-time deadline. */
internal class BoundedVideoFrameDecoder(private val context: Context) {
    private companion object {
        const val TOTAL_BUDGET_MS = 6_000L
        const val POLL_TIMEOUT_US = 2_000L
    }

    private data class Source(val format: MediaFormat, val mime: String,
        val targetPtsUs: Long, val rotation: Int)

    private class DecodeFailure(val reason: String) : RuntimeException(reason)

    fun decode(uri: String, width: Int, height: Int, onStage: (String) -> Unit): Bitmap? {
        val deadlineMs = SystemClock.elapsedRealtime() + TOTAL_BUDGET_MS
        val extractor = MediaExtractor()
        try {
            onStage("extract")
            val source = openSource(extractor, uri)
            val candidates = decoderCandidates(source)
            if (candidates.isEmpty()) {
                Log.w(ThumbnailDecoderProtocol.TAG, "no supported YUV image decoder for ${source.mime}")
                return null
            }
            for (name in candidates) {
                if (SystemClock.elapsedRealtime() >= deadlineMs || Thread.currentThread().isInterrupted) break
                onStage("seek")
                extractor.seekTo(source.targetPtsUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                require(extractor.sampleTrackIndex >= 0 && extractor.sampleTime <= source.targetPtsUs) {
                    "no sync sample before video midpoint"
                }
                try {
                    // Only an exception may try the compatibility decoder.
                    // A deadline or genuine EOS ends the request, not another pass.
                    return decodeAttempt(extractor, source, name, width, height, deadlineMs, onStage)
                } catch (error: Exception) {
                    val reason = if (error is DecodeFailure) error.reason else error.javaClass.simpleName
                    Log.w(ThumbnailDecoderProtocol.TAG,
                        "midpoint decoder=$name failed ($reason)")
                }
            }
        } catch (error: Exception) {
            Log.w(ThumbnailDecoderProtocol.TAG,
                "midpoint extraction failed (${error.javaClass.simpleName})")
        } finally {
            try { extractor.release() } catch (_: RuntimeException) { }
        }
        return null
    }

    private fun openSource(extractor: MediaExtractor, uri: String): Source {
        extractor.setDataSource(context, Uri.parse(uri), null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw IllegalArgumentException("no video track")
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("no MIME")
        val rotation = ((format.integerOrNull(MediaFormat.KEY_ROTATION) ?: 0) % 360 + 360) % 360
        require(rotation % 90 == 0) { "unsupported video rotation" }
        extractor.selectTrack(track)
        val firstPtsUs = extractor.sampleTime
        require(extractor.sampleTrackIndex == track) { "no usable starting timestamp" }
        val durationUs = format.longOrNull(MediaFormat.KEY_DURATION)
            ?: throw IllegalArgumentException("video duration is unavailable")
        val targetPtsUs = ThumbnailMidpoint.targetPtsUs(firstPtsUs, durationUs)
            ?: throw IllegalArgumentException("video midpoint is unavailable")
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        // Rotate only the final small RGB image, not full-size decoder output.
        format.setInteger(MediaFormat.KEY_ROTATION, 0)
        return Source(format, mime, targetPtsUs, rotation)
    }

    private fun decoderCandidates(source: Source): List<String> {
        val supported = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asSequence()
            .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(source.mime, true) } }
            .filter { Build.VERSION.SDK_INT < 29 || !it.isAlias }
            .filter { info ->
                try {
                    val capabilities = info.getCapabilitiesForType(source.mime)
                    capabilities.colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) &&
                        capabilities.isFormatSupported(source.format)
                } catch (_: RuntimeException) { false }
            }
            .sortedBy { info ->
                when {
                    isSoftware(info) -> 2
                    Build.VERSION.SDK_INT >= 29 && info.isHardwareAccelerated -> 0
                    else -> 1
                }
            }
            .toList()
        val preferred = supported.firstOrNull() ?: return emptyList()
        // Two hardware entries must not crowd out the software image-output
        // fallback when a vendor component cannot actually expose getOutputImage.
        val fallback = supported.firstOrNull { it.name != preferred.name && isSoftware(it) }
            ?: supported.firstOrNull { it.name != preferred.name }
        return listOfNotNull(preferred, fallback).map { it.name }
    }

    private fun isSoftware(info: MediaCodecInfo): Boolean = if (Build.VERSION.SDK_INT >= 29) {
        info.isSoftwareOnly
    } else {
        info.name.startsWith("c2.android.", true) || info.name.startsWith("OMX.google.", true)
    }

    private fun decodeAttempt(extractor: MediaExtractor, source: Source, decoderName: String,
        width: Int, height: Int, deadlineMs: Long, onStage: (String) -> Unit): Bitmap? {
        onStage("start")
        val codec = MediaCodec.createByCodecName(decoderName)
        var started = false
        try {
            codec.configure(source.format, null, null, 0)
            codec.start()
            started = true
            onStage("decode")
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var colors = YuvColors.from(source.format, source.format)
            while (SystemClock.elapsedRealtime() < deadlineMs && !Thread.currentThread().isInterrupted) {
                // Feed and drain in the same loop. No frame count or timestamp
                // window can manufacture EOS before a long GOP reaches midpoint.
                val outputIndex = codec.dequeueOutputBuffer(info, if (inputEnded) POLL_TIMEOUT_US else 0L)
                if (outputIndex >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                            ThumbnailMidpoint.hasReachedTarget(source.targetPtsUs, info.presentationTimeUs)) {
                            val image = codec.getOutputImage(outputIndex)
                                ?: throw DecodeFailure("noOutputImage")
                            Log.d(ThumbnailDecoderProtocol.TAG,
                                "midpoint decoder=$decoderName targetPtsUs=${source.targetPtsUs} " +
                                    "outputPtsUs=${info.presentationTimeUs}")
                            return image.use { thumbnail(it, colors, width, height, source.rotation) }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.w(ThumbnailDecoderProtocol.TAG, "midpoint unavailable before source EOS")
                            return null
                        }
                    } finally {
                        try { codec.releaseOutputBuffer(outputIndex, false) } catch (_: RuntimeException) { }
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    colors = YuvColors.from(codec.outputFormat, source.format)
                }
                if (inputEnded) continue
                // When output is ready, keep draining without an input wait per frame.
                val inputIndex = codec.dequeueInputBuffer(if (outputIndex >= 0) 0L else POLL_TIMEOUT_US)
                if (inputIndex < 0) continue
                val input = codec.getInputBuffer(inputIndex)
                    ?: throw DecodeFailure("noInputBuffer")
                input.clear()
                val ptsUs = extractor.sampleTime
                if (extractor.sampleTrackIndex >= 0) {
                    require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                        "encrypted video sample"
                    }
                }
                val size = if (extractor.sampleTrackIndex < 0) -1 else extractor.readSampleData(input, 0)
                if (size < 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputEnded = true
                } else {
                    codec.queueInputBuffer(inputIndex, 0, size, ptsUs, 0)
                    extractor.advance()
                }
            }
            Log.w(ThumbnailDecoderProtocol.TAG, "midpoint decoder=$decoderName deadline or interruption")
            return null
        } finally {
            // The process deadline also covers native configure/start/release calls.
            if (started) try { codec.stop() } catch (_: RuntimeException) { }
            try { codec.release() } catch (_: RuntimeException) { }
        }
    }

    /** Convert just the selected output; intermediate frames never allocate RGB pixels. */
    private fun thumbnail(image: Image, colors: YuvColors, requestedWidth: Int,
        requestedHeight: Int, rotation: Int): Bitmap {
        require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3) {
            "unsupported decoder image format"
        }
        val crop = image.cropRect
        require(crop.left >= 0 && crop.top >= 0 && crop.right <= image.width &&
            crop.bottom <= image.height && crop.width() > 0 && crop.height() > 0) {
            "invalid decoder crop"
        }
        val sourceWidth = crop.width()
        val sourceHeight = crop.height()
        val rotatedWidth = if (rotation == 90 || rotation == 270) sourceHeight else sourceWidth
        val rotatedHeight = if (rotation == 90 || rotation == 270) sourceWidth else sourceHeight
        val ratio = minOf(requestedWidth.toFloat() / rotatedWidth,
            requestedHeight.toFloat() / rotatedHeight, 1f)
        val outWidth = (rotatedWidth * ratio).roundToInt().coerceAtLeast(1)
        val outHeight = (rotatedHeight * ratio).roundToInt().coerceAtLeast(1)
        val pixels = IntArray(outWidth * outHeight)
        val yPlane = Plane(image.planes[0])
        val uPlane = Plane(image.planes[1])
        val vPlane = Plane(image.planes[2])
        var pixel = 0
        for (y in 0 until outHeight) {
            val ry = ((2L * y + 1) * rotatedHeight / (2L * outHeight)).toInt()
            for (x in 0 until outWidth) {
                val rx = ((2L * x + 1) * rotatedWidth / (2L * outWidth)).toInt()
                val sx: Int
                val sy: Int
                when (rotation) {
                    90 -> { sx = ry; sy = sourceHeight - 1 - rx }
                    180 -> { sx = sourceWidth - 1 - rx; sy = sourceHeight - 1 - ry }
                    270 -> { sx = sourceWidth - 1 - ry; sy = rx }
                    else -> { sx = rx; sy = ry }
                }
                val ix = crop.left + sx
                val iy = crop.top + sy
                pixels[pixel++] = colors.argb(yPlane.sample(ix, iy),
                    uPlane.sample(ix / 2, iy / 2), vPlane.sample(ix / 2, iy / 2))
            }
        }
        return Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
    }

    private class Plane(plane: Image.Plane) {
        private val buffer: ByteBuffer = plane.buffer
        private val start = buffer.position()
        private val rowStride = plane.rowStride
        private val pixelStride = plane.pixelStride

        init { require(rowStride > 0 && pixelStride > 0) { "invalid image strides" } }

        fun sample(x: Int, y: Int): Int {
            val index = start.toLong() + y.toLong() * rowStride + x.toLong() * pixelStride
            require(index >= start && index < buffer.limit()) { "image plane is too short" }
            return buffer.get(index.toInt()).toInt() and 0xff
        }
    }

    /** SDR BT.601/709 conversion, with full and video-range coefficients. */
    private class YuvColors(private val yOffset: Int, private val yScale: Int,
        private val redV: Int, private val greenU: Int, private val greenV: Int,
        private val blueU: Int) {
        fun argb(y: Int, u: Int, v: Int): Int {
            val yy = (y - yOffset).coerceAtLeast(0) * yScale
            val uu = u - 128
            val vv = v - 128
            val r = ((yy + redV * vv + 128) shr 8).coerceIn(0, 255)
            val g = ((yy - greenU * uu - greenV * vv + 128) shr 8).coerceIn(0, 255)
            val b = ((yy + blueU * uu + 128) shr 8).coerceIn(0, 255)
            return (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }

        companion object {
            fun from(output: MediaFormat, source: MediaFormat): YuvColors {
                fun aspect(key: String): Int? = output.integerOrNull(key)?.takeIf { it != 0 }
                    ?: source.integerOrNull(key)?.takeIf { it != 0 }
                val transfer = aspect(MediaFormat.KEY_COLOR_TRANSFER)
                require(transfer != MediaFormat.COLOR_TRANSFER_ST2084 &&
                    transfer != MediaFormat.COLOR_TRANSFER_HLG) { "HDR output is not supported" }
                val standard = aspect(MediaFormat.KEY_COLOR_STANDARD)
                val hd = (source.integerOrNull(MediaFormat.KEY_WIDTH) ?: 0) >= 1280 ||
                    (source.integerOrNull(MediaFormat.KEY_HEIGHT) ?: 0) >= 720
                // MediaFormat also carries AOSP ColorUtils extended standards:
                // primaries + matrix * 7, based at 64 (or * 256 at 0x10000 for
                // vendor aspects). For example, 85 is unspecified primaries with
                // a BT.601 matrix, a normal output for BT.470BG video metadata.
                val matrix = when (standard) {
                    null, 0 -> 0
                    MediaFormat.COLOR_STANDARD_BT709, 3 -> 1
                    MediaFormat.COLOR_STANDARD_BT601_NTSC,
                    MediaFormat.COLOR_STANDARD_BT601_PAL, 10 -> 3
                    in 64..112 -> (standard - 64) / 7
                    in 0x10000 until 0x20000 -> (standard - 0x10000) / 256
                    else -> throw DecodeFailure("colorStandard:$standard")
                }
                val bt709 = when (matrix) {
                    0 -> hd // Unspecified SDR follows the usual HD/SD matrix convention.
                    1 -> true
                    3 -> false
                    else -> throw DecodeFailure("colorMatrix:$matrix")
                }
                val range = aspect(MediaFormat.KEY_COLOR_RANGE)
                val full = when (range) {
                    MediaFormat.COLOR_RANGE_FULL -> true
                    MediaFormat.COLOR_RANGE_LIMITED, null, 0 -> false
                    else -> throw DecodeFailure("colorRange:$range")
                }
                return if (full) {
                    if (bt709) YuvColors(0, 256, 403, 48, 120, 475)
                    else YuvColors(0, 256, 359, 88, 183, 454)
                } else {
                    if (bt709) YuvColors(16, 298, 459, 55, 136, 541)
                    else YuvColors(16, 298, 409, 100, 208, 516)
                }
            }
        }
    }
}

private fun MediaFormat.integerOrNull(key: String): Int? =
    if (containsKey(key)) getInteger(key) else null

private fun MediaFormat.longOrNull(key: String): Long? =
    if (containsKey(key)) getLong(key) else null

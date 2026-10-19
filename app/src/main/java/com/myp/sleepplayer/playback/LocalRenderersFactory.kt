package com.myp.sleepplayer.playback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** Keeps Media3's normal renderer and codec selection, with bounded exact-seek input pruning. */
@UnstableApi
internal class LocalRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    @Volatile var allowAudioFirst = false

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        out.add(SeekVideoRenderer(context, codecAdapterFactory, mediaCodecSelector,
            allowedVideoJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener,
            { allowAudioFirst }))
    }
}

@UnstableApi
private class SeekVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    codecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    private val allowAudioFirst: () -> Boolean
) : MediaCodecVideoRenderer(context, codecAdapterFactory, codecSelector, allowedJoiningTimeMs,
    enableDecoderFallback, eventHandler, eventListener, 50) {
    private var isAvc = false
    private val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var periodDurationUs = C.TIME_UNSET
    private var seekStartedMs = 0L
    private var skippedPictures = 0
    private var hasRenderedInStream = false

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        seekStartedMs = SystemClock.elapsedRealtime()
        skippedPictures = 0
        // Media3's bounded joining window lets audio/the clock start while video catches up.
        // A paused seek and a new media stream must still wait for their exact first frame.
        super.onPositionReset(positionUs, joining || (hasRenderedInStream && allowAudioFirst()))
    }

    override fun renderOutputBufferV21(
        codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long, releaseTimeNs: Long
    ) {
        super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs)
        hasRenderedInStream = true
        if (seekStartedMs != 0L) {
            if (debug) Log.d("NightingaleSeek",
                "videoReadyMs=${SystemClock.elapsedRealtime() - seekStartedMs} " +
                    "skipped=$skippedPictures targetUs=${lastResetPositionUs - outputStreamOffsetUs} " +
                    "frameUs=$presentationTimeUs")
            seekStartedMs = 0L
        }
    }

    override fun onInputFormatChanged(formatHolder: FormatHolder) =
        super.onInputFormatChanged(formatHolder).also {
            isAvc = formatHolder.format?.sampleMimeType == MimeTypes.VIDEO_H264
        }

    override fun onStreamChanged(
        formats: Array<Format>, startPositionUs: Long, offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId
    ) {
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
        hasRenderedInStream = false
        periodDurationUs = if (timeline.isEmpty) C.TIME_UNSET else
            timeline.getPeriodByUid(mediaPeriodId.periodUid, Timeline.Period()).durationUs
    }

    override fun shouldSkipDecoderInputBuffer(buffer: DecoderInputBuffer): Boolean {
        if (super.shouldSkipDecoderInputBuffer(buffer)) return true
        // Keep the target neighbourhood, terminal pictures, encrypted and unsupported samples.
        if (!isAvc || buffer.isEncrypted || buffer.isKeyFrame || buffer.isLastSample ||
            hasReadStreamToEnd() || periodDurationUs == C.TIME_UNSET ||
            buffer.timeUs >= lastResetPositionUs - 1_000_000L ||
            periodDurationUs - (buffer.timeUs - outputStreamOffsetUs) <= 1_000_000L) return false
        val data = buffer.data ?: return false
        return H264SampleDependencies.isNonReferencePicture(data, data.position()).also {
            if (it) skippedPictures++
        }
    }
}

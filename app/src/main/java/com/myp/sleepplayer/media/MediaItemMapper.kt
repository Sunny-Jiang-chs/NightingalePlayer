package com.myp.sleepplayer.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes

internal object MediaItemMapper {
    fun map(entry: MediaEntry): MediaItem {
        val subtitleConfigurations = entry.subtitles.map { subtitle ->
            val suffix = subtitleSuffix(entry.name, subtitle.name)
            MediaItem.SubtitleConfiguration.Builder(subtitle.uri)
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(suffix.takeIf { it.length in 2..3 })
                .setLabel(if (suffix.isEmpty()) "自动字幕" else suffix)
                .setSelectionFlags(
                    if (isDefaultSubtitle(entry.name, subtitle.name)) C.SELECTION_FLAG_DEFAULT else 0
                )
                .build()
        }
        val mimeType = when (entry.type) {
            MediaType.MP4 -> MimeTypes.VIDEO_MP4
            MediaType.MP3 -> MimeTypes.AUDIO_MPEG
            MediaType.WAV -> MimeTypes.AUDIO_WAV
        }
        return MediaItem.Builder()
            .setUri(entry.uri)
            .setMimeType(mimeType)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.name).build())
            .setSubtitleConfigurations(subtitleConfigurations)
            .build()
    }
}

package com.myp.sleepplayer.media

import android.net.Uri
import java.util.Locale

internal enum class MediaType {
    MP4,
    MP3,
    WAV
}

internal fun mediaStem(name: String): String =
    name.substringBeforeLast('.', name).lowercase(Locale.ROOT)

internal fun mediaExtension(name: String): String =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT)

internal fun isExtension(name: String?, extension: String): Boolean =
    name?.substringAfterLast('.', "")?.equals(extension, ignoreCase = true) == true

internal fun isSupportedMedia(name: String?): Boolean =
    listOf("mp4", "mp3", "wav").any { isExtension(name, it) }

internal fun isSubtitleFile(name: String?): Boolean =
    isExtension(name, "vtt") || isExtension(name, "lrc")

internal fun mediaType(name: String): MediaType = when {
    isExtension(name, "mp3") -> MediaType.MP3
    isExtension(name, "wav") -> MediaType.WAV
    else -> MediaType.MP4
}

internal fun subtitleSuffix(videoName: String, subtitleName: String): String {
    val videoStem = mediaStem(videoName)
    val subtitleStem = mediaStem(subtitleName)
    val rawSuffix = if (subtitleStem.startsWith("$videoStem.")) {
        subtitleStem.substring(videoStem.length + 1)
    } else {
        ""
    }
    val extension = mediaExtension(videoName)
    return when {
        rawSuffix.equals(extension, ignoreCase = true) -> ""
        rawSuffix.startsWith("$extension.", ignoreCase = true) ->
            rawSuffix.substring(extension.length + 1)
        else -> rawSuffix
    }
}

internal fun isDefaultSubtitle(videoName: String, subtitleName: String): Boolean {
    val videoStem = mediaStem(videoName)
    val subtitleStem = mediaStem(subtitleName)
    val extension = mediaExtension(videoName)
    return subtitleStem == videoStem || subtitleStem == "$videoStem.$extension"
}

internal data class SubtitleFile(val name: String, val uri: Uri) {
    fun isDefaultFor(videoName: String): Boolean = isDefaultSubtitle(videoName, name)
}

internal data class MediaEntry(
    val name: String,
    val uri: Uri,
    val type: MediaType,
    val subtitles: List<SubtitleFile>
) {
    val isVideo: Boolean
        get() = type == MediaType.MP4
}

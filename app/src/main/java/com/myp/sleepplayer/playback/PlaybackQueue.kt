package com.myp.sleepplayer.playback

internal enum class PlaybackMode(val repeatMode: Int, val label: String) {
    SEQUENTIAL(0, "顺序播放"),
    REPEAT_ALL(2, "循环列表");

    companion object {
        fun fromRepeatMode(value: Int): PlaybackMode =
            if (value == 1 || value == REPEAT_ALL.repeatMode) REPEAT_ALL else SEQUENTIAL
    }
}

/** A null selection follows the directory; a saved selection retains its explicit order. */
internal object PlaybackQueue {
    fun resolve(available: List<String>, selected: List<String>?): List<String> {
        val availableSet = available.toSet()
        return selected?.distinct()?.filter { it in availableSet } ?: available.distinct()
    }

    fun updateSelection(current: List<String>, selected: List<String>): List<String> {
        val selectedSet = selected.toSet()
        return (current.filter { it in selectedSet } + selected).distinct()
    }

    fun move(items: List<String>, from: Int, to: Int): List<String> {
        if (from !in items.indices || to !in items.indices) return items
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }
}

internal data class PlaylistResume(val index: Int, val positionMs: Long, val playWhenReady: Boolean)

internal fun resolvePlaylistResume(
    uris: List<String>,
    currentUri: String?,
    currentPositionMs: Long,
    currentPlaying: Boolean,
    savedState: PlaybackSnapshot
): PlaylistResume {
    val resumeUri = currentUri ?: savedState.mediaUri
    val index = uris.indexOf(resumeUri)
    if (index < 0) return PlaylistResume(0, 0L, false)
    return PlaylistResume(
        index,
        (if (currentUri != null) currentPositionMs else savedState.positionMs).coerceAtLeast(0L),
        if (currentUri != null) currentPlaying else savedState.playWhenReady
    )
}

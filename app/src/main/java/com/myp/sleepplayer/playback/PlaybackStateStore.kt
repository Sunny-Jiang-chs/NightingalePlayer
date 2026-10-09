package com.myp.sleepplayer.playback

import android.content.SharedPreferences

internal data class PlaybackSnapshot(
    val mediaUri: String?,
    val positionMs: Long,
    val playWhenReady: Boolean
)

internal class PlaybackStateStore(private val preferences: SharedPreferences) {
    fun read(): PlaybackSnapshot = PlaybackSnapshot(
        mediaUri = preferences.getString(LAST_MEDIA_URI, null),
        positionMs = preferences.getLong(LAST_MEDIA_POSITION, 0L),
        playWhenReady = preferences.getBoolean(LAST_MEDIA_PLAYING, false)
    )

    fun write(snapshot: PlaybackSnapshot) {
        val uri = snapshot.mediaUri ?: return
        preferences.edit()
            .putString(LAST_MEDIA_URI, uri)
            .putLong(LAST_MEDIA_POSITION, snapshot.positionMs.coerceAtLeast(0L))
            .putBoolean(LAST_MEDIA_PLAYING, snapshot.playWhenReady)
            .apply()
    }

    private companion object {
        const val LAST_MEDIA_URI = "last_media_uri"
        const val LAST_MEDIA_POSITION = "last_media_position"
        const val LAST_MEDIA_PLAYING = "last_media_playing"
    }
}

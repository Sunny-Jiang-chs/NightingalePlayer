package com.myp.sleepplayer.playback

import android.content.SharedPreferences

internal data class PlaybackControlSettings(
    val volume: Float,
    val muted: Boolean,
    val speed: Float,
    val gainDb: Int,
    val resizeMode: Int
)

internal class PlaybackControlStore(private val preferences: SharedPreferences) {
    fun read(defaultResizeMode: Int): PlaybackControlSettings = PlaybackControlSettings(
        volume = preferences.getFloat(KEY_VOLUME, 1f).coerceIn(0f, 1f),
        muted = preferences.getBoolean(KEY_MUTED, false),
        speed = preferences.getFloat(KEY_SPEED, 1f).coerceIn(0.5f, 2f),
        gainDb = preferences.getInt(KEY_GAIN_DB, 0).coerceIn(0, 12),
        resizeMode = preferences.getInt(KEY_RESIZE_MODE, defaultResizeMode)
    )

    fun gainDb(): Int = preferences.getInt(KEY_GAIN_DB, 0).coerceIn(0, 12)

    fun saveSpeed(speed: Float) {
        preferences.edit().putFloat(KEY_SPEED, speed).apply()
    }

    fun saveGainDb(gainDb: Int) {
        preferences.edit().putInt(KEY_GAIN_DB, gainDb.coerceIn(0, 12)).apply()
    }

    fun saveResizeMode(mode: Int) {
        preferences.edit().putInt(KEY_RESIZE_MODE, mode).apply()
    }

    fun save(settings: PlaybackControlSettings) {
        preferences.edit()
            .putFloat(KEY_VOLUME, settings.volume)
            .putBoolean(KEY_MUTED, settings.muted)
            .putFloat(KEY_SPEED, settings.speed)
            .putInt(KEY_GAIN_DB, settings.gainDb.coerceIn(0, 12))
            .putInt(KEY_RESIZE_MODE, settings.resizeMode)
            .apply()
    }

    private companion object {
        const val KEY_VOLUME = "volume"
        const val KEY_MUTED = "muted"
        const val KEY_SPEED = "speed"
        const val KEY_GAIN_DB = "gain_db"
        const val KEY_RESIZE_MODE = "resize_mode"
    }
}

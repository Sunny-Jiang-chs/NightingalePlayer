package com.myp.sleepplayer.playback

import android.content.SharedPreferences

internal data class SubtitleSettings(
    val overlayEnabled: Boolean,
    val sizeSp: Float,
    val color: Int,
    val bottomDp: Int
)

internal class SubtitlePreferencesStore(private val preferences: SharedPreferences) {
    fun read(defaultColor: Int): SubtitleSettings = SubtitleSettings(
        overlayEnabled = preferences.getBoolean(KEY_OVERLAY_ENABLED, false),
        sizeSp = preferences.getFloat(KEY_SIZE, 20f).coerceIn(12f, 36f),
        color = preferences.getInt(KEY_COLOR, defaultColor),
        bottomDp = preferences.getInt(KEY_BOTTOM, 42).coerceIn(8, 180)
    )

    fun setOverlayEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
    }

    fun snapSeekToCueStarts(): Boolean = preferences.getBoolean(KEY_SNAP_SEEK_TO_CUE_STARTS, false)

    fun setSnapSeekToCueStarts(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SNAP_SEEK_TO_CUE_STARTS, enabled).apply()
    }

    fun save(settings: SubtitleSettings) {
        preferences.edit()
            .putBoolean(KEY_OVERLAY_ENABLED, settings.overlayEnabled)
            .putFloat(KEY_SIZE, settings.sizeSp)
            .putInt(KEY_COLOR, settings.color)
            .putInt(KEY_BOTTOM, settings.bottomDp)
            .apply()
    }

    private companion object {
        const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        const val KEY_SIZE = "subtitle_size"
        const val KEY_COLOR = "subtitle_color"
        const val KEY_BOTTOM = "subtitle_bottom"
        const val KEY_SNAP_SEEK_TO_CUE_STARTS = "snap_seek_to_cue_starts"
    }
}

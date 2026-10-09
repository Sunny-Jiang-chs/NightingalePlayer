package com.myp.sleepplayer.playback

import android.content.SharedPreferences

internal data class SubtitleSettings(
    val overlayEnabled: Boolean,
    val sizeSp: Float,
    val color: Int,
    val backgroundAlpha: Int,
    val bottomDp: Int
)

internal class SubtitlePreferencesStore(private val preferences: SharedPreferences) {
    fun read(defaultColor: Int): SubtitleSettings = SubtitleSettings(
        overlayEnabled = preferences.getBoolean(KEY_OVERLAY_ENABLED, false),
        sizeSp = preferences.getFloat(KEY_SIZE, 20f).coerceIn(12f, 36f),
        color = preferences.getInt(KEY_COLOR, defaultColor),
        backgroundAlpha = preferences.getInt(KEY_BACKGROUND_ALPHA, 150).coerceIn(0, 255),
        bottomDp = preferences.getInt(KEY_BOTTOM, 42).coerceIn(8, 180)
    )

    fun setOverlayEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
    }

    fun save(settings: SubtitleSettings) {
        preferences.edit()
            .putBoolean(KEY_OVERLAY_ENABLED, settings.overlayEnabled)
            .putFloat(KEY_SIZE, settings.sizeSp)
            .putInt(KEY_COLOR, settings.color)
            .putInt(KEY_BACKGROUND_ALPHA, settings.backgroundAlpha)
            .putInt(KEY_BOTTOM, settings.bottomDp)
            .apply()
    }

    private companion object {
        const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        const val KEY_SIZE = "subtitle_size"
        const val KEY_COLOR = "subtitle_color"
        const val KEY_BACKGROUND_ALPHA = "subtitle_background_alpha"
        const val KEY_BOTTOM = "subtitle_bottom"
    }
}

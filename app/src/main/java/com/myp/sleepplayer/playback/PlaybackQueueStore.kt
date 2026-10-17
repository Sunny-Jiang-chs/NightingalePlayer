package com.myp.sleepplayer.playback

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException

internal class PlaybackQueueStore(private val preferences: SharedPreferences) {
    fun mode(): PlaybackMode = PlaybackMode.fromRepeatMode(preferences.getInt(KEY_MODE, 0))

    fun saveMode(mode: PlaybackMode) {
        preferences.edit().putInt(KEY_MODE, mode.repeatMode).apply()
    }

    fun selection(source: String): List<String>? {
        val value = preferences.getString("$KEY_SELECTION$source", null) ?: return null
        return try {
            val array = JSONArray(value)
            (0 until array.length()).map { array.getString(it) }.distinct()
        } catch (_: JSONException) {
            null
        }
    }

    fun saveSelection(source: String, uris: List<String>?) {
        val editor = preferences.edit()
        if (uris == null) editor.remove("$KEY_SELECTION$source")
        else editor.putString("$KEY_SELECTION$source", JSONArray(uris.distinct()).toString())
        editor.apply()
    }

    private companion object {
        const val KEY_MODE = "repeat_mode"
        const val KEY_SELECTION = "selection:"
    }
}

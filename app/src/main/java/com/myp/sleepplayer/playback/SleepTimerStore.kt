package com.myp.sleepplayer.playback

import android.content.SharedPreferences

internal class SleepTimerStore(private val preferences: SharedPreferences) {
    fun deadlineMs(): Long = preferences.getLong(KEY_DEADLINE, 0L)

    fun setDeadline(deadlineMs: Long) {
        preferences.edit().putLong(KEY_DEADLINE, deadlineMs).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_DEADLINE).apply()
    }

    private companion object {
        const val KEY_DEADLINE = "deadline_ms"
    }
}

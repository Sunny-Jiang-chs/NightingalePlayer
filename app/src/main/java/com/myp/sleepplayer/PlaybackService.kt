package com.myp.sleepplayer

import android.media.audiofx.LoudnessEnhancer
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@UnstableApi
class PlaybackService : MediaSessionService() {
    companion object {
        const val ACTION_SET_TIMER = "com.myp.sleepplayer.action.SET_TIMER"
        const val EXTRA_MINUTES = "minutes"
        const val ACTION_SET_GAIN = "com.myp.sleepplayer.action.SET_GAIN"
        const val EXTRA_GAIN_DB = "gain_db"
        private const val TIMER_PREFS = "sleep_timer"
        private const val TIMER_DEADLINE = "deadline_ms"
        private const val CONTROL_PREFS = "playback_controls"
        private const val GAIN_DB = "gain_db"
        private const val TAG = "SleepVideoPlayer"
    }

    private val timerHandler = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var gainDb = 0

    private val timerRunnable = Runnable {
        getSharedPreferences(TIMER_PREFS, MODE_PRIVATE)
            .edit()
            .remove(TIMER_DEADLINE)
            .apply()
        player.pause()
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        gainDb = getSharedPreferences(CONTROL_PREFS, MODE_PRIVATE)
            .getInt(GAIN_DB, 0)
            .coerceIn(0, 12)
        player = ExoPlayer.Builder(this)
            // Exact seek decodes forward from the previous keyframe instead of
            // snapping to a distant sync point in low-frame-rate files.
            .setSeekParameters(SeekParameters.EXACT)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachLoudnessEnhancer(audioSessionId)
            }
        })
        mediaSession = MediaSession.Builder(this, player).build()
        restoreTimer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SET_TIMER) {
            setTimer(intent.getIntExtra(EXTRA_MINUTES, 0))
        }
        if (intent?.action == ACTION_SET_GAIN) {
            setGain(intent.getIntExtra(EXTRA_GAIN_DB, 0))
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    private fun setTimer(minutes: Int) {
        timerHandler.removeCallbacks(timerRunnable)
        val prefs = getSharedPreferences(TIMER_PREFS, MODE_PRIVATE)
        if (minutes <= 0) {
            prefs.edit().remove(TIMER_DEADLINE).apply()
            return
        }

        val deadline = System.currentTimeMillis() + minutes * 60_000L
        prefs.edit().putLong(TIMER_DEADLINE, deadline).apply()
        timerHandler.postDelayed(timerRunnable, minutes * 60_000L)
    }

    private fun restoreTimer() {
        val deadline = getSharedPreferences(TIMER_PREFS, MODE_PRIVATE)
            .getLong(TIMER_DEADLINE, 0L)
        if (deadline == 0L) return
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0L) {
            timerRunnable.run()
        } else {
            timerHandler.postDelayed(timerRunnable, remaining)
        }
    }

    private fun setGain(db: Int) {
        gainDb = db.coerceIn(0, 12)
        getSharedPreferences(CONTROL_PREFS, MODE_PRIVATE)
            .edit()
            .putInt(GAIN_DB, gainDb)
            .apply()
        if (::player.isInitialized) {
            val audioSessionId = player.audioSessionId
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                attachLoudnessEnhancer(audioSessionId)
            }
        }
        applyGain()
    }

    private fun attachLoudnessEnhancer(audioSessionId: Int) {
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || gainDb == 0) return
        try {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(gainDb * 100)
                enabled = true
            }
            Log.i(TAG, "Audio gain enabled: +${gainDb} dB, session=$audioSessionId")
        } catch (error: RuntimeException) {
            Log.w(TAG, "Audio gain effect is unavailable", error)
        }
    }

    private fun applyGain() {
        val effect = loudnessEnhancer ?: return
        try {
            effect.setTargetGain(gainDb * 100)
            effect.enabled = gainDb > 0
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to update audio gain", error)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.release()
        player.release()
        mediaSession = null
        super.onDestroy()
    }
}

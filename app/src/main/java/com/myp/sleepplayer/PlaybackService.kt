package com.myp.sleepplayer

import android.media.audiofx.LoudnessEnhancer
import android.graphics.Color
import android.graphics.PixelFormat
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.text.Cue
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
        const val ACTION_SET_OVERLAY = "com.myp.sleepplayer.action.SET_OVERLAY"
        const val EXTRA_OVERLAY_ENABLED = "overlay_enabled"
        const val ACTION_SET_APP_VISIBLE = "com.myp.sleepplayer.action.SET_APP_VISIBLE"
        const val EXTRA_APP_VISIBLE = "app_visible"
        const val ACTION_SET_SUBTITLE_STYLE = "com.myp.sleepplayer.action.SET_SUBTITLE_STYLE"
        const val EXTRA_SUBTITLE_SIZE = "subtitle_size"
        const val EXTRA_SUBTITLE_COLOR = "subtitle_color"
        const val EXTRA_SUBTITLE_BACKGROUND_ALPHA = "subtitle_background_alpha"
        const val EXTRA_SUBTITLE_BOTTOM = "subtitle_bottom"
        private const val TIMER_PREFS = "sleep_timer"
        private const val TIMER_DEADLINE = "deadline_ms"
        private const val CONTROL_PREFS = "playback_controls"
        private const val GAIN_DB = "gain_db"
        private const val SUBTITLE_PREFS = "subtitle_preferences"
        private const val OVERLAY_ENABLED = "overlay_enabled"
        private const val SUBTITLE_SIZE = "subtitle_size"
        private const val SUBTITLE_COLOR = "subtitle_color"
        private const val SUBTITLE_BACKGROUND_ALPHA = "subtitle_background_alpha"
        private const val SUBTITLE_BOTTOM = "subtitle_bottom"
        private const val TAG = "SleepVideoPlayer"
    }

    private val timerHandler = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var gainDb = 0
    private var appVisible = true
    private var overlayEnabled = false
    private var currentSubtitleText = ""
    private var subtitleSizeSp = 20f
    private var subtitleColor = Color.WHITE
    private var subtitleBackgroundAlpha = 150
    private var subtitleBottomDp = 42
    private var subtitleOverlay: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

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
        val subtitlePrefs = getSharedPreferences(SUBTITLE_PREFS, MODE_PRIVATE)
        overlayEnabled = subtitlePrefs.getBoolean(OVERLAY_ENABLED, false)
        subtitleSizeSp = subtitlePrefs.getFloat(SUBTITLE_SIZE, 20f).coerceIn(12f, 36f)
        subtitleColor = subtitlePrefs.getInt(SUBTITLE_COLOR, Color.WHITE)
        subtitleBackgroundAlpha = subtitlePrefs.getInt(SUBTITLE_BACKGROUND_ALPHA, 150).coerceIn(0, 255)
        subtitleBottomDp = subtitlePrefs.getInt(SUBTITLE_BOTTOM, 42).coerceIn(8, 180)
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

            override fun onCues(cues: MutableList<Cue>) {
                currentSubtitleText = cues
                    .mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                    .joinToString("\n")
                updateSubtitleOverlay()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateSubtitleOverlay()
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
        if (intent?.action == ACTION_SET_OVERLAY) {
            setOverlayEnabled(intent.getBooleanExtra(EXTRA_OVERLAY_ENABLED, false))
        }
        if (intent?.action == ACTION_SET_APP_VISIBLE) {
            appVisible = intent.getBooleanExtra(EXTRA_APP_VISIBLE, true)
            updateSubtitleOverlay()
        }
        if (intent?.action == ACTION_SET_SUBTITLE_STYLE) {
            updateSubtitleStyle(intent)
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

    private fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        getSharedPreferences(SUBTITLE_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(OVERLAY_ENABLED, enabled)
            .apply()
        updateSubtitleOverlay()
    }

    private fun updateSubtitleStyle(intent: Intent) {
        subtitleSizeSp = intent.getFloatExtra(EXTRA_SUBTITLE_SIZE, subtitleSizeSp).coerceIn(12f, 36f)
        subtitleColor = intent.getIntExtra(EXTRA_SUBTITLE_COLOR, subtitleColor)
        subtitleBackgroundAlpha = intent.getIntExtra(
            EXTRA_SUBTITLE_BACKGROUND_ALPHA,
            subtitleBackgroundAlpha
        ).coerceIn(0, 255)
        subtitleBottomDp = intent.getIntExtra(EXTRA_SUBTITLE_BOTTOM, subtitleBottomDp).coerceIn(8, 180)
        getSharedPreferences(SUBTITLE_PREFS, MODE_PRIVATE).edit()
            .putFloat(SUBTITLE_SIZE, subtitleSizeSp)
            .putInt(SUBTITLE_COLOR, subtitleColor)
            .putInt(SUBTITLE_BACKGROUND_ALPHA, subtitleBackgroundAlpha)
            .putInt(SUBTITLE_BOTTOM, subtitleBottomDp)
            .apply()
        applySubtitleStyle()
        updateSubtitleOverlay()
    }

    private fun canDrawOverlays(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)

    private fun updateSubtitleOverlay() {
        if (!appVisible && overlayEnabled && player.isPlaying && currentSubtitleText.isNotEmpty() && canDrawOverlays()) {
            showSubtitleOverlay()
        } else {
            hideSubtitleOverlay()
        }
    }

    private fun showSubtitleOverlay() {
        val overlay = subtitleOverlay ?: TextView(this).also { view ->
            view.setPadding(dp(12), dp(5), dp(12), dp(5))
            view.gravity = Gravity.CENTER
            view.maxLines = 4
            view.elevation = dp(8).toFloat()
            subtitleOverlay = view
            applySubtitleStyle()
        }
        overlay.text = currentSubtitleText
        val params = overlayParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(subtitleBottomDp)
        }.also { overlayParams = it }
        params.y = dp(subtitleBottomDp)
        try {
            if (overlay.parent == null) {
                windowManager.addView(overlay, params)
            } else {
                windowManager.updateViewLayout(overlay, params)
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to show subtitle overlay", error)
        }
    }

    private fun applySubtitleStyle() {
        subtitleOverlay?.apply {
            textSize = subtitleSizeSp
            setTextColor(subtitleColor)
            setBackgroundColor(Color.argb(subtitleBackgroundAlpha, 0, 0, 0))
            setShadowLayer(dp(3).toFloat(), 0f, dp(2).toFloat(), Color.BLACK)
        }
    }

    private fun hideSubtitleOverlay() {
        val overlay = subtitleOverlay ?: return
        if (overlay.parent != null) {
            try {
                windowManager.removeView(overlay)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to hide subtitle overlay", error)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        hideSubtitleOverlay()
        subtitleOverlay = null
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.release()
        player.release()
        mediaSession = null
        super.onDestroy()
    }
}

package com.myp.sleepplayer

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.view.ScaleGestureDetector
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import kotlin.math.roundToInt
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private fun mediaStem(name: String): String =
    name.substringBeforeLast('.', name).lowercase(Locale.ROOT)

private fun mediaExtension(name: String): String =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT)

private fun subtitleSuffix(videoName: String, subtitleName: String): String {
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

private fun isDefaultSubtitle(videoName: String, subtitleName: String): Boolean {
    val videoStem = mediaStem(videoName)
    val subtitleStem = mediaStem(subtitleName)
    val extension = mediaExtension(videoName)
    return subtitleStem == videoStem || subtitleStem == "$videoStem.$extension"
}

@UnstableApi
class MainActivity : Activity() {
    companion object {
        private const val REQUEST_TREE = 1001
        private const val REQUEST_NOTIFICATIONS = 1002
        private const val PREFS = "library"
        private const val TREE_URI = "tree_uri"
        private const val LAST_MEDIA_URI = "last_media_uri"
        private const val LAST_MEDIA_POSITION = "last_media_position"
        private const val LAST_MEDIA_PLAYING = "last_media_playing"
    }

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var playerView: PlayerView? = null
    private var statusView: TextView? = null
    private var timingView: TextView? = null
    private var libraryContainer: LinearLayout? = null
    private var volumeSeekBar: SeekBar? = null
    private var muteButton: Button? = null
    private var resizeButton: Button? = null
    private var speedButton: Button? = null
    private var gainButton: Button? = null
    private var volumeLabel: TextView? = null
    private var loadedEntries: List<MediaEntry> = emptyList()
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var videoScale = 1f
    private var lastVolume = 1f
    private var isMuted = false
    private var playbackSpeed = 1f
    private var gainDb = 0

    private val controlPrefs by lazy { getSharedPreferences("playback_controls", MODE_PRIVATE) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private val progressTicker = object : Runnable {
        override fun run() {
            val player = controller
            if (player != null) {
                val position = formatTime(player.currentPosition)
                val duration = formatTime(player.duration)
                timingView?.text = "$position / $duration    实际落点随播放器状态更新"
            }
            mainHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastVolume = controlPrefs.getFloat("volume", 1f).coerceIn(0f, 1f)
        isMuted = controlPrefs.getBoolean("muted", false)
        playbackSpeed = controlPrefs.getFloat("speed", 1f).coerceIn(0.5f, 2f)
        gainDb = controlPrefs.getInt("gain_db", 0).coerceIn(0, 12)
        currentResizeMode = controlPrefs.getInt(
            "resize_mode",
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        )
        buildUi()
        connectController()
        requestNotificationPermission()
        loadSavedTree()
        mainHandler.post(progressTicker)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 15, 18))
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "夜莺播放器"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)
        header.addView(actionButton("选择目录") { openTreePicker() })
        header.addView(actionButton("定时") { showTimerMenu(it) })
        root.addView(header)

        statusView = TextView(this).apply {
            text = "请选择媒体目录"
            textSize = 13f
            setTextColor(Color.rgb(171, 181, 196))
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(statusView)

        playerView = PlayerView(this).apply {
            resizeMode = currentResizeMode
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setKeepContentOnPlayerReset(true)
            useController = true
            controllerShowTimeoutMs = 5_000
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(235)
            )
        }
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    videoScale = (videoScale * detector.scaleFactor).coerceIn(1f, 3f)
                    playerView?.scaleX = videoScale
                    playerView?.scaleY = videoScale
                    return true
                }
            })
        playerView?.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            false
        }
        root.addView(playerView)

        val playbackControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        resizeButton = actionButton(resizeLabel()) { showResizeMenu(it) }
        speedButton = actionButton("倍速 ${speedLabel(playbackSpeed)}") { showSpeedMenu(it) }
        gainButton = actionButton(gainLabel()) { showGainMenu(it) }
        playbackControls.addView(resizeButton)
        playbackControls.addView(speedButton)
        playbackControls.addView(gainButton)
        volumeLabel = TextView(this).apply {
            text = "音量"
            textSize = 12f
            setTextColor(Color.rgb(171, 181, 196))
            setPadding(dp(5), 0, dp(2), 0)
            isClickable = true
            setOnClickListener { showSystemVolumeMenu(it) }
        }
        playbackControls.addView(volumeLabel)
        volumeSeekBar = SeekBar(this).apply {
            max = 100
            progress = if (isMuted) 0 else (lastVolume * 100).roundToInt()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val volume = value / 100f
                    if (volume > 0f) {
                        lastVolume = volume
                        isMuted = false
                    } else {
                        isMuted = true
                    }
                    controller?.volume = volume
                    updateMuteButton()
                    persistControlSettings()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        playbackControls.addView(volumeSeekBar)
        muteButton = actionButton(if (isMuted) "取消静音" else "静音") { toggleMute() }
        playbackControls.addView(muteButton)
        root.addView(playbackControls)

        timingView = TextView(this).apply {
            text = "00:00 / --:--"
            textSize = 12f
            setTextColor(Color.rgb(171, 181, 196))
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(timingView)

        val hint = TextView(this).apply {
            text = "精确 seek 已开启；双指可缩放画面，画面菜单可裁剪放大，音量滑杆可单独调节播放器音量。"
            textSize = 12f
            setTextColor(Color.rgb(129, 143, 164))
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(hint)

        val libraryScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        libraryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        libraryScroll.addView(libraryContainer)
        root.addView(libraryScroll)
        setContentView(root)
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                playerView?.player = controller
                controller?.volume = if (isMuted) 0f else lastVolume
                controller?.setPlaybackSpeed(playbackSpeed)
                setGain(gainDb)
                controller?.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updatePlaybackStatus()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlaybackStatus()
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        statusView?.text = "播放失败：${error.errorCodeName}"
                    }
                })
                if (loadedEntries.isNotEmpty()) applyPlaylist()
            } catch (error: Exception) {
                statusView?.text = "播放服务连接失败：${error.message ?: "未知错误"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun openTreePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_TREE)
    }

    private fun loadSavedTree() {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(TREE_URI, null)
        if (saved == null) return
        // Older builds used ".movie" as an example path. Require an explicit
        // directory choice instead of silently reopening that old location.
        if (saved.lowercase(Locale.ROOT).contains("primary%3a.movie")) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(TREE_URI).apply()
            return
        }
        runOnUiThread { scanDocumentTree(Uri.parse(saved)) }
    }

    private fun scanDocumentTree(uri: Uri) {
        statusView?.text = "正在扫描目录..."
        ioExecutor.execute {
            val root = DocumentFile.fromTreeUri(this, uri)
            val entries = root?.let { scanDocumentDirectory(it) }.orEmpty()
            runOnUiThread { finishScan(entries, "授权目录") }
        }
    }

    private fun scanDocumentDirectory(directory: DocumentFile): List<MediaEntry> {
        val children = directory.listFiles().sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }
        val subtitles = children
            .filter { it.isFile && isExtension(it.name, "vtt") }
            .mapNotNull { file -> file.name?.let { SubtitleFile(it, file.uri) } }
        val entries = children
            .filter { it.isFile && isSupportedMedia(it.name) }
            .mapNotNull { video ->
                val name = video.name ?: return@mapNotNull null
                MediaEntry(name, video.uri, mediaMimeType(name), matchSubtitles(name, subtitles))
            }
        return entries + children
            .filter { it.isDirectory }
            .flatMap { scanDocumentDirectory(it) }
    }

    private fun matchSubtitles(videoName: String, subtitles: List<SubtitleFile>): List<SubtitleFile> {
        val stem = mediaStem(videoName)
        return subtitles.filter { subtitle ->
            val subtitleStem = mediaStem(subtitle.name)
            subtitleStem == stem || subtitleStem.startsWith("$stem.")
        }.sortedWith(compareBy<SubtitleFile> { !it.isDefaultFor(videoName) }.thenBy { it.name })
    }

    private fun finishScan(entries: List<MediaEntry>, source: String) {
        loadedEntries = entries.sortedBy { it.name.lowercase(Locale.ROOT) }
        renderLibrary()
        applyPlaylist()
        statusView?.text = if (loadedEntries.isEmpty()) {
            "$source 中没有找到 MP4、MP3 或 WAV"
        } else {
            "已找到 ${loadedEntries.size} 个媒体；外挂字幕会按同名文件自动加载"
        }
    }

    private fun applyPlaylist() {
        val player = controller ?: return
        if (loadedEntries.isEmpty()) return
        val mediaItems = loadedEntries.map { it.toMediaItem() }
        val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        val currentPosition = player.currentPosition
        val currentPlaying = player.playWhenReady
        val savedUri = controlPrefs.getString(LAST_MEDIA_URI, null)
        val savedPosition = controlPrefs.getLong(LAST_MEDIA_POSITION, 0L)
        val savedPlaying = controlPrefs.getBoolean(LAST_MEDIA_PLAYING, false)
        val resumeUri = currentUri ?: savedUri
        val resumeIndex = resumeUri?.let { uri ->
            mediaItems.indexOfFirst { it.localConfiguration?.uri?.toString() == uri }
        } ?: -1
        val resumePosition = if (currentUri != null) currentPosition else savedPosition
        val resumePlaying = if (currentUri != null) currentPlaying else savedPlaying

        player.setMediaItems(mediaItems, true)
        player.prepare()
        if (resumeIndex >= 0) {
            player.seekTo(resumeIndex, resumePosition.coerceAtLeast(0L))
            player.playWhenReady = resumePlaying
        }
    }

    private fun renderLibrary() {
        val container = libraryContainer ?: return
        container.removeAllViews()
        if (loadedEntries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "目录为空"
                textSize = 15f
                setTextColor(Color.rgb(171, 181, 196))
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }
        loadedEntries.forEachIndexed { index, entry ->
            val subtitleHint = if (entry.subtitles.isEmpty()) "" else "  ·  VTT ${entry.subtitles.size}"
            val button = Button(this).apply {
                text = "${index + 1}. ${entry.name}$subtitleHint"
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    controller?.seekToDefaultPosition(index)
                    controller?.play()
                    statusView?.text = "正在播放 ${entry.name}"
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }
            container.addView(button)
        }
    }

    private fun showTimerMenu(anchor: android.view.View) {
        PopupMenu(this, anchor).apply {
            menu.add("关闭定时").setOnMenuItemClickListener { setTimer(0); true }
            menu.add("15 分钟").setOnMenuItemClickListener { setTimer(15); true }
            menu.add("30 分钟").setOnMenuItemClickListener { setTimer(30); true }
            menu.add("60 分钟").setOnMenuItemClickListener { setTimer(60); true }
            menu.add("90 分钟").setOnMenuItemClickListener { setTimer(90); true }
            show()
        }
    }

    private fun showResizeMenu(anchor: android.view.View) {
        PopupMenu(this, anchor).apply {
            menu.add("适配画面").setOnMenuItemClickListener {
                setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                true
            }
            menu.add("裁剪放大").setOnMenuItemClickListener {
                setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
                true
            }
            menu.add("拉伸填充").setOnMenuItemClickListener {
                setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL)
                true
            }
            menu.add("重置双指缩放").setOnMenuItemClickListener {
                resetVideoScale()
                true
            }
            show()
        }
    }

    private fun showSpeedMenu(anchor: android.view.View) {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        PopupMenu(this, anchor).apply {
            speeds.forEach { speed ->
                val label = if (speed == 1f) "正常 1.0x" else "${speed}x"
                menu.add(label).setOnMenuItemClickListener {
                    controller?.setPlaybackSpeed(speed)
                    playbackSpeed = speed
                    speedButton?.text = "倍速 ${speedLabel(speed)}"
                    controlPrefs.edit().putFloat("speed", speed).apply()
                    true
                }
            }
            show()
        }
    }

    private fun showGainMenu(anchor: android.view.View) {
        PopupMenu(this, anchor).apply {
            listOf(0, 6, 12).forEach { db ->
                menu.add(if (db == 0) "原声 0 dB" else "+${db} dB 音频增益")
                    .setOnMenuItemClickListener {
                        setGain(db)
                        true
                    }
            }
            show()
        }
    }

    private fun showSystemVolumeMenu(anchor: android.view.View) {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        PopupMenu(this, anchor).apply {
            menu.add("系统媒体音量：$current/$max")
            menu.add("设为系统最大音量").setOnMenuItemClickListener {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                true
            }
            menu.add("设为系统 75%").setOnMenuItemClickListener {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    (max * 0.75f).roundToInt().coerceAtLeast(1),
                    0
                )
                true
            }
            show()
        }
    }

    private fun setGain(db: Int) {
        gainDb = db.coerceIn(0, 12)
        gainButton?.text = gainLabel()
        controlPrefs.edit().putInt("gain_db", gainDb).apply()
        startService(Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_SET_GAIN
            putExtra(PlaybackService.EXTRA_GAIN_DB, gainDb)
        })
    }

    private fun gainLabel(): String = if (gainDb == 0) "增益 原声" else "增益 +${gainDb}dB"

    private fun setResizeMode(mode: Int) {
        currentResizeMode = mode
        playerView?.resizeMode = mode
        resizeButton?.text = resizeLabel()
        controlPrefs.edit().putInt("resize_mode", mode).apply()
    }

    private fun resizeLabel(): String = when (currentResizeMode) {
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "画面 裁剪"
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "画面 拉伸"
        else -> "画面 适配"
    }

    private fun speedLabel(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"

    private fun resetVideoScale() {
        videoScale = 1f
        playerView?.scaleX = 1f
        playerView?.scaleY = 1f
    }

    private fun toggleMute() {
        if (isMuted || (controller?.volume ?: 0f) <= 0f) {
            isMuted = false
            if (lastVolume <= 0f) lastVolume = 1f
            controller?.volume = lastVolume
            volumeSeekBar?.progress = (lastVolume * 100).roundToInt()
        } else {
            lastVolume = controller?.volume ?: lastVolume
            isMuted = true
            controller?.volume = 0f
            volumeSeekBar?.progress = 0
        }
        updateMuteButton()
        persistControlSettings()
    }

    private fun updateMuteButton() {
        muteButton?.text = if (isMuted) "取消静音" else "静音"
    }

    private fun persistControlSettings() {
        controlPrefs.edit()
            .putFloat("volume", lastVolume)
            .putBoolean("muted", isMuted)
            .putFloat("speed", playbackSpeed)
            .putInt("gain_db", gainDb)
            .apply()
    }

    private fun setTimer(minutes: Int) {
        startService(Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_SET_TIMER
            putExtra(PlaybackService.EXTRA_MINUTES, minutes)
        })
        statusView?.text = if (minutes == 0) "已关闭睡眠定时器" else "睡眠定时器：${minutes} 分钟后暂停"
    }

    private fun updatePlaybackStatus() {
        val player = controller ?: return
        val state = when (player.playbackState) {
            Player.STATE_BUFFERING -> "缓冲中"
            Player.STATE_READY -> if (player.isPlaying) "播放中" else "已暂停"
            Player.STATE_ENDED -> "播放结束"
            else -> "等待播放"
        }
        statusView?.text = state
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(TREE_URI, uri.toString()).apply()
        scanDocumentTree(uri)
    }

    override fun onDestroy() {
        savePlaybackPosition()
        mainHandler.removeCallbacks(progressTicker)
        ioExecutor.shutdownNow()
        controller?.release()
        controllerFuture?.cancel(true)
        super.onDestroy()
    }

    override fun onStop() {
        savePlaybackPosition()
        super.onStop()
    }

    private fun savePlaybackPosition() {
        val player = controller ?: return
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        controlPrefs.edit()
            .putString(LAST_MEDIA_URI, uri)
            .putLong(LAST_MEDIA_POSITION, player.currentPosition.coerceAtLeast(0L))
            .putBoolean(LAST_MEDIA_PLAYING, player.playWhenReady)
            .apply()
    }

    private fun actionButton(label: String, action: (android.view.View) -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setOnClickListener(action)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(6), 0, dp(6), 0)
        }

    private fun isExtension(name: String?, extension: String): Boolean =
        name?.substringAfterLast('.', "")?.equals(extension, ignoreCase = true) == true

    private fun isSupportedMedia(name: String?): Boolean =
        listOf("mp4", "mp3", "wav").any { isExtension(name, it) }

    private fun mediaMimeType(name: String): String = when {
        isExtension(name, "mp3") -> MimeTypes.AUDIO_MPEG
        isExtension(name, "wav") -> MimeTypes.AUDIO_WAV
        else -> MimeTypes.VIDEO_MP4
    }

    private fun formatTime(milliseconds: Long): String {
        if (milliseconds < 0) return "--:--"
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
        } else {
            "%02d:%02d".format(Locale.ROOT, minutes, seconds)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class SubtitleFile(val name: String, val uri: Uri) {
        fun isDefaultFor(videoName: String): Boolean = isDefaultSubtitle(videoName, name)
    }

    private data class MediaEntry(
        val name: String,
        val uri: Uri,
        val mimeType: String,
        val subtitles: List<SubtitleFile>
    ) {
        fun toMediaItem(): MediaItem {
            val subtitleConfigurations = subtitles.map { subtitle ->
                val suffix = subtitleSuffix(name, subtitle.name)
                MediaItem.SubtitleConfiguration.Builder(subtitle.uri)
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(suffix.takeIf { it.length in 2..3 })
                    .setLabel(if (suffix.isEmpty()) "自动字幕" else suffix)
                    .setSelectionFlags(
                        if (isDefaultSubtitle(name, subtitle.name)) C.SELECTION_FLAG_DEFAULT else 0
                    )
                    .build()
            }
            return MediaItem.Builder()
                .setUri(uri)
                .setMimeType(mimeType)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
                .setSubtitleConfigurations(subtitleConfigurations)
                .build()
        }
    }
}

package com.myp.sleepplayer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.util.TypedValue
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.ScaleGestureDetector
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.myp.sleepplayer.media.MediaEntry
import com.myp.sleepplayer.media.MediaScanner
import com.myp.sleepplayer.media.isExtension
import com.myp.sleepplayer.media.isSupportedMedia
import com.myp.sleepplayer.media.isSubtitleFile
import com.myp.sleepplayer.media.mediaType
import com.myp.sleepplayer.playback.PlaybackCoordinator
import com.myp.sleepplayer.playback.PlaybackControlSettings
import com.myp.sleepplayer.playback.PlaybackControlStore
import com.myp.sleepplayer.playback.PlaybackPreferenceFiles
import com.myp.sleepplayer.playback.PlaybackMode
import com.myp.sleepplayer.playback.PlaybackQueueStore
import com.myp.sleepplayer.playback.SavedPlaylist
import com.myp.sleepplayer.playback.PlaybackStateStore
import com.myp.sleepplayer.playback.SleepTimerStore
import com.myp.sleepplayer.playback.SubtitlePreferencesStore
import com.myp.sleepplayer.playback.SubtitleSeekSnapper
import com.myp.sleepplayer.playback.SubtitleSettings
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

private class VerticalVolumeSlider(
    context: Context,
    initialProgress: Int,
    private val onValueChanged: (Int) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private var progress = initialProgress.coerceIn(0, 100)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(92, 103, 116)
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(104, 207, 188)
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    init {
        contentDescription = "垂直调整播放器音量"
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        val centerX = width / 2f
        val top = 10f * density
        val bottom = (height - 10f * density).coerceAtLeast(top + density)
        val fraction = progress / 100f
        val thumbY = bottom - fraction * (bottom - top)
        canvas.drawLine(centerX, top, centerX, bottom, trackPaint)
        canvas.drawLine(centerX, thumbY, centerX, bottom, progressPaint)
        canvas.drawCircle(centerX, thumbY, 7f * density, thumbPaint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = SeekBar::class.java.name
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
            0f,
            100f,
            progress.toFloat()
        )
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id) {
            val value = arguments
                ?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)
                ?.roundToInt()
                ?.coerceIn(0, 100)
                ?: return false
            setValue(value)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.y)
            }
            MotionEvent.ACTION_MOVE -> updateFromTouch(event.y)
            MotionEvent.ACTION_UP -> {
                updateFromTouch(event.y)
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(y: Float) {
        val top = 10f * density
        val bottom = (height - 10f * density).coerceAtLeast(top + density)
        val fraction = ((bottom - y) / (bottom - top)).coerceIn(0f, 1f)
        setValue((fraction * 100).roundToInt())
    }

    private fun setValue(value: Int) {
        val safeValue = value.coerceIn(0, 100)
        if (progress == safeValue) return
        progress = safeValue
        invalidate()
        onValueChanged(safeValue)
    }
}

@UnstableApi
class MainActivity : Activity() {
    companion object {
        private const val LONG_PRESS_SPEED_BOOST_MS = 450L
        private const val VERTICAL_GESTURE_SENSITIVITY = 1f / 3f
        private const val REQUEST_TREE = 1001
        private const val REQUEST_NOTIFICATIONS = 1002
        private const val REQUEST_STORAGE = 1003
        private const val PREFS = "library"
        private const val TREE_URI = "tree_uri"
        private const val DIRECTORY_PATH = "directory_path"
        private const val MAX_TIMER_MINUTES = 24 * 60
        private const val PLAYLIST_UNDO_TIMEOUT_MS = 10_000L
        private const val PLAYLIST_UNDO_MAX_INTERACTIONS = 3
        private const val BUFFERING_INDICATOR_DELAY_MS = 400L
    }

    private val scanExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val thumbnailLoader by lazy { VideoThumbnailLoader(this) }
    private val mediaScanner by lazy {
        MediaScanner(contentResolver, File(cacheDir, "lrc-subtitles"))
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var playerView: PlayerView? = null
    private var rootLayout: LinearLayout? = null
    private var timingView: TextView? = null
    private var libraryContainer: LinearLayout? = null
    private var timerCountdownView: TextView? = null
    private var timerDialog: AlertDialog? = null
    private var playbackButton: ImageButton? = null
    private var speakerButton: ImageButton? = null
    private var volumePopup: PopupWindow? = null
    private var episodesButton: TextView? = null
    private var episodesPopup: FullscreenEpisodesPopup? = null
    private var scrubOverlayView: TextView? = null
    private var subtitleButton: Button? = null
    private var loadedEntries: List<MediaEntry> = emptyList()
    private var activeEntries: List<MediaEntry> = emptyList()
    private var librarySource: String? = null
    private var isLibraryLoading = false
    private var playlistEntries: List<MediaEntry> = emptyList()
    private var directoryPlaybackEntries: List<MediaEntry>? = null
    private data class ClearedPlaylist(val entries: List<MediaEntry>, val resumePlaylist: Boolean, val savedId: String?)
    private var clearedPlaylist: ClearedPlaylist? = null
    private var playlistUndoRow: View? = null
    private var playlistUndoInteractions = 0
    private val playlistUndoTimeout = Runnable { dismissPlaylistUndo(animate = true) }
    private var playlistView: PlaybackQueueView? = null
    private var playlistLoopCheck: CheckBox? = null
    private var playlistAddButton: Button? = null
    private var directorySectionView: LinearLayout? = null
    private var directoryHasPlaylistSection = false
    private var playlistCollapsed = false
    private var directoryCollapsed = false
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var videoScale = 1f
    private var lastVolume = 1f
    private var isMuted = false
    private var playbackSpeed = 1f
    private var gainDb = 0
    private var subtitleOverlayEnabled = false
    private var snapSeekToCueStarts = false
    private var videoPreviewCollapsed = false
    private var audioSectionCollapsed = false
    private var subtitleSizeSp = 20f
    private var subtitleColor = Color.WHITE
    private var subtitleBackgroundAlpha = 150
    private var subtitleBottomDp = 42
    private var isFullscreen = false
    private var waitingForAllFilesAccess = false
    private var isActivityStarted = false
    private var scanSequence = 0
    private var scanFuture: Future<*>? = null
    private var isSeeking = false
    private var scrubGestureEligible = false
    private var scrubGestureCaptured = false
    private var touchGestureEligible = false
    private var doubleTapGestureCaptured = false
    private var lastVideoTapUpTime = 0L
    private var lastVideoTapX = 0f
    private var lastVideoTapY = 0f
    private var verticalGestureCaptured = false
    private var verticalGestureSide = 0
    private var verticalGestureStartValue = 0f
    private var touchGestureActive = false
    private var speedBoostActive = false
    private var speedBeforeBoost = 1f
    private var scrubStartX = 0f
    private var scrubStartY = 0f
    private var scrubStartPosition = 0L
    private var scrubTargetPosition = 0L
    private var scrubDuration = 0L
    private var scrubSnapper: SubtitleSeekSnapper? = null
    private var scrubWasSnapped = false
    private var scaleDetector: ScaleGestureDetector? = null

    private val showBufferingIndicator = Runnable {
        if (controller?.playbackState == Player.STATE_BUFFERING && controller?.playWhenReady == true) {
            playerView?.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
    }

    private val longPressSpeedBoostRunnable = Runnable {
        if (touchGestureActive &&
            touchGestureEligible &&
            !scrubGestureCaptured &&
            !verticalGestureCaptured &&
            controller?.isPlaying == true
        ) {
            beginSpeedBoost()
        }
    }

    private val playbackControlStore by lazy {
        PlaybackControlStore(getSharedPreferences(PlaybackPreferenceFiles.CONTROLS, MODE_PRIVATE))
    }
    private val playbackStateStore by lazy {
        PlaybackStateStore(getSharedPreferences(PlaybackPreferenceFiles.CONTROLS, MODE_PRIVATE))
    }
    private val playbackCoordinator by lazy { PlaybackCoordinator(playbackStateStore) }
    private val playbackQueueStore by lazy {
        PlaybackQueueStore(getSharedPreferences(PlaybackPreferenceFiles.QUEUE, MODE_PRIVATE))
    }
    private val sleepTimerStore by lazy {
        SleepTimerStore(getSharedPreferences(PlaybackPreferenceFiles.SLEEP_TIMER, MODE_PRIVATE))
    }
    private val subtitlePreferencesStore by lazy {
        SubtitlePreferencesStore(getSharedPreferences(PlaybackPreferenceFiles.SUBTITLES, MODE_PRIVATE))
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            val player = controller
            if (player != null) {
                val position = formatTime(player.currentPosition)
                val duration = formatTime(player.duration)
                if (!isSeeking) {
                    val text = "$position / $duration"
                    timingView?.let { if (it.text.toString() != text) it.text = text }
                }
            }
            updateTimerUi()
            mainHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val playbackSettings = playbackControlStore.read(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        lastVolume = playbackSettings.volume
        isMuted = playbackSettings.muted
        playbackSpeed = playbackSettings.speed
        gainDb = playbackSettings.gainDb
        currentResizeMode = playbackSettings.resizeMode
        val subtitleSettings = subtitlePreferencesStore.read(Color.WHITE)
        subtitleOverlayEnabled = subtitleSettings.overlayEnabled
        snapSeekToCueStarts = subtitlePreferencesStore.snapSeekToCueStarts()
        subtitleSizeSp = subtitleSettings.sizeSp
        subtitleColor = subtitleSettings.color
        subtitleBackgroundAlpha = subtitleSettings.backgroundAlpha
        subtitleBottomDp = subtitleSettings.bottomDp
        playlistEntries = playbackQueueStore.playlist().orEmpty()
        directoryPlaybackEntries = playbackQueueStore.directoryPlayback()
        buildUi()
        registerFullscreenBackHandler()
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
        rootLayout = root
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            if (isFullscreen) {
                view.setPadding(0, 0, 0, 0)
            } else {
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(
                    dp(12),
                    dp(10) + systemBars.top,
                    dp(12),
                    dp(12) + systemBars.bottom
                )
            }
            insets
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
        header.addView(actionButton("歌单", R.drawable.ic_playlist, "歌单") { showSavedPlaylists() })
        header.addView(actionButton("目录", R.drawable.ic_folder_open, "选择媒体目录") {
            showDirectorySourceMenu(it)
        })
        subtitleButton = actionButton(
            "字幕",
            R.drawable.ic_subtitles,
            "字幕设置",
            selected = subtitleOverlayEnabled
        ) {
            showSubtitleSettings()
        }
        header.addView(subtitleButton)
        root.addView(header)

        playerView = object : PlayerView(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                scaleDetector?.onTouchEvent(event)
                if (handleVideoTouch(this, event)) return true
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            resizeMode = currentResizeMode
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setKeepContentOnPlayerReset(true)
            useController = true
            controllerShowTimeoutMs = 5_000
            setFullscreenButtonClickListener { fullscreen -> setFullscreen(fullscreen) }
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                normalPlayerHeight()
            )
        }
        scrubOverlayView = TextView(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 8, 12, 18))
                cornerRadius = dp(8).toFloat()
            }
            elevation = dp(8).toFloat()
            isClickable = false
            isFocusable = false
        }
        playerView?.addView(
            scrubOverlayView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
                marginStart = dp(20)
                marginEnd = dp(20)
            }
        )
        applyPlayerSubtitleStyle()
        scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    videoScale = (videoScale * detector.scaleFactor).coerceIn(1f, 3f)
                    playerView?.scaleX = videoScale
                    playerView?.scaleY = videoScale
                    return true
                }
            })
        root.addView(playerView)
        playerView?.post { stylePlayerControls() }

        speakerButton = ImageButton(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "音量，点击调整"
            val selectableBackground = TypedValue()
            theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                selectableBackground,
                true
            )
            setBackgroundResource(selectableBackground.resourceId)
            setOnClickListener { showVolumePopup(this) }
        }
        updateSpeakerButton()

        playbackButton = ImageButton(this).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "播放"
            val selectableBackground = TypedValue()
            theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                selectableBackground,
                true
            )
            setBackgroundResource(selectableBackground.resourceId)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.rgb(220, 228, 237))
            setOnClickListener { playbackCoordinator.togglePlayback() }
        }
        updatePlaybackButton()

        timingView = TextView(this).apply {
            text = "00:00 / --:--"
            textSize = 12f
            setTextColor(Color.rgb(171, 181, 196))
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(timingView)

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
        renderLibrary()
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                controller?.let(playbackCoordinator::attach)
                playerView?.player = controller
                installCompactController()
                attachPlaybackButtonToPlayerControls()
                attachSpeakerButtonToPlayerControls()
                attachSettingsButtonToPlayerControls()
                attachEpisodesButtonToPlayerControls()
                stylePlayerControls()
                controller?.volume = if (isMuted) 0f else lastVolume
                updateSpeakerButton()
                setGain(gainDb)
                controller?.addListener(object : Player.Listener {
                    override fun onPlaybackParametersChanged(
                        playbackParameters: androidx.media3.common.PlaybackParameters
                    ) {
                        updatePlaybackSpeedUi(playbackParameters.speed)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateScreenAwakeState()
                        updatePlaybackButton()
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        updatePlaybackButton()
                        updateBufferingIndicator()
                    }

                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        updatePlaylistHighlight()
                        episodesPopup?.updateCurrent(mediaItem?.localConfiguration?.uri?.toString())
                    }

                    override fun onRepeatModeChanged(repeatMode: Int) {
                        playlistLoopCheck?.isChecked = PlaybackMode.fromRepeatMode(repeatMode) == PlaybackMode.REPEAT_ALL
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updatePlaybackButton()
                        updateBufferingIndicator()
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Toast.makeText(
                            this@MainActivity,
                            "播放失败：${error.errorCodeName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
                updateScreenAwakeState()
                controller?.setPlaybackSpeed(playbackSpeed)
                controller?.repeatMode = playbackQueueStore.mode().repeatMode
                if (directoryPlaybackEntries != null || playlistEntries.isNotEmpty() ||
                    (librarySource != null && !isLibraryLoading)) applyPlaybackQueue()
                renderLibrary()
            } catch (error: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "播放服务连接失败：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun openTreePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            savedTreeUri()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
        startActivityForResult(intent, REQUEST_TREE)
    }

    private fun showDirectorySourceMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("浏览全部目录（含隐藏）").setOnMenuItemClickListener {
                openAllFilesDirectoryPicker()
                true
            }
            menu.add("使用系统目录选择器").setOnMenuItemClickListener {
                openTreePicker()
                true
            }
            show()
        }
    }

    private fun openAllFilesDirectoryPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            waitingForAllFilesAccess = true
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_STORAGE)
            return
        }
        showFileDirectoryPicker(
            savedFileDirectory() ?: Environment.getExternalStorageDirectory()
        )
    }

    private fun savedFileDirectory(): File? =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(DIRECTORY_PATH, null)
            ?.let { path -> runCatching { File(path).canonicalFile }.getOrNull() }
            ?.takeIf { it.isDirectory && it.canRead() }

    private fun savedTreeUri(): Uri? {
        val savedUri = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(TREE_URI, null)
            ?.let { value -> runCatching { Uri.parse(value) }.getOrNull() }
            ?: return null
        return contentResolver.persistedUriPermissions
            .firstOrNull { it.uri == savedUri && it.isReadPermission }
            ?.uri
    }

    private fun showFileDirectoryPicker(initialDirectory: File) {
        var currentDirectory = initialDirectory
        val storageRoot = Environment.getExternalStorageDirectory().canonicalFile
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }
        val pathBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.player_button_background)
            setPadding(dp(4), dp(4), dp(12), dp(4))
        }
        val pathView = TextView(this).apply {
            setTextColor(getColor(R.color.player_text))
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextIsSelectable(true)
        }
        val upButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_expand_less)
            rotation = -90f
            background = null
            contentDescription = "返回上一级目录"
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        pathBar.addView(upButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        pathBar.addView(pathView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.player_text_muted))
            setPadding(dp(2), dp(12), 0, dp(8))
        }
        val directoryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            addView(directoryContainer)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        panel.addView(pathBar)
        panel.addView(statusView)
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.48f).toInt())
        )

        fun renderDirectory() {
            val relativePath = currentDirectory.canonicalPath.removePrefix(storageRoot.path).trimStart('/')
            pathView.text = "共享存储" + if (relativePath.isNotEmpty()) " / ${relativePath.replace("/", " / ")}" else ""
            pathView.contentDescription = currentDirectory.absolutePath
            upButton.isEnabled = currentDirectory.canonicalFile != storageRoot
            upButton.alpha = if (upButton.isEnabled) 1f else 0.3f
            directoryContainer.removeAllViews()
            scroll.scrollTo(0, 0)
            val children = try {
                currentDirectory.listFiles()
                    ?.filter { it.canRead() }
                    ?.sortedWith(compareBy<File> { !it.isDirectory }
                        .thenBy { it.name.startsWith('.') }
                        .thenBy { it.name.lowercase(Locale.ROOT) })
                    .orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
            val directories = children.filter { it.isDirectory }
            val files = children.filter { it.isFile }
            val playableCount = files.count { isSupportedMedia(it.name) }
            val subtitleCount = files.count { isSubtitleFile(it.name) }

            statusView.text = buildString {
                    append("${directories.size} 个子目录 · ${files.size} 个文件")
                    if (playableCount > 0) append(" · ${playableCount} 个可播放")
                }

            if (directories.isNotEmpty()) {
                directories.forEach { directory ->
                    directoryContainer.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = getDrawable(R.drawable.player_button_background)
                        setPadding(dp(12), 0, dp(12), 0)
                        addView(ImageView(this@MainActivity).apply {
                            setImageResource(R.drawable.ic_folder_open)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LinearLayout.LayoutParams(dp(24), dp(24)))
                        addView(TextView(this@MainActivity).apply {
                            text = directory.name
                            textSize = 15f
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.MIDDLE
                            setPadding(dp(12), 0, dp(8), 0)
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        if (directory.name.startsWith('.')) addView(TextView(this@MainActivity).apply {
                            text = "隐藏"
                            textSize = 11f
                            setTextColor(getColor(R.color.player_text_muted))
                            setPadding(0, 0, dp(8), 0)
                        })
                        addView(ImageView(this@MainActivity).apply {
                            setImageResource(R.drawable.ic_chevron_right)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LinearLayout.LayoutParams(dp(20), dp(20)))
                        isFocusable = true
                        contentDescription = "打开目录 ${directory.name}"
                        setOnClickListener {
                            currentDirectory = directory
                            renderDirectory()
                        }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply {
                        bottomMargin = dp(8)
                    })
                }
            }

            if (files.isNotEmpty()) {
                directoryContainer.addView(TextView(this).apply {
                    text = "文件" + if (subtitleCount > 0) " · 含 $subtitleCount 个字幕" else ""
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(getColor(R.color.player_text_muted))
                    setPadding(dp(2), dp(8), 0, dp(8))
                })
                val previewLimit = 40
                files.take(previewLimit).forEach { file ->
                    val preview = if (isSupportedMedia(file.name)) {
                        createMediaPreview(MediaEntry(file.name, Uri.fromFile(file), mediaType(file.name), emptyList()),
                            maxLines = 2, compact = true)
                    } else TextView(this).apply {
                        text = (if (isSubtitleFile(file.name)) "字幕 · " else "文件 · ") + file.name
                        textSize = 13f
                        setTextColor(getColor(R.color.player_text_muted))
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = getDrawable(R.drawable.player_button_background)
                    }
                    directoryContainer.addView(preview, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(if (isSupportedMedia(file.name)) 72 else 48)
                    ).apply { bottomMargin = dp(6) })
                }
                if (files.size > previewLimit) {
                    directoryContainer.addView(TextView(this).apply {
                        text = "… 还有 ${files.size - previewLimit} 个文件未显示"
                        textSize = 12f
                        setTextColor(Color.rgb(174, 181, 192))
                        setPadding(dp(8), dp(4), 0, dp(8))
                    })
                }
            }

            if (children.isEmpty()) {
                directoryContainer.addView(TextView(this).apply {
                    text = "此目录为空或没有可访问的内容"
                    textSize = 13f
                    setTextColor(Color.rgb(174, 181, 192))
                    setPadding(0, dp(16), 0, dp(16))
                })
            }
        }
        upButton.setOnClickListener {
            val parent = currentDirectory.parentFile?.canonicalFile
            if (parent != null && (parent == storageRoot || parent.path.startsWith(storageRoot.path + File.separator))) {
                currentDirectory = parent
                renderDirectory()
            }
        }
        renderDirectory()
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择媒体目录")
            .setView(panel)
            .setNegativeButton("取消", null)
            .setPositiveButton("使用当前目录") { _, _ ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(DIRECTORY_PATH, currentDirectory.absolutePath)
                    .remove(TREE_URI)
                    .apply()
                scanFileDirectory(currentDirectory)
            }
            .show()
        dialog.window?.setLayout(resources.displayMetrics.widthPixels - dp(24), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun loadSavedTree() {
        val savedDirectory = savedFileDirectory()
        if (savedDirectory != null && hasAllFilesAccess()) {
            scanFileDirectory(savedDirectory)
            return
        }
        savedTreeUri()?.let { scanDocumentTree(it) }
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun scanDocumentTree(uri: Uri) {
        val requestId = beginScan(uri.toString())
        scanFuture = scanExecutor.submit {
            val entries = try {
                val root = DocumentFile.fromTreeUri(this, uri)
                root?.let { mediaScanner.scanDocumentDirectory(it) }.orEmpty()
            } catch (_: RuntimeException) {
                emptyList()
            }
            runOnUiThread {
                if (requestId == scanSequence) finishScan(entries)
            }
        }
    }

    private fun scanFileDirectory(directory: File) {
        val requestId = beginScan(directory.absolutePath)
        scanFuture = scanExecutor.submit {
            val entries = try {
                mediaScanner.scanFileDirectory(directory)
            } catch (_: RuntimeException) {
                emptyList()
            }
            runOnUiThread {
                if (requestId == scanSequence) finishScan(entries)
            }
        }
    }

    private fun beginScan(source: String): Int {
        librarySource = source
        loadedEntries = emptyList()
        isLibraryLoading = true
        scanFuture?.cancel(true)
        thumbnailLoader.cancelPending()
        val requestId = ++scanSequence
        showLibraryLoading()
        return requestId
    }

    private fun showLibraryLoading() = renderLibrary()

    private fun finishScan(entries: List<MediaEntry>) {
        loadedEntries = entries.sortedBy { it.name.lowercase(Locale.ROOT) }
        isLibraryLoading = false
        val saved = librarySource?.let { playbackQueueStore.migrateLegacySelection(it, loadedEntries) }
            ?: playbackQueueStore.playlist()
        val refreshed = loadedEntries.associateBy { it.uri.toString() }
        playlistEntries = saved.orEmpty().map { refreshed[it.uri.toString()] ?: it }
        if (playlistEntries != saved.orEmpty()) {
            playbackQueueStore.savePlaylist(playlistEntries)
            saveActivePlaylist()
        }
        directoryPlaybackEntries?.let { previous ->
            val updated = previous.map { refreshed[it.uri.toString()] ?: it }
            if (updated != previous) {
                directoryPlaybackEntries = updated
                playbackQueueStore.saveDirectoryPlayback(updated)
            }
        }
        applyPlaybackQueue()
        renderLibrary()
    }

    private fun librarySection() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), 0, dp(12), dp(12))
        background = GradientDrawable().apply {
            setColor(getColor(R.color.player_surface))
            setStroke(dp(1), getColor(R.color.player_border))
            cornerRadius = dp(12).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    private fun renderLibrary(refreshDirectory: Boolean = true) {
        val container = libraryContainer ?: return
        playlistUndoRow?.animate()?.cancel()
        playlistUndoRow = null
        val keepDirectory = !refreshDirectory && directorySectionView != null &&
            directoryHasPlaylistSection == hasPlaylistSection()
        val directory = directorySectionView.takeIf { keepDirectory }
        // Playlist edits only replace their own section; keep all directory cards.
        val oldPlaylistSections = (0 until container.childCount).map { container.getChildAt(it) }
            .filter { it !== directory }
        oldPlaylistSections.forEach(container::removeView)
        playlistView = null
        playlistLoopCheck = null
        if (!keepDirectory || hasPlaylistSection()) playlistAddButton = null
        clearedPlaylist?.takeIf { playlistEntries.isEmpty() }?.let { previous ->
            val undoRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "已清空播放列表"
                    textSize = 13f
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(getColor(R.color.player_text_muted))
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(quietAction("撤销", "撤销清空播放列表") {
                    if (clearedPlaylist !== previous) return@quietAction
                    playbackQueueStore.setActivePlaylistId(previous.savedId)
                    if (previous.resumePlaylist) {
                        directoryPlaybackEntries = null
                        playbackQueueStore.saveDirectoryPlayback(null)
                    }
                    savePlaybackQueue(previous.entries)
                })
            }
            playlistUndoRow = undoRow
            container.addView(undoRow, 0)
        }
        if (hasPlaylistSection()) {
            val section = librarySection()
            val playlistName = playbackQueueStore.activePlaylistId()?.let(playbackQueueStore::playlist)?.name
                ?: "播放列表"
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (playlistCollapsed) View.GONE else View.VISIBLE
            }
            val title = addLibrarySectionHeader(
                section, "$playlistName · ${playlistEntries.size} 项", "播放列表", !playlistCollapsed, primary = true
            ) {
                playlistCollapsed = !playlistCollapsed
                body.visibility = if (playlistCollapsed) View.GONE else View.VISIBLE
            }.apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                tooltipText = playlistName
            }
            val loopCheck = CheckBox(this).apply {
                text = "循环"
                textSize = 13f
                setTextColor(getColor(R.color.player_text))
                contentDescription = "循环播放"
                minHeight = dp(48)
                isChecked = currentPlaybackMode() == PlaybackMode.REPEAT_ALL
                isEnabled = controller != null
                setOnCheckedChangeListener { _, enabled ->
                    val mode = if (enabled) PlaybackMode.REPEAT_ALL else PlaybackMode.SEQUENTIAL
                    if (mode != currentPlaybackMode()) setPlaybackMode(mode)
                }
            }
            playlistLoopCheck = loopCheck
            section.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(loopCheck, LinearLayout.LayoutParams(dp(80), dp(48)))
                addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(createPlaylistMenuButton())
                addView(quietIcon(R.drawable.ic_close, "清空当前播放列表") { clearCurrentPlaylist() })
            })
            playlistView = PlaybackQueueView(
                activity = this,
                entries = playlistEntries,
                currentUri = controller?.currentMediaItem?.localConfiguration?.uri?.toString(),
                addMediaAction = createPlaylistAddButton("添加媒体"),
                createPreview = { createMediaPreview(it, maxLines = 2, compact = true) },
                onChange = { entries ->
                    savePlaybackQueue(entries, refreshLibrary = false)
                    title.text = "$playlistName · ${entries.size} 项"
                    updatePlaylistAddButton()
                    if (entries.isEmpty()) renderLibrary(refreshDirectory = false)
                },
                onPlay = ::playPlaylistEntry
            ).also { body.addView(it) }
            section.addView(body)
            container.addView(section, 0)
            updatePlaylistHighlight()
        }
        if (!keepDirectory) {
            directorySectionView = createDirectorySection().also { container.addView(it) }
            directoryHasPlaylistSection = hasPlaylistSection()
        }
        updatePlaylistAddButton()
    }

    private fun createDirectorySection(): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (!hasPlaylistSection()) {
            populateDirectoryBody(body)
            return body
        }
        val section = librarySection()
        body.visibility = if (directoryCollapsed) View.GONE else View.VISIBLE
        var populated = false
        fun populate() {
            if (populated) return
            populated = true
            populateDirectoryBody(body)
        }
        addLibrarySectionHeader(section, "当前目录内容 · ${loadedEntries.size} 项", "当前目录内容",
            !directoryCollapsed, primary = true) {
            directoryCollapsed = !directoryCollapsed
            if (!directoryCollapsed) populate()
            body.visibility = if (directoryCollapsed) View.GONE else View.VISIBLE
        }
        if (!directoryCollapsed) populate()
        section.addView(body)
        return section
    }

    private fun populateDirectoryBody(body: LinearLayout) {
        if (isLibraryLoading) body.addView(ProgressBar(this).apply {
            isIndeterminate = true
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        if (isLibraryLoading || loadedEntries.isEmpty()) {
            body.addView(TextView(this).apply {
                text = when {
                    isLibraryLoading -> "正在加载媒体目录…"
                    librarySource == null -> "先选择媒体目录。"
                    else -> "目录为空或暂时无法读取"
                }
                textSize = 14f
                setTextColor(getColor(R.color.player_text_muted))
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }
        fun mediaGroup(entries: List<MediaEntry>, title: String, label: String, collapsed: Boolean,
                       onCollapsed: (Boolean) -> Unit) {
            if (entries.isEmpty()) return
            var hidden = collapsed
            var populated = false
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (hidden) View.GONE else View.VISIBLE
            }
            fun populate() {
                if (populated) return
                populated = true
                entries.forEach { addLibraryEntry(group, it) }
            }
            addLibrarySectionHeader(body, title, label, !hidden) {
                hidden = !hidden
                onCollapsed(hidden)
                if (!hidden) populate()
                group.visibility = if (hidden) View.GONE else View.VISIBLE
            }
            if (!hidden) populate()
            body.addView(group)
        }
        val videos = loadedEntries.filter { it.isVideo }
        mediaGroup(videos, "MP4 视频预览（${videos.size}）", "视频预览", videoPreviewCollapsed) {
            videoPreviewCollapsed = it
        }
        val audio = loadedEntries.filter { !it.isVideo }
        mediaGroup(audio, "音频播放（${audio.size}）", "音频播放", audioSectionCollapsed) {
            audioSectionCollapsed = it
        }
    }

    private fun createPlaylistAddButton(label: String) = quietAction(label) { showAddMedia() }.apply {
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_add, 0, 0, 0)
        compoundDrawablePadding = dp(4)
        playlistAddButton = this
    }

    private fun quietAction(label: String, description: String = label, onClick: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = description
        textSize = 13f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(getColor(R.color.player_accent))
        useQuietBackground(this)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48))
        setOnClickListener { onClick() }
    }

    private fun hasPlaylistSection() = playlistEntries.isNotEmpty() || playbackQueueStore.activePlaylistId() != null

    private fun quietIcon(icon: Int, description: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.player_text_muted))
        contentDescription = description
        setPadding(dp(14), dp(14), dp(14), dp(14))
        useQuietBackground(this)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
    }

    private fun useQuietBackground(view: View) {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        view.setBackgroundResource(value.resourceId)
    }

    private fun updatePlaylistHighlight() {
        playlistView?.updateCurrentUri(controller?.currentMediaItem?.localConfiguration?.uri?.toString())
    }

    private fun updatePlaylistAddButton() {
        playlistAddButton?.apply {
            isEnabled = !isLibraryLoading && availablePlaylistAdditions().isNotEmpty() && controller != null
            alpha = if (isEnabled) 1f else 0.4f
        }
    }

    private fun addLibrarySectionHeader(
        container: LinearLayout,
        title: String,
        collapsibleLabel: String = "视频预览",
        expanded: Boolean? = null,
        primary: Boolean = false,
        onToggle: (() -> Unit)? = null
    ): TextView {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = if (primary) 16f else 14f
            setTextColor(getColor(if (primary) R.color.player_text else R.color.player_text_muted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleView)
        if (expanded != null && onToggle != null) {
            var isExpanded = expanded == true
            header.addView(ImageButton(this).apply {
                setImageResource(
                    if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                )
                contentDescription = if (isExpanded) "收起$collapsibleLabel" else "展开$collapsibleLabel"
                setPadding(0, 0, 0, 0)
                scaleType = ImageView.ScaleType.CENTER
                val selectableBackground = TypedValue()
                theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless,
                    selectableBackground,
                    true
                )
                setBackgroundResource(selectableBackground.resourceId)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setOnClickListener {
                    onToggle()
                    isExpanded = !isExpanded
                    setImageResource(
                        if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                    )
                    contentDescription = if (isExpanded) "收起$collapsibleLabel" else "展开$collapsibleLabel"
                }
            })
        }
        container.addView(header)
        return titleView
    }

    private fun addLibraryEntry(
        container: LinearLayout,
        entry: MediaEntry
    ) {
        container.addView(createMediaPreview(entry).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "${entry.name}，播放"
            setOnClickListener { playDirectoryEntry(entry) }
            if (hasPlaylistSection()) {
                val card = this
                val requestId = scanSequence
                val detector = GestureDetector(this@MainActivity, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(event: MotionEvent) = true

                    override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                        if (card.isAttachedToWindow && requestId == scanSequence && isActivityStarted && !isFullscreen) {
                            card.performClick()
                        }
                        return true
                    }

                    override fun onDoubleTap(event: MotionEvent) = true

                    override fun onDoubleTapEvent(event: MotionEvent): Boolean {
                        if (event.actionMasked == MotionEvent.ACTION_UP && card.isAttachedToWindow && requestId == scanSequence) {
                            addDirectoryEntryToPlaylist(entry)
                        }
                        return true
                    }
                }).apply { setIsLongpressEnabled(false) }
                setOnTouchListener { _, event ->
                    detector.onTouchEvent(event)
                    true
                }
                ViewCompat.addAccessibilityAction(this, "加入播放列表") { _, _ ->
                    addDirectoryEntryToPlaylist(entry)
                    true
                }
            }
        })
    }

    private fun createMediaPreview(entry: MediaEntry, label: String = entry.name, maxLines: Int = 1, compact: Boolean = false): View {
        val rowHeight = if (maxLines > 1) dp(72) else if (entry.isVideo) dp(64) else dp(52)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight
            ).apply { bottomMargin = dp(2) }
            setPadding(0, 0, dp(10), 0)
            background = mediaRowBackground()
            clipToOutline = true
        }
        if (entry.isVideo) {
            val thumbnail = ImageView(this).apply {
                setBackgroundColor(Color.rgb(32, 36, 42))
                setImageResource(android.R.drawable.ic_media_play)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "${entry.name} 封面"
                layoutParams = LinearLayout.LayoutParams(dp(if (compact) 80 else 112), ViewGroup.LayoutParams.MATCH_PARENT)
            }
            row.addView(thumbnail)
            thumbnailLoader.load(entry, thumbnail)
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        textColumn.addView(TextView(this).apply {
            text = label
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textSize = if (compact) 13f else 14f
            setTextColor(Color.rgb(232, 237, 243))
            this.maxLines = maxLines
            ellipsize = TextUtils.TruncateAt.END
        })
        if (compact) textColumn.addView(TextView(this).apply {
            text = entry.type.name
            textSize = 11f
            setTextColor(getColor(R.color.player_text_muted))
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        if (entry.subtitles.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = if (entry.subtitles.size == 1) "字幕" else "字幕 ${entry.subtitles.size}"
                gravity = Gravity.CENTER
                textSize = 10.5f
                includeFontPadding = false
                setTextColor(Color.rgb(159, 231, 215))
                setPadding(dp(7), 0, dp(7), 0)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(24, 59, 57))
                    setStroke(dp(1), Color.rgb(54, 126, 113))
                    cornerRadius = dp(5).toFloat()
                }
                contentDescription = "${entry.subtitles.size} 条字幕"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(26)
                ).apply {
                    marginStart = dp(4)
                    marginEnd = dp(2)
                }
            })
        }
        return row
    }

    private fun mediaRowBackground(): StateListDrawable = statefulBackground(
        normalColor = Color.rgb(27, 33, 40),
        pressedColor = Color.rgb(43, 53, 63),
        strokeColor = Color.rgb(48, 58, 69),
        radius = dp(6)
    )

    private fun currentPlaybackMode(): PlaybackMode = controller?.let {
        PlaybackMode.fromRepeatMode(it.repeatMode)
    } ?: playbackQueueStore.mode()

    private fun setPlaybackMode(mode: PlaybackMode) {
        playbackQueueStore.saveMode(mode)
        controller?.repeatMode = mode.repeatMode
        saveActivePlaylist()
    }

    private fun applyPlaybackQueue() {
        val entries = directoryPlaybackEntries ?: playlistEntries.ifEmpty { loadedEntries }
        if (activeEntries != entries) episodesPopup?.dismiss()
        activeEntries = entries
        playbackCoordinator.applyPlaylist(activeEntries)
    }

    private fun availablePlaylistAdditions(): List<MediaEntry> {
        val selected = playlistEntries.map { it.uri.toString() }.toSet()
        return loadedEntries.distinctBy { it.uri.toString() }.filter { it.uri.toString() !in selected }
    }

    private fun savePlaybackQueue(entries: List<MediaEntry>, refreshLibrary: Boolean = true) {
        dismissPlaylistUndo()
        playlistEntries = entries.distinctBy { it.uri.toString() }
        playbackQueueStore.savePlaylist(playlistEntries)
        applyPlaybackQueue()
        playbackCoordinator.savePosition()
        saveActivePlaylist()
        if (refreshLibrary) renderLibrary(refreshDirectory = false)
    }

    private fun showAddMedia() {
        val candidates = availablePlaylistAdditions()
        if (controller == null || candidates.isEmpty()) return
        val checked = BooleanArray(candidates.size)
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val checks = mutableListOf<CheckBox>()
        val scroll = dialogScroll(choices)
        val picker = AlertDialog.Builder(this)
            .setTitle(if (hasPlaylistSection()) "添加媒体" else "创建播放列表")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setNeutralButton("全选", null)
            .setPositiveButton("添加") { _, _ ->
                val additions = candidates.filterIndexed { index, _ -> checked[index] }
                if (additions.isNotEmpty()) {
                    playlistCollapsed = false
                    appendToPlaylist(additions)
                }
            }.create()
        candidates.forEachIndexed { index, entry ->
            val check = CheckBox(this).apply {
                contentDescription = "添加 ${entry.name}"
                setOnCheckedChangeListener { _, selected ->
                    checked[index] = selected
                    picker.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = checked.any { it }
                    picker.getButton(AlertDialog.BUTTON_NEUTRAL)?.text = if (checked.all { it }) "全不选" else "全选"
                }
            }
            checks += check
            choices.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(check, LinearLayout.LayoutParams(dp(48), dp(72)))
                addView(createMediaPreview(entry, maxLines = 2, compact = true),
                    LinearLayout.LayoutParams(0, dp(72), 1f))
                isFocusable = true
                setOnClickListener { check.toggle() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)))
        }
        picker.show()
        picker.window?.setLayout(minOf(resources.displayMetrics.widthPixels - dp(24), dp(560)),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        scroll.layoutParams.height = minOf(dp(80) * candidates.size,
            (resources.displayMetrics.heightPixels * 0.7f - dp(140)).toInt().coerceAtLeast(dp(80)))
        scroll.requestLayout()
        picker.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        picker.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val selectAll = !checked.all { it }
            checks.forEach { it.isChecked = selectAll }
        }
    }

    private fun playDirectoryEntry(entry: MediaEntry) {
        if (controller == null) {
            Toast.makeText(this, "播放器正在连接，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        val directory = loadedEntries.takeIf { hasPlaylistSection() }
        if (directoryPlaybackEntries != directory) {
            directoryPlaybackEntries = directory
            playbackQueueStore.saveDirectoryPlayback(directory)
        }
        applyPlaybackQueue()
        playbackCoordinator.playEntry(activeEntries.indexOfFirst { it.uri == entry.uri })
    }

    private fun playPlaylistEntry(entry: MediaEntry) {
        if (controller == null) {
            Toast.makeText(this, "播放器正在连接，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        directoryPlaybackEntries = null
        playbackQueueStore.saveDirectoryPlayback(null)
        applyPlaybackQueue()
        playbackCoordinator.playEntry(activeEntries.indexOfFirst { it.uri == entry.uri })
    }

    private fun addDirectoryEntryToPlaylist(entry: MediaEntry) {
        if (!hasPlaylistSection()) return
        if (playlistEntries.any { it.uri == entry.uri }) {
            Toast.makeText(this, "已在播放列表中", Toast.LENGTH_SHORT).show()
            return
        }
        appendToPlaylist(listOf(entry))
        Toast.makeText(this, "已加入播放列表", Toast.LENGTH_SHORT).show()
    }

    private fun appendToPlaylist(entries: List<MediaEntry>) {
        // An empty playlist currently falls back to directory playback. Keep that
        // queue when adding its first item, just as when adding later items.
        if (playlistEntries.isEmpty() && directoryPlaybackEntries == null && activeEntries.isNotEmpty()) {
            directoryPlaybackEntries = activeEntries
            playbackQueueStore.saveDirectoryPlayback(directoryPlaybackEntries)
        }
        savePlaybackQueue(playlistEntries + entries)
    }

    private fun createPlaylistMenuButton(): View {
        return quietIcon(R.drawable.ic_more_vert, "更多歌单操作") {}.apply {
            setOnClickListener { anchor ->
                PopupMenu(this@MainActivity, anchor).apply {
                    menu.add(if (playbackQueueStore.activePlaylistId() == null) "保存为歌单" else "另存为新歌单")
                        .setOnMenuItemClickListener { showSavePlaylist(); true }
                    show()
                }
            }
        }
    }

    private fun saveActivePlaylist() {
        playbackQueueStore.activePlaylistId()?.let { id ->
            playbackQueueStore.updatePlaylist(id, playlistEntries, currentPlaybackMode())
        }
    }

    private fun clearCurrentPlaylist() {
        val previous = ClearedPlaylist(playlistEntries.toList(), directoryPlaybackEntries == null,
            playbackQueueStore.activePlaylistId())
        if (previous.resumePlaylist) {
            val currentUri = controller?.currentMediaItem?.localConfiguration?.uri
            directoryPlaybackEntries = activeEntries.firstOrNull { it.uri == currentUri }?.let(::listOf)
            playbackQueueStore.saveDirectoryPlayback(directoryPlaybackEntries)
        }
        // Clearing the queue does not empty or delete the saved playlist.
        playbackQueueStore.setActivePlaylistId(null)
        savePlaybackQueue(emptyList(), refreshLibrary = false)
        clearedPlaylist = previous
        mainHandler.postDelayed(playlistUndoTimeout, PLAYLIST_UNDO_TIMEOUT_MS)
        renderLibrary(refreshDirectory = false)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val pending = clearedPlaylist
        val handled = super.dispatchTouchEvent(event)
        // Let any queued click run first, including an Undo tapped on the third interaction.
        if (event.actionMasked == MotionEvent.ACTION_UP && pending != null) {
            mainHandler.post {
                if (clearedPlaylist === pending) {
                    playlistUndoInteractions++
                    if (playlistUndoInteractions >= PLAYLIST_UNDO_MAX_INTERACTIONS) dismissPlaylistUndo(animate = true)
                }
            }
        }
        return handled
    }

    private fun dismissPlaylistUndo(animate: Boolean = false) {
        mainHandler.removeCallbacks(playlistUndoTimeout)
        clearedPlaylist = null
        playlistUndoInteractions = 0
        val row = playlistUndoRow ?: return
        playlistUndoRow = null
        row.animate().cancel()
        if (animate) {
            row.animate().alpha(0f).setDuration(180L).withEndAction {
                (row.parent as? ViewGroup)?.removeView(row)
            }.start()
        } else {
            (row.parent as? ViewGroup)?.removeView(row)
        }
    }

    private fun showSavePlaylist() {
        val names = playbackQueueStore.savedPlaylists().map { it.name }.toSet()
        val number = generateSequence(1) { it + 1 }.first { "歌单 $it" !in names }
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "歌单名称"
            contentDescription = "歌单名称"
            setText("歌单 $number")
            selectAll()
        }
        val panel = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("新建歌单")
            .setMessage(if (playlistEntries.isEmpty()) "创建后可从当前目录添加媒体。" else "包含当前播放列表，之后的调整会自动保存。")
            .setView(panel)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isBlank()) input.error = "请输入歌单名称"
            else playbackQueueStore.createPlaylist(name, playlistEntries, currentPlaybackMode())?.let { saved ->
                dismissPlaylistUndo()
                playbackQueueStore.setActivePlaylistId(saved.id)
                renderLibrary(refreshDirectory = false)
                Toast.makeText(this, "歌单已创建，后续调整自动保存", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun openSavedPlaylist(playlist: SavedPlaylist) {
        // Detach the previous identity before changing mode, so it cannot be overwritten.
        playbackQueueStore.setActivePlaylistId(null)
        directoryPlaybackEntries = null
        playbackQueueStore.saveDirectoryPlayback(null)
        playlistCollapsed = false
        setPlaybackMode(playlist.mode)
        playbackQueueStore.setActivePlaylistId(playlist.id)
        savePlaybackQueue(playlist.entries)
    }

    private fun showSavedPlaylists() {
        SavedPlaylistsDialog(
            activity = this,
            store = playbackQueueStore,
            activeId = { playbackQueueStore.activePlaylistId() },
            onOpen = ::openSavedPlaylist,
            onCreate = ::showSavePlaylist,
            onChanged = { renderLibrary(refreshDirectory = false) }
        ).show()
    }

    private fun showTimerMenu(anchor: android.view.View) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val countdownView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 24f
            setTextColor(getColor(R.color.player_accent))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(12))
            background = getDrawable(R.drawable.player_button_background)
        }
        panel.addView(countdownView)
        panel.addView(TextView(this).apply {
            text = "快速设置"
            textSize = 13f
            setTextColor(Color.rgb(171, 181, 196))
            setPadding(0, dp(16), 0, dp(8))
        })
        listOf(15, 30, 60, 90).chunked(2).forEach { pair ->
            panel.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                pair.forEachIndexed { index, minutes ->
                    addView(Button(this@MainActivity).apply {
                        text = "${minutes} 分钟"
                        setOnClickListener { setTimer(minutes) }
                    }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        if (index == 0) marginEnd = dp(8)
                    })
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                bottomMargin = dp(8)
            })
        }
        panel.addView(TextView(this).apply {
            text = "自定义时长（分钟）"
            textSize = 13f
            setTextColor(Color.rgb(171, 181, 196))
            setPadding(0, dp(10), 0, dp(4))
        })
        val customInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "例如 45"
            setSingleLine(true)
            contentDescription = "自定义定时分钟数"
        }
        val customRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        customRow.addView(customInput, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        customRow.addView(Button(this).apply {
            text = "设置"
            contentDescription = "设置自定义定时"
            isAllCaps = false
            setOnClickListener {
                val minutes = customInput.text.toString().trim().toIntOrNull()
                if (minutes == null || minutes !in 1..MAX_TIMER_MINUTES) {
                    customInput.error = "请输入 1 到 ${MAX_TIMER_MINUTES} 分钟"
                } else {
                    customInput.error = null
                    setTimer(minutes)
                    customInput.text.clear()
                }
            }
        }, LinearLayout.LayoutParams(dp(80), dp(48)))
        panel.addView(customRow)

        val dialog = AlertDialog.Builder(this)
            .setTitle("睡眠定时")
            .setView(dialogScroll(panel))
            .setNeutralButton("关闭定时") { _, _ -> setTimer(0) }
            .setNegativeButton("关闭页面", null)
            .create()
        timerCountdownView = countdownView
        timerDialog = dialog
        dialog.setOnDismissListener {
            if (timerCountdownView === countdownView) timerCountdownView = null
            if (timerDialog === dialog) timerDialog = null
        }
        dialog.show()
        dialog.window?.setLayout(dialogWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
        updateTimerUi()
    }

    private fun setPlaybackSpeed(speed: Float) {
        val safeSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        controller?.setPlaybackSpeed(safeSpeed)
        updatePlaybackSpeedUi(safeSpeed)
    }

    private fun updatePlaybackSpeedUi(speed: Float) {
        if (speedBoostActive) return
        playbackSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        playbackControlStore.saveSpeed(playbackSpeed)
    }

    private fun beginSpeedBoost() {
        val player = controller ?: return
        if (speedBoostActive || !player.isPlaying) return
        speedBeforeBoost = player.playbackParameters.speed
            .takeIf { it.isFinite() && it > 0f } ?: playbackSpeed
        speedBoostActive = true
        player.setPlaybackSpeed(3f)
        showScrubOverlay("3.0x 加速", hideAfterMs = null)
    }

    private fun endSpeedBoost() {
        if (!speedBoostActive) return
        speedBoostActive = false
        val restoredSpeed = speedBeforeBoost.takeIf { it.isFinite() && it > 0f } ?: 1f
        controller?.setPlaybackSpeed(restoredSpeed)
        updatePlaybackSpeedUi(restoredSpeed)
        showScrubOverlay("恢复 ${speedLabel(restoredSpeed)}", hideAfterMs = 700L)
    }

    private fun showPlayerSettingsMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.addSubMenu("画面设置").apply {
                add("适配画面").setOnMenuItemClickListener {
                    setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                    true
                }
                add("裁剪放大").setOnMenuItemClickListener {
                    setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
                    true
                }
                add("拉伸填充").setOnMenuItemClickListener {
                    setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL)
                    true
                }
                add("重置双指缩放").setOnMenuItemClickListener {
                    resetVideoScale()
                    true
                }
            }
            menu.addSubMenu("倍速 ${speedLabel(playbackSpeed)}").apply {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    val label = if (speed == 1f) "正常 1.0x" else "${speed}x"
                    add(label).setOnMenuItemClickListener {
                        setPlaybackSpeed(speed)
                        true
                    }
                }
            }
            menu.addSubMenu("增益 ${gainLabel().removePrefix("增益 ")}").apply {
                listOf(0, 6, 12).forEach { db ->
                    add(if (db == 0) "原声 0 dB" else "+${db} dB 音频增益")
                        .setOnMenuItemClickListener {
                            setGain(db)
                            true
                        }
                }
            }
            menu.add("睡眠定时").setOnMenuItemClickListener {
                showTimerMenu(anchor)
                true
            }
            menu.add("字幕设置").setOnMenuItemClickListener {
                showSubtitleSettings()
                true
            }
            show()
        }
    }

    private fun showSubtitleSettings() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val preview = TextView(this).apply {
            text = "字幕预览\n愿你今晚睡得安稳"
            gravity = Gravity.CENTER
            minHeight = dp(88)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        panel.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })

        val overlayCheck = CheckBox(this).apply {
            text = "退到后台时显示悬浮字幕"
            isChecked = subtitleOverlayEnabled
            minHeight = dp(48)
            textSize = 14f
        }
        val snapSeekCheck = CheckBox(this).apply {
            text = "有字幕时，滑动对齐字幕起始点"
            isChecked = snapSeekToCueStarts
            minHeight = dp(48)
            textSize = 14f
        }
        panel.addView(snapSeekCheck)
        panel.addView(overlayCheck)
        panel.addView(Button(this).apply {
            text = "授权悬浮字幕显示"
            isAllCaps = false
            setOnClickListener { requestOverlayPermission() }
        })

        fun settingLabel() = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.player_text_muted))
            setPadding(0, dp(12), 0, 0)
        }
        val sizeLabel = settingLabel()
        val sizeSeek = SeekBar(this).apply {
            max = 24
            progress = (subtitleSizeSp - 12f).roundToInt()
            minimumHeight = dp(48)
        }
        val alphaLabel = settingLabel()
        val alphaSeek = SeekBar(this).apply {
            max = 255
            progress = subtitleBackgroundAlpha
            minimumHeight = dp(48)
        }
        val bottomLabel = settingLabel()
        val bottomSeek = SeekBar(this).apply {
            max = 172
            progress = subtitleBottomDp - 8
            minimumHeight = dp(48)
        }
        val colorButton = Button(this).apply {
            isAllCaps = false
        }
        fun updatePreview() {
            val size = (12 + sizeSeek.progress).toFloat()
            val alpha = alphaSeek.progress
            val bottom = 8 + bottomSeek.progress
            sizeLabel.text = "字号：${size.toInt()}sp"
            alphaLabel.text = "背景透明度：${(alpha * 100 / 255)}%"
            bottomLabel.text = "距屏幕底部：${bottom}dp"
            colorButton.text = "字幕颜色：${subtitleColorName(subtitleColor)}"
            preview.textSize = size
            preview.setTextColor(subtitleColor)
            preview.background = GradientDrawable().apply {
                setColor(Color.argb(alpha, 0, 0, 0))
                cornerRadius = dp(8).toFloat()
            }
        }
        colorButton.setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                listOf(
                    "白色" to Color.WHITE,
                    "暖黄色" to Color.rgb(255, 232, 170),
                    "青色" to Color.rgb(175, 235, 255),
                    "浅绿色" to Color.rgb(205, 255, 205)
                ).forEach { (label, color) ->
                    menu.add(label).setOnMenuItemClickListener {
                        subtitleColor = color
                        updatePreview()
                        true
                    }
                }
                show()
            }
        }
        sizeSeek.setOnSeekBarChangeListener(previewSeekListener { updatePreview() })
        alphaSeek.setOnSeekBarChangeListener(previewSeekListener { updatePreview() })
        bottomSeek.setOnSeekBarChangeListener(previewSeekListener { updatePreview() })
        panel.addView(sizeLabel)
        panel.addView(sizeSeek)
        panel.addView(alphaLabel)
        panel.addView(alphaSeek)
        panel.addView(bottomLabel)
        panel.addView(bottomSeek)
        panel.addView(colorButton)
        updatePreview()

        val dialog = AlertDialog.Builder(this)
            .setTitle("字幕设置")
            .setView(dialogScroll(panel))
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
                subtitleOverlayEnabled = overlayCheck.isChecked && canOverlay
                snapSeekToCueStarts = snapSeekCheck.isChecked
                subtitlePreferencesStore.setSnapSeekToCueStarts(snapSeekToCueStarts)
                if (overlayCheck.isChecked && !canOverlay) {
                    Toast.makeText(this, "请先授权悬浮窗权限，后台字幕才会显示", Toast.LENGTH_LONG).show()
                }
                subtitleSizeSp = (12 + sizeSeek.progress).toFloat()
                subtitleBackgroundAlpha = alphaSeek.progress
                subtitleBottomDp = 8 + bottomSeek.progress
                applyPlayerSubtitleStyle()
                sendSubtitleSettings()
            }
            .show()
        dialog.window?.setLayout(dialogWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dialogWidth() = minOf(resources.displayMetrics.widthPixels - dp(24), dp(560))

    private fun dialogScroll(content: View) = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(content)
    }

    private fun previewSeekListener(onChanged: () -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChanged()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun subtitleColorName(color: Int): String = when (color) {
        Color.WHITE -> "白色"
        Color.rgb(255, 232, 170) -> "暖黄色"
        Color.rgb(175, 235, 255) -> "青色"
        Color.rgb(205, 255, 205) -> "浅绿色"
        else -> "自定义"
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            Toast.makeText(this, "悬浮字幕权限已可用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendSubtitleSettings() {
        startService(Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_SET_OVERLAY
            putExtra(PlaybackService.EXTRA_OVERLAY_ENABLED, subtitleOverlayEnabled)
        })
        startService(Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_SET_SUBTITLE_STYLE
            putExtra(PlaybackService.EXTRA_SUBTITLE_SIZE, subtitleSizeSp)
            putExtra(PlaybackService.EXTRA_SUBTITLE_COLOR, subtitleColor)
            putExtra(PlaybackService.EXTRA_SUBTITLE_BACKGROUND_ALPHA, subtitleBackgroundAlpha)
            putExtra(PlaybackService.EXTRA_SUBTITLE_BOTTOM, subtitleBottomDp)
        })
        updateSubtitleButtonStyle()
    }

    private fun applyPlayerSubtitleStyle() {
        playerView?.subtitleView?.apply {
            setApplyEmbeddedStyles(false)
            setApplyEmbeddedFontSizes(false)
            setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSizeSp)
            setStyle(
                CaptionStyleCompat(
                    subtitleColor,
                    Color.argb(subtitleBackgroundAlpha, 0, 0, 0),
                    Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    Color.BLACK,
                    null
                )
            )
            setBottomPaddingFraction((subtitleBottomDp / 300f).coerceIn(0.02f, 0.45f))
        }
    }

    private fun setAppVisible(visible: Boolean) {
        startService(Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_SET_APP_VISIBLE
            putExtra(PlaybackService.EXTRA_APP_VISIBLE, visible)
        })
    }

    private fun setGain(db: Int) {
        gainDb = db.coerceIn(0, 12)
        startService(Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_SET_GAIN
            putExtra(PlaybackService.EXTRA_GAIN_DB, gainDb)
        })
    }

    private fun gainLabel(): String = if (gainDb == 0) "增益 原声" else "增益 +${gainDb}dB"

    private fun setResizeMode(mode: Int) {
        currentResizeMode = mode
        playerView?.resizeMode = mode
        playbackControlStore.saveResizeMode(mode)
    }

    private fun speedLabel(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"

    private fun resetVideoScale() {
        videoScale = 1f
        playerView?.scaleX = 1f
        playerView?.scaleY = 1f
    }

    private fun normalPlayerHeight(): Int =
        dp(minOf(235, resources.configuration.screenHeightDp * 2 / 5))

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        episodesPopup?.dismiss()
        if (!isFullscreen) {
            playerView?.apply {
                layoutParams.height = normalPlayerHeight()
                requestLayout()
            }
        }
    }

    private fun setFullscreen(fullscreen: Boolean) {
        if (isFullscreen == fullscreen) return
        isFullscreen = fullscreen
        val root = rootLayout ?: return
        val video = playerView ?: return
        video.setFullscreenButtonState(fullscreen)
        volumePopup?.dismiss()
        episodesPopup?.dismiss()
        episodesButton?.visibility = if (fullscreen) View.VISIBLE else View.GONE
        if (fullscreen) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if (child !== video) child.visibility = View.GONE
            }
            root.setPadding(0, 0, 0, 0)
            video.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            for (index in 0 until root.childCount) {
                root.getChildAt(index).visibility = View.VISIBLE
            }
            root.setPadding(dp(12), dp(10), dp(12), dp(12))
            video.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                normalPlayerHeight()
            )
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        ViewCompat.requestApplyInsets(root)
        video.requestLayout()
    }

    private fun registerFullscreenBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (isFullscreen) setFullscreen(false) else finishAfterTransition()
            }
        }
    }

    private fun updateSpeakerButton() {
        speakerButton?.apply {
            setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
            contentDescription = if (isMuted) "已静音，点击调整音量" else "音量，点击调整"
        }
    }

    private fun updatePlaybackButton() {
        val player = controller
        val playbackRequested = player?.playWhenReady == true &&
            player.playbackState != Player.STATE_ENDED
        playbackButton?.apply {
            setImageResource(
                if (playbackRequested) androidx.media3.ui.R.drawable.exo_icon_pause
                else androidx.media3.ui.R.drawable.exo_icon_play
            )
            contentDescription = if (playbackRequested) "暂停播放" else "播放"
        }
    }

    private fun updateBufferingIndicator() {
        mainHandler.removeCallbacks(showBufferingIndicator)
        playerView?.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        if (controller?.playbackState == Player.STATE_BUFFERING && controller?.playWhenReady == true) {
            // Brief local seeks should not flash a loading wheel; longer waits stay visible.
            mainHandler.postDelayed(showBufferingIndicator, BUFFERING_INDICATOR_DELAY_MS)
        }
    }

    private fun stylePlayerControls() {
        val video = playerView ?: return
        video.findViewById<View>(androidx.media3.ui.R.id.exo_center_controls)?.visibility = View.GONE
        video.findViewById<View>(androidx.media3.ui.R.id.exo_controls_background)?.apply {
            background = ColorDrawable(Color.TRANSPARENT)
        }
        video.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)?.apply {
            background = ColorDrawable(Color.argb(112, 13, 15, 18))
        }
    }

    private fun installCompactController() {
        val video = playerView ?: return
        video.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) {
                video.findViewById<View>(androidx.media3.ui.R.id.exo_center_controls)?.visibility = View.GONE
            }
        })
        stylePlayerControls()
    }

    private fun attachPlaybackButtonToPlayerControls() {
        val video = playerView ?: return
        val timeControls = video.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_time) ?: return
        val position = video.findViewById<View>(androidx.media3.ui.R.id.exo_position) ?: return
        val button = playbackButton ?: return
        if (button.parent === timeControls) return

        (button.parent as? ViewGroup)?.removeView(button)
        val templateParams = video.findViewById<View>(androidx.media3.ui.R.id.exo_settings)
            ?.layoutParams
        val params = templateParams?.let { ViewGroup.LayoutParams(it) }
            ?: ViewGroup.LayoutParams(dp(48), dp(48))
        timeControls.addView(button, timeControls.indexOfChild(position).coerceAtLeast(0), params)
    }

    private fun attachSpeakerButtonToPlayerControls() {
        val fullscreenButton = playerView?.findViewById<View>(
            androidx.media3.ui.R.id.exo_fullscreen
        ) ?: return
        val controls = fullscreenButton.parent as? ViewGroup ?: return
        val speaker = speakerButton ?: return
        if (speaker.parent === controls) return

        (speaker.parent as? ViewGroup)?.removeView(speaker)
        val index = controls.indexOfChild(fullscreenButton)
        if (index < 0) return
        controls.addView(
            speaker,
            index,
            ViewGroup.LayoutParams(fullscreenButton.layoutParams)
        )
    }

    private fun attachSettingsButtonToPlayerControls() {
        val settingsButton = playerView?.findViewById<View>(
            androidx.media3.ui.R.id.exo_settings
        ) ?: return
        settingsButton.contentDescription = "播放器设置"
        settingsButton.setOnClickListener { showPlayerSettingsMenu(settingsButton) }
    }

    private fun attachEpisodesButtonToPlayerControls() {
        if (episodesButton != null) return
        val settings = playerView?.findViewById<View>(androidx.media3.ui.R.id.exo_settings) ?: return
        val controls = settings.parent as? ViewGroup ?: return
        episodesButton = TextView(this).apply {
            text = "选集"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isFocusable = true
            contentDescription = "选集"
            tooltipText = "选集"
            val selectable = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true)
            setBackgroundResource(selectable.resourceId)
            visibility = if (isFullscreen) View.VISIBLE else View.GONE
            setOnClickListener { showFullscreenEpisodes() }
        }.also {
            controls.addView(it, controls.indexOfChild(settings), ViewGroup.LayoutParams(settings.layoutParams))
        }
    }

    private fun showFullscreenEpisodes() {
        val video = playerView ?: return
        if (!isFullscreen || video.width == 0 || video.height == 0) return
        volumePopup?.dismiss()
        episodesPopup?.dismiss()
        episodesPopup = FullscreenEpisodesPopup(
            this,
            activeEntries.toList(),
            controller?.currentMediaItem?.localConfiguration?.uri?.toString(),
            createPreview = { createMediaPreview(it, maxLines = 2, compact = true) },
            onPlay = { playbackCoordinator.playEntry(it) }
        ).also { picker ->
            picker.show(video) {
                episodesPopup = null
                episodesButton?.requestFocus()
                video.showController()
            }
        }
    }

    private fun showVolumePopup(anchor: View) {
        volumePopup?.takeIf { it.isShowing }?.let {
            it.dismiss()
            return
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(34, 39, 46))
                cornerRadius = dp(8).toFloat()
            }
        }
        val volumeLabel = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(4))
        }
        panel.addView(volumeLabel)

        val initialProgress = if (isMuted) 0 else
            ((controller?.volume ?: lastVolume) * 100).roundToInt().coerceIn(0, 100)
        val verticalSlider = VerticalVolumeSlider(this, initialProgress) { value ->
            val volume = value / 100f
            controller?.volume = volume
            if (volume > 0f) {
                lastVolume = volume
                isMuted = false
            } else {
                isMuted = true
            }
            volumeLabel.text = "音量 ${value}%"
            updateSpeakerButton()
            persistControlSettings()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(180))
        }
        volumeLabel.text = "音量 ${initialProgress}%"
        panel.addView(verticalSlider)

        val popupWidth = dp(88)
        val popupHeight = dp(224)
        val popup = PopupWindow(panel, popupWidth, popupHeight, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
            setOnDismissListener {
                if (volumePopup === this) volumePopup = null
            }
        }
        volumePopup = popup
        popup.showAsDropDown(
            anchor,
            -((popupWidth - anchor.width) / 2),
            -popupHeight - anchor.height
        )
    }

    private fun persistControlSettings() {
        playbackControlStore.save(
            PlaybackControlSettings(
                volume = lastVolume,
                muted = isMuted,
                speed = playbackSpeed,
                gainDb = gainDb,
                resizeMode = currentResizeMode
            )
        )
    }

    private fun setTimer(minutes: Int) {
        val safeMinutes = minutes.coerceIn(0, MAX_TIMER_MINUTES)
        startService(Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_SET_TIMER
            putExtra(PlaybackService.EXTRA_MINUTES, safeMinutes)
        })
        updateTimerUi()
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
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(TREE_URI, uri.toString())
            .remove(DIRECTORY_PATH)
            .apply()
        scanDocumentTree(uri)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            showFileDirectoryPicker(
                savedFileDirectory() ?: Environment.getExternalStorageDirectory()
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForAllFilesAccess) {
            waitingForAllFilesAccess = false
            if (hasAllFilesAccess()) {
                showFileDirectoryPicker(
                    savedFileDirectory() ?: Environment.getExternalStorageDirectory()
                )
            } else {
                Toast.makeText(
                    this,
                    "需要允许“所有文件访问”，应用内浏览器才能显示隐藏目录",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Deprecated("Handled for fullscreen before delegating to Activity")
    override fun onBackPressed() {
        if (isFullscreen) {
            setFullscreen(false)
        } else {
            super.onBackPressed()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullscreen) {
            WindowInsetsControllerCompat(window, window.decorView).hide(
                WindowInsetsCompat.Type.systemBars()
            )
        }
    }

    override fun onDestroy() {
        playbackCoordinator.savePosition()
        dismissPlaylistUndo()
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.removeCallbacks(showBufferingIndicator)
        playerView?.keepScreenOn = false
        volumePopup?.dismiss()
        episodesPopup?.dismiss()
        scanFuture?.cancel(true)
        thumbnailLoader.close()
        scanExecutor.shutdownNow()
        controller?.release()
        controllerFuture?.cancel(true)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
        updateScreenAwakeState()
        setAppVisible(true)
    }

    override fun onStop() {
        playbackCoordinator.savePosition()
        isActivityStarted = false
        updateScreenAwakeState()
        setAppVisible(false)
        super.onStop()
    }

    private fun updateScreenAwakeState() {
        playerView?.keepScreenOn = isActivityStarted && controller?.isPlaying == true
    }

    private fun statefulBackground(
        normalColor: Int,
        pressedColor: Int,
        strokeColor: Int,
        radius: Int
    ): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply {
            setColor(color)
            setStroke(dp(1), strokeColor)
            cornerRadius = radius.toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(pressedColor))
            addState(intArrayOf(android.R.attr.state_focused), shape(pressedColor))
            addState(intArrayOf(), shape(normalColor))
        }
    }

    private fun toolbarButtonBackground(selected: Boolean): StateListDrawable =
        statefulBackground(
            normalColor = if (selected) Color.rgb(31, 65, 59) else Color.rgb(27, 33, 40),
            pressedColor = if (selected) Color.rgb(43, 91, 81) else Color.rgb(43, 53, 63),
            strokeColor = if (selected) Color.rgb(63, 137, 123) else Color.rgb(48, 58, 69),
            radius = dp(7)
        )

    private fun updateSubtitleButtonStyle() {
        subtitleButton?.background = toolbarButtonBackground(subtitleOverlayEnabled)
    }

    private fun actionButton(
        label: String,
        iconRes: Int,
        description: String,
        selected: Boolean = false,
        action: (android.view.View) -> Unit
    ): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.rgb(224, 231, 238))
            isAllCaps = false
            gravity = Gravity.CENTER
            minHeight = 0
            minimumHeight = 0
            minWidth = dp(48)
            minimumWidth = dp(48)
            setPadding(dp(8), 0, dp(8), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawablePadding = dp(4)
            contentDescription = description
            background = toolbarButtonBackground(selected)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(48)
            ).apply { marginStart = dp(4) }
            setOnClickListener(action)
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

    private fun handleVideoTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val player = controller
                mainHandler.removeCallbacks(longPressSpeedBoostRunnable)
                touchGestureActive = true
                verticalGestureCaptured = false
                verticalGestureSide = 0
                doubleTapGestureCaptured = false
                val doubleTapSlop = ViewConfiguration.get(this).scaledDoubleTapSlop.toFloat()
                val timeSinceLastTap = event.eventTime - lastVideoTapUpTime
                val isSecondTap = lastVideoTapUpTime > 0L &&
                    timeSinceLastTap in 0L..ViewConfiguration.getDoubleTapTimeout().toLong() &&
                    abs(event.x - lastVideoTapX) <= doubleTapSlop &&
                    abs(event.y - lastVideoTapY) <= doubleTapSlop
                val blockedTouch = isProgressBarTouch(view, event) ||
                    isPlayerControlTouch(view, event)
                doubleTapGestureCaptured = isSecondTap && player?.currentMediaItem != null
                touchGestureEligible = doubleTapGestureCaptured || !blockedTouch
                scrubGestureEligible = player != null &&
                    player.duration > 0L &&
                    touchGestureEligible
                scrubGestureCaptured = false
                scrubStartX = event.x
                scrubStartY = event.y
                scrubStartPosition = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
                scrubTargetPosition = scrubStartPosition
                scrubWasSnapped = false
                scrubDuration = player?.duration?.coerceAtLeast(0L) ?: 0L
                scrubSnapper = if (snapSeekToCueStarts && player != null) {
                    val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                    val cueStarts = activeEntries
                        .firstOrNull { it.uri.toString() == currentUri }
                        ?.subtitles
                        ?.flatMap { it.cueStartTimesMs }
                        .orEmpty()
                    SubtitleSeekSnapper(cueStarts, scrubDuration).takeIf { it.isAvailable }
                } else {
                    null
                }
                if (touchGestureEligible &&
                    !doubleTapGestureCaptured &&
                    player?.isPlaying == true
                ) {
                    mainHandler.postDelayed(longPressSpeedBoostRunnable, LONG_PRESS_SPEED_BOOST_MS)
                }
                if (doubleTapGestureCaptured) return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                mainHandler.removeCallbacks(longPressSpeedBoostRunnable)
                doubleTapGestureCaptured = false
                lastVideoTapUpTime = 0L
                scrubGestureEligible = false
                touchGestureEligible = false
                if (scrubGestureCaptured) {
                    finishScrub(commit = false)
                    return true
                }
                if (verticalGestureCaptured) {
                    finishVerticalGesture()
                    return true
                }
                if (speedBoostActive) {
                    endSpeedBoost()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touchGestureEligible || event.pointerCount != 1) return false
                if (speedBoostActive) return true
                val deltaX = event.x - scrubStartX
                val deltaY = event.y - scrubStartY
                if (doubleTapGestureCaptured) {
                    val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
                    if (abs(deltaX) <= touchSlop && abs(deltaY) <= touchSlop) return true
                    doubleTapGestureCaptured = false
                    lastVideoTapUpTime = 0L
                }
                if (!scrubGestureCaptured) {
                    val horizontal = abs(deltaX) > dp(12) && abs(deltaX) > abs(deltaY) * 1.2f
                    val vertical = abs(deltaY) > dp(12) && abs(deltaY) > abs(deltaX) * 1.2f
                    if (!horizontal && !vertical) return false
                    mainHandler.removeCallbacks(longPressSpeedBoostRunnable)
                    if (horizontal && scrubGestureEligible) {
                        scrubGestureCaptured = true
                        isSeeking = true
                        updateScrubOverlay()
                    } else if (vertical) {
                        beginVerticalGesture(view, event)
                    } else {
                        return false
                    }
                }
                if (verticalGestureCaptured) {
                    updateVerticalGesture(view, event)
                    return true
                }
                if (!scrubGestureCaptured) return false
                val width = view.width.coerceAtLeast(1).toDouble()
                val seekPerViewWidth = (scrubDuration / 10L).coerceAtMost(60_000L)
                val proportionalOffset = (seekPerViewWidth.toDouble() * deltaX / width).toLong()
                val rawTarget = (scrubStartPosition + proportionalOffset)
                    .coerceIn(0L, scrubDuration)
                val snappedTarget = scrubSnapper?.snap(
                    rawTarget,
                    scrubStartPosition,
                    deltaX.compareTo(0f)
                )
                scrubTargetPosition = snappedTarget ?: rawTarget
                scrubWasSnapped = snappedTarget != null && snappedTarget != rawTarget
                timingView?.text = "${formatTime(scrubTargetPosition)} / ${formatTime(scrubDuration)}"
                updateScrubOverlay()
                return true
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressSpeedBoostRunnable)
                touchGestureActive = false
                if (doubleTapGestureCaptured) {
                    doubleTapGestureCaptured = false
                    lastVideoTapUpTime = 0L
                    scrubGestureEligible = false
                    touchGestureEligible = false
                    playbackCoordinator.togglePlayback()
                    playerView?.showController()
                    return true
                }
                if (speedBoostActive) {
                    endSpeedBoost()
                    lastVideoTapUpTime = 0L
                    scrubGestureEligible = false
                    touchGestureEligible = false
                    return true
                }
                if (verticalGestureCaptured) {
                    finishVerticalGesture()
                    lastVideoTapUpTime = 0L
                    scrubGestureEligible = false
                    touchGestureEligible = false
                    return true
                }
                val wasScrubbing = scrubGestureCaptured
                if (wasScrubbing) finishScrub(commit = true)
                val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
                val wasTap = touchGestureEligible &&
                    !wasScrubbing &&
                    abs(event.x - scrubStartX) <= touchSlop &&
                    abs(event.y - scrubStartY) <= touchSlop
                if (wasTap) {
                    lastVideoTapUpTime = event.eventTime
                    lastVideoTapX = event.x
                    lastVideoTapY = event.y
                } else {
                    lastVideoTapUpTime = 0L
                }
                scrubGestureEligible = false
                touchGestureEligible = false
                return wasScrubbing
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressSpeedBoostRunnable)
                touchGestureActive = false
                doubleTapGestureCaptured = false
                lastVideoTapUpTime = 0L
                if (speedBoostActive) endSpeedBoost()
                if (verticalGestureCaptured) finishVerticalGesture()
                val wasScrubbing = scrubGestureCaptured
                if (wasScrubbing) finishScrub(commit = false)
                scrubGestureEligible = false
                touchGestureEligible = false
                return wasScrubbing
            }
        }
        return false
    }

    private fun beginVerticalGesture(view: View, event: MotionEvent) {
        verticalGestureCaptured = true
        verticalGestureSide = if (event.x < view.width / 2f) -1 else 1
        verticalGestureStartValue = if (verticalGestureSide < 0) {
            currentWindowBrightness()
        } else {
            if (isMuted) 0f else (controller?.volume ?: lastVolume)
        }
        updateVerticalGesture(view, event)
    }

    private fun updateVerticalGesture(view: View, event: MotionEvent) {
        if (!verticalGestureCaptured) return
        val deltaFraction = ((scrubStartY - event.y) /
            view.height.coerceAtLeast(1).toFloat()) * VERTICAL_GESTURE_SENSITIVITY
        val value = (verticalGestureStartValue + deltaFraction).coerceIn(0f, 1f)
        val percent = (value * 100f).roundToInt()
        if (verticalGestureSide < 0) {
            setWindowBrightness(value)
            showScrubOverlay("亮度 $percent%", hideAfterMs = null)
        } else {
            setPlayerVolume(value)
            showScrubOverlay("音量 $percent%", hideAfterMs = null)
        }
    }

    private fun finishVerticalGesture() {
        val value = if (verticalGestureSide < 0) {
            currentWindowBrightness()
        } else {
            if (isMuted) 0f else (controller?.volume ?: lastVolume)
        }
        val label = if (verticalGestureSide < 0) "亮度" else "音量"
        showScrubOverlay("$label ${(value * 100f).roundToInt()}%", hideAfterMs = 700L)
        verticalGestureCaptured = false
        verticalGestureSide = 0
    }

    private fun setPlayerVolume(value: Float) {
        val volume = value.coerceIn(0f, 1f)
        controller?.volume = volume
        if (volume > 0f) {
            lastVolume = volume
            isMuted = false
        } else {
            isMuted = true
        }
        updateSpeakerButton()
        persistControlSettings()
    }

    private fun currentWindowBrightness(): Float {
        val windowValue = window.attributes.screenBrightness
        if (windowValue in 0f..1f) return windowValue
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) {
            0.5f
        }
    }

    private fun setWindowBrightness(value: Float) {
        window.attributes = window.attributes.apply {
            screenBrightness = value.coerceIn(0f, 1f)
        }
    }

    private fun isPlayerControlTouch(view: View, event: MotionEvent): Boolean {
        val controlIds = intArrayOf(
            androidx.media3.ui.R.id.exo_bottom_bar,
            androidx.media3.ui.R.id.exo_center_controls,
            androidx.media3.ui.R.id.exo_minimal_controls
        )
        return controlIds.any { id ->
            view.findViewById<View>(id)?.takeIf { it.isShown }?.let {
                isTouchInsideView(it, event)
            } == true
        }
    }

    private fun isTouchInsideView(target: View, event: MotionEvent): Boolean {
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        return event.rawX >= location[0] &&
            event.rawX <= location[0] + target.width &&
            event.rawY >= location[1] &&
            event.rawY <= location[1] + target.height
    }

    private fun isProgressBarTouch(view: View, event: MotionEvent): Boolean {
        val progressBar = view.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
            ?.takeIf { it.isShown }
            ?: view.findViewById<View>(androidx.media3.ui.R.id.exo_progress_placeholder)
                ?.takeIf { it.isShown }
            ?: return false

        val location = IntArray(2)
        progressBar.getLocationOnScreen(location)
        return event.rawX >= location[0] &&
            event.rawX <= location[0] + progressBar.width &&
            event.rawY >= location[1] &&
            event.rawY <= location[1] + progressBar.height
    }

    private fun finishScrub(commit: Boolean) {
        val player = controller
        if (commit && player != null && scrubDuration > 0L) {
            player.seekTo(scrubTargetPosition)
            timingView?.text = "${formatTime(scrubTargetPosition)} / ${formatTime(scrubDuration)}"
            val result = if (scrubWasSnapped) "已对齐字幕起点" else "已跳转"
            showScrubOverlay("$result\n${scrubPositionLabel()}", hideAfterMs = 1_200L)
        } else {
            timingView?.text = "${formatTime(player?.currentPosition ?: 0L)} / ${formatTime(player?.duration ?: 0L)}"
            hideScrubOverlay()
        }
        isSeeking = false
        scrubGestureCaptured = false
        scrubSnapper = null
    }

    private fun updateScrubOverlay() {
        val delta = scrubTargetPosition - scrubStartPosition
        val direction = if (delta >= 0L) "快进" else "回退"
        val signedDelta = if (delta >= 0L) "+${formatTime(delta)}" else "-${formatTime(-delta)}"
        val alignment = if (scrubWasSnapped) "\n字幕起点" else ""
        showScrubOverlay("$direction $signedDelta\n${scrubPositionLabel()}$alignment", hideAfterMs = null)
    }

    private fun scrubPositionLabel(): String =
        "目标 ${formatTime(scrubTargetPosition)} / ${formatTime(scrubDuration)}"

    private fun showScrubOverlay(text: String, hideAfterMs: Long?) {
        val overlay = scrubOverlayView ?: return
        mainHandler.removeCallbacks(hideScrubOverlayRunnable)
        overlay.text = text
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        if (hideAfterMs != null) {
            mainHandler.postDelayed(hideScrubOverlayRunnable, hideAfterMs)
        }
    }

    private val hideScrubOverlayRunnable = Runnable {
        scrubOverlayView?.visibility = View.GONE
    }

    private fun hideScrubOverlay() {
        mainHandler.removeCallbacks(hideScrubOverlayRunnable)
        scrubOverlayView?.visibility = View.GONE
    }

    private fun timerRemainingMs(): Long {
        val deadline = sleepTimerStore.deadlineMs()
        return (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun formatTimerRemaining(milliseconds: Long): String {
        val totalSeconds = ((milliseconds + 999L) / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%02d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
        } else {
            "%02d:%02d".format(Locale.ROOT, minutes, seconds)
        }
    }

    private fun updateTimerUi() {
        val remaining = timerRemainingMs()
        timerCountdownView?.text = if (remaining > 0L) {
            "剩余时间\n${formatTimerRemaining(remaining)}"
        } else {
            "当前未设置定时"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}

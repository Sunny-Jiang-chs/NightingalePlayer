package com.myp.sleepplayer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.ScaleGestureDetector
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.Locale
import kotlin.math.abs
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
        private const val REQUEST_TREE = 1001
        private const val REQUEST_NOTIFICATIONS = 1002
        private const val REQUEST_STORAGE = 1003
        private const val PREFS = "library"
        private const val TREE_URI = "tree_uri"
        private const val DIRECTORY_PATH = "directory_path"
        private const val LAST_MEDIA_URI = "last_media_uri"
        private const val LAST_MEDIA_POSITION = "last_media_position"
        private const val LAST_MEDIA_PLAYING = "last_media_playing"
        private const val TIMER_PREFS = "sleep_timer"
        private const val TIMER_DEADLINE = "deadline_ms"
        private const val MAX_TIMER_MINUTES = 24 * 60
    }

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var playerView: PlayerView? = null
    private var rootLayout: LinearLayout? = null
    private var timingView: TextView? = null
    private var libraryContainer: LinearLayout? = null
    private var timerCountdownView: TextView? = null
    private var timerDialog: AlertDialog? = null
    private var speakerButton: ImageButton? = null
    private var volumePopup: PopupWindow? = null
    private var scrubOverlayView: TextView? = null
    private var subtitleButton: Button? = null
    private var loadedEntries: List<MediaEntry> = emptyList()
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var videoScale = 1f
    private var lastVolume = 1f
    private var isMuted = false
    private var playbackSpeed = 1f
    private var gainDb = 0
    private var subtitleOverlayEnabled = false
    private var subtitleSizeSp = 20f
    private var subtitleColor = Color.WHITE
    private var subtitleBackgroundAlpha = 150
    private var subtitleBottomDp = 42
    private var isFullscreen = false
    private var waitingForAllFilesAccess = false
    private var isSeeking = false
    private var scrubGestureEligible = false
    private var scrubGestureCaptured = false
    private var scrubStartX = 0f
    private var scrubStartY = 0f
    private var scrubStartPosition = 0L
    private var scrubTargetPosition = 0L
    private var scrubDuration = 0L
    private var scaleDetector: ScaleGestureDetector? = null

    private val controlPrefs by lazy { getSharedPreferences("playback_controls", MODE_PRIVATE) }

    private val progressTicker = object : Runnable {
        override fun run() {
            val player = controller
            if (player != null) {
                val position = formatTime(player.currentPosition)
                val duration = formatTime(player.duration)
                if (!isSeeking) {
                    timingView?.text = "$position / $duration"
                }
            }
            updateTimerUi()
            mainHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastVolume = controlPrefs.getFloat("volume", 1f).coerceIn(0f, 1f)
        isMuted = controlPrefs.getBoolean("muted", false)
        playbackSpeed = controlPrefs.getFloat("speed", 1f).coerceIn(0.5f, 2f)
        gainDb = controlPrefs.getInt("gain_db", 0).coerceIn(0, 12)
        val subtitlePrefs = getSharedPreferences("subtitle_preferences", MODE_PRIVATE)
        subtitleOverlayEnabled = subtitlePrefs.getBoolean("overlay_enabled", false)
        subtitleSizeSp = subtitlePrefs.getFloat("subtitle_size", 20f).coerceIn(12f, 36f)
        subtitleColor = subtitlePrefs.getInt("subtitle_color", Color.WHITE)
        subtitleBackgroundAlpha = subtitlePrefs.getInt("subtitle_background_alpha", 150).coerceIn(0, 255)
        subtitleBottomDp = subtitlePrefs.getInt("subtitle_bottom", 42).coerceIn(8, 180)
        currentResizeMode = controlPrefs.getInt(
            "resize_mode",
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        )
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
        header.addView(actionButton("选择目录") { showDirectorySourceMenu(it) })
        subtitleButton = actionButton(if (subtitleOverlayEnabled) "字幕 ✓" else "字幕") {
            showSubtitleSettings()
        }
        header.addView(subtitleButton)
        root.addView(header)

        playerView = object : PlayerView(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                scaleDetector?.onTouchEvent(event)
                handleVideoTouch(this, event)
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            resizeMode = currentResizeMode
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setKeepContentOnPlayerReset(true)
            useController = true
            controllerShowTimeoutMs = 5_000
            setFullscreenButtonClickListener { fullscreen -> setFullscreen(fullscreen) }
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(235)
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
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                playerView?.player = controller
                attachSpeakerButtonToPlayerControls()
                attachSettingsButtonToPlayerControls()
                controller?.volume = if (isMuted) 0f else lastVolume
                updateSpeakerButton()
                setGain(gainDb)
                controller?.addListener(object : Player.Listener {
                    override fun onPlaybackParametersChanged(
                        playbackParameters: androidx.media3.common.PlaybackParameters
                    ) {
                        updatePlaybackSpeedUi(playbackParameters.speed)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Toast.makeText(
                            this@MainActivity,
                            "播放失败：${error.errorCodeName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
                controller?.setPlaybackSpeed(playbackSpeed)
                if (loadedEntries.isNotEmpty()) applyPlaylist()
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
        showFileDirectoryPicker(Environment.getExternalStorageDirectory())
    }

    private fun showFileDirectoryPicker(initialDirectory: File) {
        var currentDirectory = initialDirectory
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }
        val pathView = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            textSize = 13f
            setPadding(0, 0, 0, dp(6))
        }
        val upButton = Button(this).apply {
            text = "↑ 上一级"
            isAllCaps = false
        }
        val directoryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            addView(directoryContainer)
        }
        panel.addView(pathView)
        panel.addView(upButton)
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420))
        )

        fun renderDirectory() {
            pathView.text = currentDirectory.absolutePath
            val storageRoot = Environment.getExternalStorageDirectory().canonicalFile
            upButton.isEnabled = currentDirectory.canonicalFile != storageRoot
            directoryContainer.removeAllViews()
            val directories = try {
                currentDirectory.listFiles()
                    ?.filter { it.isDirectory && it.canRead() }
                    ?.sortedWith(compareBy<File> { !it.name.startsWith('.') }
                        .thenBy { it.name.lowercase(Locale.ROOT) })
                    .orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
            if (directories.isEmpty()) {
                directoryContainer.addView(TextView(this).apply {
                    text = "此目录中没有可访问的子目录"
                    setPadding(0, dp(16), 0, dp(16))
                })
            } else {
                directories.forEach { directory ->
                    directoryContainer.addView(Button(this).apply {
                        text = if (directory.name.startsWith('.')) {
                            "● ${directory.name}  （隐藏）"
                        } else {
                            "▸ ${directory.name}"
                        }
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        isAllCaps = false
                        setOnClickListener {
                            currentDirectory = directory
                            renderDirectory()
                        }
                    })
                }
            }
        }
        upButton.setOnClickListener {
            val storageRoot = Environment.getExternalStorageDirectory().canonicalFile
            val parent = currentDirectory.parentFile?.canonicalFile
            if (parent != null && parent.absolutePath.startsWith(storageRoot.absolutePath)) {
                currentDirectory = parent
                renderDirectory()
            }
        }
        renderDirectory()
        AlertDialog.Builder(this)
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
    }

    private fun loadSavedTree() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val savedPath = prefs.getString(DIRECTORY_PATH, null)
        if (savedPath != null && hasAllFilesAccess()) {
            scanFileDirectory(File(savedPath))
            return
        }
        prefs.getString(TREE_URI, null)?.let { scanDocumentTree(Uri.parse(it)) }
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun scanDocumentTree(uri: Uri) {
        ioExecutor.execute {
            val root = DocumentFile.fromTreeUri(this, uri)
            val entries = root?.let { scanDocumentDirectory(it) }.orEmpty()
            runOnUiThread { finishScan(entries) }
        }
    }

    private fun scanFileDirectory(directory: File) {
        ioExecutor.execute {
            val entries = scanFileDirectoryEntries(directory)
            runOnUiThread { finishScan(entries) }
        }
    }

    private fun scanFileDirectoryEntries(directory: File): List<MediaEntry> {
        val children = try {
            directory.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        val subtitles = children
            .filter { it.isFile && isExtension(it.name, "vtt") }
            .map { SubtitleFile(it.name, Uri.fromFile(it)) }
        val entries = children
            .filter { it.isFile && isSupportedMedia(it.name) }
            .map { media ->
                MediaEntry(
                    media.name,
                    Uri.fromFile(media),
                    mediaMimeType(media.name),
                    matchSubtitles(media.name, subtitles)
                )
            }
        return entries + children
            .filter { it.isDirectory && it.canRead() }
            .flatMap { scanFileDirectoryEntries(it) }
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

    private fun finishScan(entries: List<MediaEntry>) {
        loadedEntries = entries.sortedBy { it.name.lowercase(Locale.ROOT) }
        renderLibrary()
        applyPlaylist()
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
        val videoEntries = loadedEntries.withIndex().filter { it.value.isVideo }
        val audioEntries = loadedEntries.withIndex().filter { !it.value.isVideo }
        if (videoEntries.isNotEmpty()) {
            addLibrarySectionHeader(container, "MP4 视频预览（${videoEntries.size}）")
            videoEntries.forEach { indexed ->
                addLibraryEntry(container, indexed.index, indexed.value)
            }
        }
        if (audioEntries.isNotEmpty()) {
            addLibrarySectionHeader(container, "音频播放（${audioEntries.size}）")
            audioEntries.forEach { indexed ->
                addLibraryEntry(container, indexed.index, indexed.value)
            }
        }
    }

    private fun addLibrarySectionHeader(container: LinearLayout, title: String) {
        container.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(6))
        })
    }

    private fun addLibraryEntry(
        container: LinearLayout,
        index: Int,
        entry: MediaEntry
    ) {
        val subtitleHint = if (entry.subtitles.isEmpty()) "" else "  ·  VTT ${entry.subtitles.size}"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        if (entry.isVideo) {
            val thumbnail = ImageView(this).apply {
                setBackgroundColor(Color.rgb(32, 36, 42))
                setImageResource(android.R.drawable.ic_media_play)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = "${entry.name} 封面"
                layoutParams = LinearLayout.LayoutParams(dp(104), dp(59)).apply {
                    marginEnd = dp(8)
                }
            }
            row.addView(thumbnail)
            loadVideoThumbnail(entry, thumbnail)
        }
        row.addView(Button(this).apply {
            text = "${entry.name}$subtitleHint"
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { playEntry(index) }
        })
        container.addView(row)
    }

    private fun loadVideoThumbnail(entry: MediaEntry, target: ImageView) {
        val uriKey = entry.uri.toString()
        target.tag = uriKey
        ioExecutor.execute {
            var thumbnail: Bitmap? = null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, entry.uri)
                val frame = retriever.getFrameAtTime(
                    1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (frame != null) {
                    thumbnail = Bitmap.createScaledBitmap(frame, dp(240), dp(135), true)
                    if (thumbnail !== frame) frame.recycle()
                }
            } catch (_: Exception) {
                // Keep the media placeholder when the source has no readable frame.
            } finally {
                try {
                    retriever.release()
                } catch (_: RuntimeException) {
                    // Ignore release failures from malformed media.
                }
            }
            val result = thumbnail ?: return@execute
            runOnUiThread {
                if (!isDestroyed && target.tag == uriKey) {
                    target.scaleType = ImageView.ScaleType.CENTER_CROP
                    target.clearColorFilter()
                    target.setImageBitmap(result)
                } else {
                    result.recycle()
                }
            }
        }
    }

    private fun playEntry(index: Int) {
        controller?.seekToDefaultPosition(index)
        controller?.play()
    }

    private fun showTimerMenu(anchor: android.view.View) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val countdownView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(12))
        }
        panel.addView(countdownView)
        panel.addView(TextView(this).apply {
            text = "快速设置"
            textSize = 13f
            setTextColor(Color.rgb(171, 181, 196))
            setPadding(0, 0, 0, dp(4))
        })
        listOf(15, 30, 60, 90).forEach { minutes ->
            panel.addView(Button(this).apply {
                text = "${minutes} 分钟"
                isAllCaps = false
                setOnClickListener { setTimer(minutes) }
            })
        }
        panel.addView(Button(this).apply {
            text = "关闭定时"
            isAllCaps = false
            setOnClickListener { setTimer(0) }
        })
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
        panel.addView(customInput)
        panel.addView(Button(this).apply {
            text = "设置自定义定时"
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
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("睡眠定时")
            .setView(panel)
            .setNegativeButton("关闭页面", null)
            .create()
        timerCountdownView = countdownView
        timerDialog = dialog
        dialog.setOnDismissListener {
            if (timerCountdownView === countdownView) timerCountdownView = null
            if (timerDialog === dialog) timerDialog = null
        }
        dialog.show()
        updateTimerUi()
    }

    private fun setPlaybackSpeed(speed: Float) {
        val safeSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        controller?.setPlaybackSpeed(safeSpeed)
        updatePlaybackSpeedUi(safeSpeed)
    }

    private fun updatePlaybackSpeedUi(speed: Float) {
        playbackSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        controlPrefs.edit().putFloat("speed", playbackSpeed).apply()
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
        }
        panel.addView(overlayCheck)
        panel.addView(Button(this).apply {
            text = "授权悬浮字幕显示"
            isAllCaps = false
            setOnClickListener { requestOverlayPermission() }
        })

        val sizeLabel = TextView(this)
        val sizeSeek = SeekBar(this).apply {
            max = 24
            progress = (subtitleSizeSp - 12f).roundToInt()
        }
        val alphaLabel = TextView(this)
        val alphaSeek = SeekBar(this).apply {
            max = 255
            progress = subtitleBackgroundAlpha
        }
        val bottomLabel = TextView(this)
        val bottomSeek = SeekBar(this).apply {
            max = 172
            progress = subtitleBottomDp - 8
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
            preview.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
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

        AlertDialog.Builder(this)
            .setTitle("字幕设置")
            .setView(panel)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
                subtitleOverlayEnabled = overlayCheck.isChecked && canOverlay
                if (overlayCheck.isChecked && !canOverlay) {
                    Toast.makeText(this, "请先授权悬浮窗权限，后台字幕才会显示", Toast.LENGTH_LONG).show()
                }
                subtitleSizeSp = (12 + sizeSeek.progress).toFloat()
                subtitleBackgroundAlpha = alphaSeek.progress
                subtitleBottomDp = 8 + bottomSeek.progress
                getSharedPreferences("subtitle_preferences", MODE_PRIVATE).edit()
                    .putBoolean("overlay_enabled", subtitleOverlayEnabled)
                    .putFloat("subtitle_size", subtitleSizeSp)
                    .putInt("subtitle_color", subtitleColor)
                    .putInt("subtitle_background_alpha", subtitleBackgroundAlpha)
                    .putInt("subtitle_bottom", subtitleBottomDp)
                    .apply()
                applyPlayerSubtitleStyle()
                sendSubtitleSettings()
            }
            .show()
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
        subtitleButton?.text = if (subtitleOverlayEnabled) "字幕 ✓" else "字幕"
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
        controlPrefs.edit().putInt("resize_mode", mode).apply()
    }

    private fun speedLabel(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"

    private fun resetVideoScale() {
        videoScale = 1f
        playerView?.scaleX = 1f
        playerView?.scaleY = 1f
    }

    private fun setFullscreen(fullscreen: Boolean) {
        if (isFullscreen == fullscreen) return
        isFullscreen = fullscreen
        val root = rootLayout ?: return
        val video = playerView ?: return
        if (fullscreen) {
            volumePopup?.dismiss()
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
                dp(235)
            )
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
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

    private fun attachSpeakerButtonToPlayerControls() {
        val settingsButton = playerView?.findViewById<View>(
            androidx.media3.ui.R.id.exo_settings
        ) ?: return
        val controls = settingsButton.parent as? ViewGroup ?: return
        val speaker = speakerButton ?: return
        if (speaker.parent === controls) return

        (speaker.parent as? ViewGroup)?.removeView(speaker)
        val index = controls.indexOfChild(settingsButton)
        if (index < 0) return
        controls.addView(
            speaker,
            index,
            ViewGroup.LayoutParams(settingsButton.layoutParams)
        )
    }

    private fun attachSettingsButtonToPlayerControls() {
        val settingsButton = playerView?.findViewById<View>(
            androidx.media3.ui.R.id.exo_settings
        ) ?: return
        settingsButton.contentDescription = "播放器设置"
        settingsButton.setOnClickListener { showPlayerSettingsMenu(settingsButton) }
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
        controlPrefs.edit()
            .putFloat("volume", lastVolume)
            .putBoolean("muted", isMuted)
            .putFloat("speed", playbackSpeed)
            .putInt("gain_db", gainDb)
            .apply()
    }

    private fun setTimer(minutes: Int) {
        val safeMinutes = minutes.coerceIn(0, MAX_TIMER_MINUTES)
        getSharedPreferences(TIMER_PREFS, MODE_PRIVATE).edit().apply {
            if (safeMinutes == 0) {
                remove(TIMER_DEADLINE)
            } else {
                putLong(TIMER_DEADLINE, System.currentTimeMillis() + safeMinutes * 60_000L)
            }
        }.apply()
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
            showFileDirectoryPicker(Environment.getExternalStorageDirectory())
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForAllFilesAccess) {
            waitingForAllFilesAccess = false
            if (hasAllFilesAccess()) {
                showFileDirectoryPicker(Environment.getExternalStorageDirectory())
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
        savePlaybackPosition()
        mainHandler.removeCallbacks(progressTicker)
        volumePopup?.dismiss()
        ioExecutor.shutdownNow()
        controller?.release()
        controllerFuture?.cancel(true)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        setAppVisible(true)
    }

    override fun onStop() {
        savePlaybackPosition()
        setAppVisible(false)
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

    private fun handleVideoTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val player = controller
                scrubGestureEligible = player != null &&
                    player.currentMediaItem?.localConfiguration?.mimeType == MimeTypes.VIDEO_MP4 &&
                    player.duration > 0L
                scrubGestureCaptured = false
                scrubStartX = event.x
                scrubStartY = event.y
                scrubStartPosition = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
                scrubTargetPosition = scrubStartPosition
                scrubDuration = player?.duration?.coerceAtLeast(0L) ?: 0L
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                scrubGestureEligible = false
                if (scrubGestureCaptured) finishScrub(commit = false)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scrubGestureEligible || event.pointerCount != 1) return false
                val deltaX = event.x - scrubStartX
                val deltaY = event.y - scrubStartY
                if (!scrubGestureCaptured) {
                    val horizontal = abs(deltaX) > dp(12) && abs(deltaX) > abs(deltaY) * 1.2f
                    if (!horizontal) return false
                    scrubGestureCaptured = true
                    isSeeking = true
                    updateScrubOverlay()
                }
                val width = view.width.coerceAtLeast(1).toDouble()
                val seekPerViewWidth = (scrubDuration / 10L).coerceAtMost(60_000L)
                val proportionalOffset = (seekPerViewWidth.toDouble() * deltaX / width).toLong()
                scrubTargetPosition = (scrubStartPosition + proportionalOffset)
                    .coerceIn(0L, scrubDuration)
                timingView?.text = "${formatTime(scrubTargetPosition)} / ${formatTime(scrubDuration)}"
                updateScrubOverlay()
            }

            MotionEvent.ACTION_UP -> {
                if (scrubGestureCaptured) finishScrub(commit = true)
                scrubGestureEligible = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (scrubGestureCaptured) finishScrub(commit = false)
                scrubGestureEligible = false
            }
        }
        return false
    }

    private fun finishScrub(commit: Boolean) {
        val player = controller
        if (commit && player != null && scrubDuration > 0L) {
            player.seekTo(scrubTargetPosition)
            timingView?.text = "${formatTime(scrubTargetPosition)} / ${formatTime(scrubDuration)}"
            showScrubOverlay("已跳转\n${scrubPositionLabel()}", hideAfterMs = 1_200L)
        } else {
            timingView?.text = "${formatTime(player?.currentPosition ?: 0L)} / ${formatTime(player?.duration ?: 0L)}"
            hideScrubOverlay()
        }
        isSeeking = false
        scrubGestureCaptured = false
    }

    private fun updateScrubOverlay() {
        val delta = scrubTargetPosition - scrubStartPosition
        val direction = if (delta >= 0L) "快进" else "回退"
        val signedDelta = if (delta >= 0L) "+${formatTime(delta)}" else "-${formatTime(-delta)}"
        showScrubOverlay("$direction $signedDelta\n${scrubPositionLabel()}", hideAfterMs = null)
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
        val deadline = getSharedPreferences(TIMER_PREFS, MODE_PRIVATE)
            .getLong(TIMER_DEADLINE, 0L)
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

    private data class SubtitleFile(val name: String, val uri: Uri) {
        fun isDefaultFor(videoName: String): Boolean = isDefaultSubtitle(videoName, name)
    }

    private data class MediaEntry(
        val name: String,
        val uri: Uri,
        val mimeType: String,
        val subtitles: List<SubtitleFile>
    ) {
        val isVideo: Boolean
            get() = mimeType == MimeTypes.VIDEO_MP4

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

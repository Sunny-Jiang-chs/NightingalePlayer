package com.myp.sleepplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.annotation.MainThread
import com.myp.sleepplayer.media.MediaEntry
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import kotlin.math.roundToInt

/** UI-thread bindings with one bounded cache and one decode per visible URI. */
internal class VideoThumbnailLoader(context: Context) {
    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2) as ThreadPoolExecutor
    private val width = (112 * context.resources.displayMetrics.density).roundToInt().coerceIn(160, 384)
    private val height = width * 9 / 16
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 24).coerceIn(2L * 1024 * 1024, 12L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        // Views can still display an evicted bitmap; leave its lifetime to the GC.
    }
    private val targets = WeakHashMap<ImageView, Target>()
    private val observers = IdentityHashMap<ViewTreeObserver, VisibilityObserver>()
    private val pending = mutableMapOf<String, Decode>()
    private var closed = false

    @MainThread
    fun load(entry: MediaEntry, target: ImageView) {
        targets.remove(target)?.dispose()
        if (closed || !entry.isVideo) return
        val request = Target(target, entry.uri.toString())
        targets[target] = request
        target.addOnAttachStateChangeListener(request)
        if (target.isAttachedToWindow) request.onViewAttachedToWindow(target)
    }

    /** Stop subscriptions for the old page, retaining reusable decoded thumbnails. */
    @MainThread
    fun cancelPending() {
        targets.values.toList().forEach { it.dispose() }
        targets.clear()
        observers.values.toList().forEach { it.dispose() }
        pending.values.toList().forEach(::cancel)
        pending.clear()
    }

    @MainThread
    fun close() {
        if (closed) return
        closed = true
        cancelPending()
        executor.shutdownNow()
        cache.evictAll()
    }

    private inner class Target(view: ImageView, val uri: String) : View.OnAttachStateChangeListener {
        val view = WeakReference(view)
        var decode: Decode? = null
        private var observer: VisibilityObserver? = null
        private var displayed: Bitmap? = null
        private var failed = false
        private val visibleRect = Rect()

        override fun onViewAttachedToWindow(view: View) {
            if (closed) return
            val tree = view.viewTreeObserver
            observer = observers.getOrPut(tree) { VisibilityObserver(tree) }.also { it.targets.add(this) }
            refresh()
        }

        override fun onViewDetachedFromWindow(view: View) {
            stopObserving()
            unsubscribe()
            clearImage()
        }

        fun refresh() {
            val image = view.get()
            if (image == null) {
                dispose()
                return
            }
            if (!image.isAttachedToWindow || !image.isShown || !image.getGlobalVisibleRect(visibleRect)) {
                unsubscribe()
                clearImage()
                return
            }
            if (displayed != null || failed) return
            cache.get(uri)?.let { show(it); return }
            if (decode != null) return
            val work = pending[uri] ?: Decode(uri).also { work ->
                pending[uri] = work
                work.future = executor.submit {
                    val bitmap = decodeFrame(uri)
                    mainHandler.post { complete(work, bitmap) }
                }
            }
            work.targets.add(this)
            decode = work
        }

        fun show(bitmap: Bitmap?) {
            decode = null
            if (bitmap == null) {
                failed = true
                return
            }
            val image = view.get() ?: return
            if (!image.isAttachedToWindow || !image.isShown || !image.getGlobalVisibleRect(visibleRect)) return
            displayed = bitmap
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.clearColorFilter()
            image.setImageBitmap(bitmap)
        }

        fun dispose() {
            view.get()?.removeOnAttachStateChangeListener(this)
            stopObserving()
            unsubscribe()
            clearImage()
        }

        private fun clearImage() {
            if (displayed == null) return
            displayed = null
            view.get()?.setImageResource(android.R.drawable.ic_media_play)
        }

        private fun stopObserving() {
            observer?.let {
                it.targets.remove(this)
                if (it.targets.isEmpty()) it.dispose()
            }
            observer = null
        }

        private fun unsubscribe() {
            decode?.let {
                it.targets.remove(this)
                if (it.targets.isEmpty()) cancel(it)
            }
            decode = null
        }
    }

    /** Share visibility callbacks across all targets in the same window. */
    private inner class VisibilityObserver(private val tree: ViewTreeObserver) :
        ViewTreeObserver.OnScrollChangedListener, ViewTreeObserver.OnGlobalLayoutListener {
        val targets = mutableSetOf<Target>()
        private var scheduled = false
        private val refresh = Runnable {
            scheduled = false
            targets.toList().forEach { it.refresh() }
        }

        init {
            tree.addOnScrollChangedListener(this)
            tree.addOnGlobalLayoutListener(this)
        }

        override fun onScrollChanged() = scheduleRefresh()
        override fun onGlobalLayout() = scheduleRefresh()

        private fun scheduleRefresh() {
            if (scheduled) return
            scheduled = true
            // Coalesce scroll/layout events instead of walking every preview each frame.
            mainHandler.postDelayed(refresh, 64L)
        }

        fun dispose() {
            mainHandler.removeCallbacks(refresh)
            scheduled = false
            if (tree.isAlive) {
                tree.removeOnScrollChangedListener(this)
                tree.removeOnGlobalLayoutListener(this)
            }
            observers.remove(tree)
        }
    }

    private inner class Decode(val uri: String) {
        val targets = mutableSetOf<Target>()
        var future: Future<*>? = null
    }

    private fun cancel(work: Decode) {
        if (pending[work.uri] === work) pending.remove(work.uri)
        work.future?.let {
            it.cancel(true)
            (it as? Runnable)?.let(executor::remove)
        }
    }

    private fun complete(work: Decode, bitmap: Bitmap?) {
        if (closed || pending[work.uri] !== work) return
        pending.remove(work.uri)
        if (bitmap != null) cache.put(work.uri, bitmap)
        work.targets.toList().forEach { it.show(bitmap) }
        work.targets.clear()
    }

    private fun decodeFrame(uri: String): Bitmap? {
        if (Thread.currentThread().isInterrupted) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
            } else {
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                    val ratio = minOf(width.toFloat() / frame.width, height.toFloat() / frame.height, 1f)
                    Bitmap.createScaledBitmap(frame, (frame.width * ratio).roundToInt().coerceAtLeast(1),
                        (frame.height * ratio).roundToInt().coerceAtLeast(1), true).also {
                        if (it !== frame) frame.recycle()
                    }
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: RuntimeException) {
                // A broken source should keep its placeholder.
            }
        }
    }
}

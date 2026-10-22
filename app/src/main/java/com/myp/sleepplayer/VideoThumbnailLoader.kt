package com.myp.sleepplayer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.annotation.MainThread
import com.myp.sleepplayer.media.MediaEntry
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import kotlin.math.roundToInt

/** UI-thread bindings with one bounded cache and visible-first, nearby prefetch. */
internal class VideoThumbnailLoader(context: Context) {
    private companion object {
        const val TAG = "VideoThumbnails"
        const val MAX_AUTOMATIC_RETRIES = 3
        const val FIRST_RETRY_DELAY_MS = 750L
        const val MAX_RETRY_DELAY_MS = 8_000L
        const val RETRY_AFTER_REVISIT_MS = 30_000L
        const val SLOW_STAGE_THRESHOLD_MS = 500L
        const val DIAGNOSTIC_INTERVAL_MS = 5_000L
        const val MAX_TRACKED_BITMAPS = 4_096
        const val STATE_VISIBLE = 1
        const val STATE_DISPLAYED = 2
        const val STATE_FAILED = 4
        const val STATE_ATTACHED = 8
    }

    // Release builds can opt in with log.tag.VideoThumbnails=DEBUG. No media
    // paths or filenames are included in either diagnostic mode.
    private val diagnosticsEnabled =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 || Log.isLoggable(TAG, Log.DEBUG)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(1) as ThreadPoolExecutor
    // Cached covers must not wait behind an active native video decode.
    private val cacheExecutor = Executors.newFixedThreadPool(1) as ThreadPoolExecutor
    private val decoder = RemoteThumbnailDecoder(context)
    private val diskCache = ThumbnailDiskCache(context)
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
    private val readyDecodes = ArrayDeque<Decode>()
    private var activeDecode: Decode? = null
    private val pendingCacheWrites = AtomicInteger()
    private val decodeStarted = AtomicLong()
    private val decodeCompleted = AtomicLong()
    private val decodeNull = AtomicLong()
    private val decodeErrors = AtomicLong()
    private val cancelledBeforeStart = AtomicLong()
    private val cancelledRunning = AtomicLong()
    private val decodedBitmaps = AtomicLong()
    private val bitmapTrackingDropped = AtomicLong()
    private val lastBitmap = AtomicReference("none")
    private val largestBitmap = AtomicReference<BitmapDiagnostic?>(null)
    private val maxBitmapWidth = AtomicLong()
    private val maxBitmapHeight = AtomicLong()
    private val bitmapReferenceQueue = ReferenceQueue<Bitmap>()
    private val trackedBitmaps = ConcurrentHashMap<BitmapReference, Boolean>()
    private var targetRefreshes = 0L
    private var visibilityRefreshes = 0L
    private var imagesShown = 0L
    private var imagesCleared = 0L
    private var diagnosticsScheduled = false
    private val diagnosticReport = object : Runnable {
        override fun run() {
            diagnosticsScheduled = false
            if (closed) return
            logDiagnostics("periodic")
            scheduleDiagnostics()
        }
    }
    private var closed = false
    private var browseGeneration = 0L

    private class BitmapReference(bitmap: Bitmap, queue: ReferenceQueue<Bitmap>, val bytes: Int) :
        WeakReference<Bitmap>(bitmap, queue)

    private class BitmapDiagnostic(val width: Int, val height: Int, val config: String, val bytes: Int) {
        override fun toString() = "${width}x$height/$config/$bytes"
    }

    @MainThread
    fun load(entry: MediaEntry, target: ImageView) {
        targets.remove(target)?.dispose()
        if (closed || !entry.isVideo) return
        val request = Target(target, entry.uri.toString(), entry.name)
        targets[target] = request
        target.addOnAttachStateChangeListener(request)
        if (target.isAttachedToWindow) request.onViewAttachedToWindow(target)
        scheduleDiagnostics()
    }

    /** Stop old-page subscriptions; the next visit revalidates persistent covers. */
    @MainThread
    fun cancelPending() {
        browseGeneration++
        targets.values.toList().forEach { it.dispose() }
        targets.clear()
        observers.values.toList().forEach { it.dispose() }
        pending.values.toList().forEach(::cancel)
        // A new directory visit revalidates file revisions off the UI thread.
        // A previous in-flight decode may still populate the disk cache, but
        // must not publish an unvalidated image into the next visit's memory cache.
        pending.clear()
        cache.evictAll()
        logDiagnostics("cancelPending")
    }

    @MainThread
    fun close() {
        if (closed) return
        closed = true
        cancelPending()
        activeDecode?.task?.cancel(true)
        executor.shutdownNow()
        cacheExecutor.shutdownNow()
        decoder.close()
        cache.evictAll()
        mainHandler.removeCallbacks(diagnosticReport)
        diagnosticsScheduled = false
        logDiagnostics("close")
    }

    private inner class Target(view: ImageView, val uri: String, private val name: String) :
        View.OnAttachStateChangeListener {
        val view = WeakReference(view)
        var decode: Decode? = null
        private var observer: VisibilityObserver? = null
        private var displayed: Bitmap? = null
        private var failed = false
        private var retryCount = 0
        private var retryAtUptimeMs = 0L
        private var retryRunnable: Runnable? = null
        private var wasInWindow = false
        private val visibleRect = Rect()
        private var placeholderFailed: Boolean? = null

        init {
            showPlaceholder(false)
        }

        override fun onViewAttachedToWindow(view: View) {
            if (closed) return
            val tree = view.viewTreeObserver
            observer = observers.getOrPut(tree) { VisibilityObserver(tree) }.also { it.targets.add(this) }
            refresh()
        }

        override fun onViewDetachedFromWindow(view: View) {
            wasInWindow = false
            cancelRetry()
            stopObserving()
            unsubscribe()
            clearImage()
        }

        fun refresh() {
            if (diagnosticsEnabled) targetRefreshes++
            val image = view.get()
            if (image == null) {
                dispose()
                return
            }
            if (previewDistance() == null) {
                wasInWindow = false
                cancelRetry()
                unsubscribe()
                clearImage()
                return
            }
            if (!wasInWindow) {
                wasInWindow = true
                if (failed && SystemClock.uptimeMillis() >= retryAtUptimeMs) {
                    // A later visit gets a fresh bounded retry budget.
                    retryCount = 0
                    failed = false
                }
            }
            if (displayed != null) return
            // Another view bound to this URI may already have decoded it while
            // this target was in its retry backoff.
            cache.get(uri)?.let { show(it); return }
            if (failed) {
                val now = SystemClock.uptimeMillis()
                if (retryCount > MAX_AUTOMATIC_RETRIES) return
                if (now < retryAtUptimeMs) {
                    scheduleRetry(retryAtUptimeMs - now)
                    return
                }
                failed = false
            }
            if (decode != null) return
            showPlaceholder(false)
            val existing = pending[uri]
            val work = existing ?: Decode(uri).also { pending[uri] = it }
            work.targets.add(this)
            decode = work
            if (existing == null) {
                try {
                    cacheExecutor.execute(work.lookupTask)
                } catch (error: RejectedExecutionException) {
                    // Complete asynchronously just like a finished worker. This
                    // also clears the target's in-flight marker during shutdown.
                    work.lookupTask.cancel(false)
                }
            }
        }

        fun show(bitmap: Bitmap?) {
            decode = null
            if (bitmap == null) {
                failed = true
                // Show a failed attempt immediately, including during the
                // remote timeout cooldown. A retry restores the loading state.
                showPlaceholder(true)
                retryCount += 1
                val delayMs = maxOf(decoder.retryDelayMs(uri), if (retryCount > MAX_AUTOMATIC_RETRIES) {
                    RETRY_AFTER_REVISIT_MS
                } else {
                    retryDelayMs(retryCount)
                })
                retryAtUptimeMs = SystemClock.uptimeMillis() + delayMs
                scheduleRetry(delayMs)
                return
            }
            cancelRetry()
            failed = false
            retryCount = 0
            retryAtUptimeMs = 0L
            val image = view.get() ?: return
            if (previewDistance() == null) return
            displayed = bitmap
            placeholderFailed = null
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.clearColorFilter()
            image.setImageBitmap(bitmap)
            image.contentDescription = image.context.getString(R.string.thumbnail_description, name)
            if (diagnosticsEnabled) imagesShown++
        }

        fun previewDistance(): Int? {
            val image = view.get() ?: return null
            if (!image.isAttachedToWindow || !image.isShown || image.windowVisibility != View.VISIBLE ||
                image.width <= 0 || image.height <= 0) return null
            if (image.getGlobalVisibleRect(visibleRect)) return 0
            var ancestor = image.parent
            while (ancestor is View) {
                if (ancestor is VirtualMediaListView) return ancestor.previewDistance(image)
                ancestor = ancestor.parent
            }
            return null
        }

        // Read only on the UI thread; diagnostics must not refresh, clear or
        // schedule targets and thereby change the behavior under investigation.
        fun diagnosticState(): Int {
            val image = view.get()
            val attached = image?.isAttachedToWindow == true
            val visible = attached && image.isShown && image.getGlobalVisibleRect(visibleRect)
            return (if (visible) STATE_VISIBLE else 0) or
                (if (displayed != null) STATE_DISPLAYED else 0) or
                (if (failed) STATE_FAILED else 0) or
                (if (attached) STATE_ATTACHED else 0)
        }

        fun dispose() {
            view.get()?.removeOnAttachStateChangeListener(this)
            cancelRetry()
            stopObserving()
            unsubscribe()
            clearImage()
        }

        private fun retryDelayMs(attempt: Int): Long {
            val exponent = (attempt - 1).coerceIn(0, 3)
            return (FIRST_RETRY_DELAY_MS shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
        }

        private fun scheduleRetry(delayMs: Long) {
            if (retryCount > MAX_AUTOMATIC_RETRIES || retryRunnable != null) return
            val retry = Runnable {
                retryRunnable = null
                refresh()
            }
            retryRunnable = retry
            mainHandler.postDelayed(retry, delayMs.coerceAtLeast(1L))
        }

        private fun cancelRetry() {
            retryRunnable?.let(mainHandler::removeCallbacks)
            retryRunnable = null
        }

        private fun clearImage() {
            val hadBitmap = displayed != null
            showPlaceholder(failed)
            if (hadBitmap && diagnosticsEnabled) imagesCleared++
        }

        private fun showPlaceholder(failure: Boolean) {
            if (displayed == null && placeholderFailed == failure) return
            val image = view.get() ?: return
            displayed = null
            placeholderFailed = failure
            image.scaleType = ImageView.ScaleType.CENTER_INSIDE
            image.clearColorFilter()
            image.setImageDrawable(ThumbnailStatusDrawable(image.context, failure))
            image.contentDescription = image.context.getString(
                if (failure) R.string.thumbnail_failed_description
                else R.string.thumbnail_loading_description, name)
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
            if (diagnosticsEnabled) visibilityRefreshes++
            targets.toList().forEach { it.refresh() }
            dispatchNextDecode()
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
        val generation = browseGeneration
        val enqueuedAtUptimeMs = SystemClock.uptimeMillis()
        val startedAtUptimeMs = AtomicLong()
        private val stage = AtomicReference("cache")
        var decodeSubmitted = false
        private var cacheKey: ThumbnailDiskCache.Key? = null
        private val watchdog = Runnable {
            if (task.isDone.not() && stage.get() != "queued") {
                Log.w(TAG, "uri=${debugUriId(uri)} stage=${stage.get()} still running after 5000ms")
            }
        }
        lateinit var task: ThumbnailDecodeTask<Bitmap>
            private set
        val lookupTask: ThumbnailDecodeTask<Bitmap>

        init {
            lookupTask = ThumbnailDecodeTask(
                decode = {
                    cacheKey = diskCache.prepare(uri, width, height)
                    cacheKey?.let(diskCache::read)
                },
                onComplete = { bitmap, _ ->
                    mainHandler.post { cacheLookupComplete(this, bitmap) }
                }
            )
            task = ThumbnailDecodeTask(
                decode = {
                    stage.set("bindDecoder")
                    startedAtUptimeMs.set(SystemClock.uptimeMillis())
                    if (diagnosticsEnabled) decodeStarted.incrementAndGet()
                    mainHandler.postDelayed(watchdog, 5_000L)
                    var encounteredError = false
                    try {
                        val bitmap = decodeFrame(uri, { stage.set(it) }, { encounteredError = true })
                        if (bitmap != null) {
                            cacheKey?.let { key ->
                                persistCover(key, bitmap)
                            }
                        }
                        if (diagnosticsEnabled) runDiagnostic {
                            if (bitmap == null) {
                                val count = decodeNull.incrementAndGet()
                                // Keep large invalid directories from flooding logcat.
                                if (count <= 3L || count % 25L == 0L) {
                                    Log.d(TAG, "uri=${debugUriId(uri)} result=null error=$encounteredError nullCount=$count")
                                }
                            } else {
                                recordBitmap(uri, bitmap)
                            }
                        }
                        bitmap
                    } catch (error: Throwable) {
                        encounteredError = true
                        throw error
                    } finally {
                        if (diagnosticsEnabled) {
                            decodeCompleted.incrementAndGet()
                            if (encounteredError) decodeErrors.incrementAndGet()
                        }
                        mainHandler.removeCallbacks(watchdog)
                    }
                },
                onComplete = { bitmap, failure ->
                    try {
                        if (diagnosticsEnabled && task.isCancelled) {
                            if (startedAtUptimeMs.get() == 0L) cancelledBeforeStart.incrementAndGet()
                            else cancelledRunning.incrementAndGet()
                        }
                        if (failure != null) {
                            Log.w(
                                TAG,
                                "uri=${debugUriId(uri)} task failed (${failure.javaClass.simpleName})"
                            )
                        }
                    } finally {
                        // Diagnostic formatting must not prevent request cleanup.
                        mainHandler.post { complete(this, bitmap) }
                    }
                }
            )
        }

        fun diagnosticStage() = stage.get()
        fun markQueued() { stage.set("queued") }
    }

    private fun cancel(work: Decode) {
        // Remove queued work immediately. Keep the one running request until
        // it returns or its remote deadline expires, preserving deduplication
        // and useful cached results across a scroll or directory switch.
        if (!work.decodeSubmitted) {
            if (pending[work.uri] === work) pending.remove(work.uri)
            readyDecodes.remove(work)
            cacheExecutor.remove(work.lookupTask)
            work.lookupTask.cancel(false)
            work.task.cancel(false)
        }
    }

    @MainThread
    private fun cacheLookupComplete(work: Decode, bitmap: Bitmap?) {
        if (closed || pending[work.uri] !== work || work.generation != browseGeneration) return
        // Recheck the shared prefetch window before adding native work. Scroll
        // observers are coalesced, so their last callback may still be pending.
        work.targets.toList().forEach { it.refresh() }
        if (pending[work.uri] !== work || work.targets.isEmpty()) return
        if (bitmap != null) {
            complete(work, bitmap)
            return
        }
        work.markQueued()
        readyDecodes.addLast(work)
        dispatchNextDecode()
    }

    @MainThread
    private fun dispatchNextDecode() {
        if (closed || activeDecode != null) return
        var next: Decode? = null
        var nearest = Int.MAX_VALUE
        // Re-rank at dispatch time: scrolling can promote a prefetched row to visible.
        for (work in readyDecodes.toList()) {
            if (pending[work.uri] !== work) {
                readyDecodes.remove(work)
                continue
            }
            work.targets.toList().forEach { it.refresh() }
            if (pending[work.uri] !== work || work.targets.isEmpty()) continue
            val distance = work.targets.minOf { it.previewDistance() ?: Int.MAX_VALUE }
            if (distance < nearest) {
                nearest = distance
                next = work
            }
        }
        val work = next ?: return
        // A cache lookup for a visible cover also outranks background decoding.
        // Its completion (or the next viewport refresh) resumes dispatch.
        if (nearest > 0 && pending.values.any { candidate ->
                candidate !in readyDecodes && !candidate.decodeSubmitted &&
                    candidate.targets.any { it.previewDistance() == 0 }
            }) return
        readyDecodes.remove(work)
        activeDecode = work
        work.decodeSubmitted = true
        try {
            executor.execute(work.task)
        } catch (_: RejectedExecutionException) {
            work.task.cancel(false)
        }
    }

    private fun complete(work: Decode, bitmap: Bitmap?) {
        if (activeDecode === work) activeDecode = null
        try {
            if (closed || pending[work.uri] !== work || work.generation != browseGeneration) {
                // A canceled task may still finish inside native media code. Do not
                // retain its subscribers until that late completion is delivered.
                work.targets.clear()
                return
            }
            pending.remove(work.uri)
            if (bitmap != null) cache.put(work.uri, bitmap)
            val subscribers = work.targets.toList()
            work.targets.clear()
            subscribers.forEach { if (it.decode === work) it.show(bitmap) }
        } finally {
            dispatchNextDecode()
        }
    }

    private fun persistCover(key: ThumbnailDiskCache.Key, bitmap: Bitmap) {
        // Keep at most two extra bitmap references while optional disk writes
        // wait. A slow provider or storage must not grow a second image cache.
        if (pendingCacheWrites.incrementAndGet() > 2) {
            pendingCacheWrites.decrementAndGet()
            return
        }
        try {
            cacheExecutor.execute {
                try { diskCache.write(key, bitmap) }
                finally { pendingCacheWrites.decrementAndGet() }
            }
        } catch (_: RejectedExecutionException) {
            pendingCacheWrites.decrementAndGet()
        }
    }

    private fun decodeFrame(uri: String, onStageChanged: (String) -> Unit, onFailure: () -> Unit): Bitmap? {
        if (Thread.currentThread().isInterrupted) return null
        val uriId = debugUriId(uri)
        var stage = "bindDecoder"
        var stageStartedAt = SystemClock.uptimeMillis()
        return try {
            decoder.decode(uri, width, height) { nextStage ->
                logSlowStage(uriId, stage, stageStartedAt)
                stage = nextStage
                stageStartedAt = SystemClock.uptimeMillis()
                if (nextStage == "timeout") onFailure()
                onStageChanged(nextStage)
            }
        } catch (error: Exception) {
            onFailure()
            Log.w(TAG, "uri=$uriId stage=$stage failed (${error.javaClass.simpleName})")
            null
        } catch (error: OutOfMemoryError) {
            onFailure()
            Log.e(TAG, "uri=$uriId stage=$stage failed (${error.javaClass.simpleName})")
            throw error
        } finally {
            logSlowStage(uriId, stage, stageStartedAt)
        }
    }

    private fun logSlowStage(uriId: String, stage: String, startedAt: Long) {
        val elapsedMs = SystemClock.uptimeMillis() - startedAt
        if (elapsedMs >= SLOW_STAGE_THRESHOLD_MS) {
            Log.w(TAG, "uri=$uriId stage=$stage took ${elapsedMs}ms")
        }
    }

    private fun recordBitmap(uri: String, bitmap: Bitmap) {
        drainBitmapReferences()
        val bytes = bitmap.allocationByteCount
        if (trackedBitmaps.size < MAX_TRACKED_BITMAPS) {
            trackedBitmaps[BitmapReference(bitmap, bitmapReferenceQueue, bytes)] = true
        } else {
            bitmapTrackingDropped.incrementAndGet()
        }
        val count = decodedBitmaps.incrementAndGet()
        val info = BitmapDiagnostic(bitmap.width, bitmap.height, bitmap.config.toString(), bytes)
        val details = info.toString()
        // Store metadata only. Neither diagnostics nor the ReferenceQueue keeps
        // a bitmap alive after the cache and ImageViews release it.
        lastBitmap.set(details)
        largestBitmap.updateAndGet { previous ->
            if (previous == null || previous.bytes < bytes) info else previous
        }
        maxBitmapWidth.updateAndGet { maxOf(it, info.width.toLong()) }
        maxBitmapHeight.updateAndGet { maxOf(it, info.height.toLong()) }
        if (count <= 3L || count % 25L == 0L) {
            Log.d(TAG, "uri=${debugUriId(uri)} bitmap=$details decodedBitmaps=$count")
        }
    }

    private fun drainBitmapReferences() {
        while (true) {
            val reference = bitmapReferenceQueue.poll() as? BitmapReference ?: break
            trackedBitmaps.remove(reference)
        }
    }

    @MainThread
    private fun scheduleDiagnostics() {
        if (!diagnosticsEnabled || closed || diagnosticsScheduled || (targets.isEmpty() && pending.isEmpty())) return
        diagnosticsScheduled = true
        mainHandler.postDelayed(diagnosticReport, DIAGNOSTIC_INTERVAL_MS)
    }

    @MainThread
    private fun logDiagnostics(reason: String) {
        if (!diagnosticsEnabled) return
        runDiagnostic { writeDiagnostics(reason) }
    }

    // Diagnostics may allocate while the app is under memory pressure. A
    // reporting failure must not discard a decoded frame or interrupt cleanup.
    private inline fun runDiagnostic(report: () -> Unit) {
        try {
            report()
        } catch (_: Throwable) {
            // Do not allocate another log message while handling this failure.
        }
    }

    @MainThread
    private fun writeDiagnostics(reason: String) {
        var attached = 0
        var visible = 0
        var displayed = 0
        var failed = 0
        var displayedOffscreen = 0
        val boundTargets = targets.values.toList()
        boundTargets.forEach { target ->
            val state = target.diagnosticState()
            if (state and STATE_ATTACHED != 0) attached++
            if (state and STATE_VISIBLE != 0) visible++
            if (state and STATE_FAILED != 0) failed++
            if (state and STATE_DISPLAYED != 0) {
                displayed++
                if (state and STATE_VISIBLE == 0) displayedOffscreen++
            }
        }
        drainBitmapReferences()
        var liveBitmaps = 0
        var liveBitmapBytes = 0L
        trackedBitmaps.keys.forEach { reference ->
            if (reference.get() != null) {
                liveBitmaps++
                liveBitmapBytes += reference.bytes
            } else {
                trackedBitmaps.remove(reference)
            }
        }
        // liveBitmaps means weak references not cleared by a GC yet. It does
        // not prove all counted bitmaps remain strongly reachable, and we never
        // force GC here. A nonzero dropped count makes the weak sample partial.
        val runtime = Runtime.getRuntime()
        val now = SystemClock.uptimeMillis()
        val oldestPending = pending.values.minByOrNull { it.enqueuedAtUptimeMs }
        val oldestRunning = pending.values.filter { it.startedAtUptimeMs.get() != 0L }
            .minByOrNull { it.startedAtUptimeMs.get() }
        Log.d(TAG, "summary reason=$reason targets=${boundTargets.size} attached=$attached visible=$visible " +
            "displayed=$displayed displayedOffscreen=$displayedOffscreen failed=$failed observers=${observers.size} " +
            "targetRefreshes=$targetRefreshes visibilityRefreshes=$visibilityRefreshes shown=$imagesShown cleared=$imagesCleared " +
            "cacheBytes=${cache.size()} cacheMaxBytes=${cache.maxSize()} cacheEntries=${cache.snapshot().size} cachePuts=${cache.putCount()} " +
            "cacheHits=${cache.hitCount()} cacheMisses=${cache.missCount()} cacheEvictions=${cache.evictionCount()} " +
            "pending=${pending.size} queue=${readyDecodes.size} active=${executor.activeCount} cacheQueue=${cacheExecutor.queue.size} " +
            "oldestPendingStage=${oldestPending?.diagnosticStage() ?: "none"} " +
            "oldestPendingAgeMs=${oldestPending?.let { now - it.enqueuedAtUptimeMs } ?: 0L} " +
            "oldestRunningStage=${oldestRunning?.diagnosticStage() ?: "none"} " +
            "oldestRunningAgeMs=${oldestRunning?.let { now - it.startedAtUptimeMs.get() } ?: 0L} " +
            "started=${decodeStarted.get()} completed=${decodeCompleted.get()} null=${decodeNull.get()} errors=${decodeErrors.get()} " +
            "cancelledBeforeStart=${cancelledBeforeStart.get()} cancelledRunning=${cancelledRunning.get()} " +
            "decodedBitmaps=${decodedBitmaps.get()} requestedBitmap=${width}x$height lastBitmap=${lastBitmap.get()} " +
            "largestBitmap=${largestBitmap.get() ?: "none"} maxBitmapWidth=${maxBitmapWidth.get()} maxBitmapHeight=${maxBitmapHeight.get()} " +
            "liveBitmaps=$liveBitmaps liveBitmapBytes=$liveBitmapBytes liveBitmapTrackingDropped=${bitmapTrackingDropped.get()} " +
            "liveSample=weakNotClearedSinceGc javaUsedBytes=${runtime.totalMemory() - runtime.freeMemory()} " +
            "javaMaxBytes=${runtime.maxMemory()} nativeAllocatedBytes=${Debug.getNativeHeapAllocatedSize()}")
    }

    private fun debugUriId(uri: String): String = MessageDigest.getInstance("SHA-256")
        .digest(uri.toByteArray(Charsets.UTF_8))
        .take(4)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

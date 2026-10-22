package com.myp.sleepplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One bounded native worker process, with a deadline that includes release(). */
internal class RemoteThumbnailDecoder(
    context: Context,
    private val requestTimeoutMs: Long = 8_000L
) {
    private companion object {
        const val TIMEOUT_COOLDOWN_MS = 5 * 60_000L
        const val MAX_COOLDOWNS = 256
        const val DEATH_CHECK_MS = 100L
    }

    private val context = context.applicationContext
    init {
        require(requestTimeoutMs in 500L..30_000L)
    }
    private val closed = AtomicBoolean()
    private val nextRequestId = AtomicInteger()
    private val controlThread = HandlerThread("NightingaleThumbnailControl").apply { start() }
    private val control = Handler(controlThread.looper)
    private val cooldowns = LinkedHashMap<String, Long>(16, 0.75f, true)
    // These fields belong exclusively to the control HandlerThread.
    private var current: Request? = null
    private var session: Session? = null

    /** Called only by the loader's one background worker; never wait on the UI. */
    fun decode(uri: String, width: Int, height: Int, onStageChanged: (String) -> Unit): Bitmap? {
        if (closed.get() || retryDelayMs(uri) > 0L) return null
        val request = Request(nextRequestId.incrementAndGet(), uri,
            width.coerceIn(1, 384), height.coerceIn(1, 384), onStageChanged)
        if (!control.post { start(request) }) return null
        try {
            // The extra allowance only protects against a failed control thread.
            // The actual deadline is scheduled independently of the UI thread.
            if (!request.done.await(requestTimeoutMs + 2_000L, TimeUnit.MILLISECONDS)) {
                control.post { timeout(request) }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            control.post { cancel(request) }
        }
        return request.takeResult()
    }

    fun retryDelayMs(uri: String): Long = synchronized(cooldowns) {
        val until = cooldowns[uri] ?: return@synchronized 0L
        val remaining = (until - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        if (remaining == 0L) cooldowns.remove(uri)
        remaining
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        control.post {
            current?.let { finish(it, null) }
            val oldSession = session
            if (oldSession == null) controlThread.quitSafely() else retire(oldSession)
        }
    }

    private inner class Request(
        val id: Int,
        val uri: String,
        val width: Int,
        val height: Int,
        val onStageChanged: (String) -> Unit
    ) {
        val done = CountDownLatch(1)
        val timeout = Runnable { timeout(this) }
        var dispatched = false
        private var finished = false
        private var bitmap: Bitmap? = null

        @Synchronized fun complete(result: Bitmap?) {
            if (finished) result?.recycle() else {
                finished = true
                bitmap = result
                done.countDown()
            }
        }

        @Synchronized fun takeResult(): Bitmap? {
            finished = true
            return bitmap.also { bitmap = null }
        }

        fun stage(name: String) {
            // A diagnostic observer cannot break request cleanup or a deadline.
            try { onStageChanged(name) } catch (_: Throwable) { }
        }
    }

    private inner class Session {
        var bound = false
        var binder: IBinder? = null
        var remote: Messenger? = null
        var pid = 0
        var ready = false
        var stopping = false
        var dead = false
        val recipient = IBinder.DeathRecipient { control.post { died(this) } }
        val receiver = Messenger(Handler(controlThread.looper) { message ->
            receive(this, message)
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                control.post { connected(this@Session, service) }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                control.post { died(this@Session) }
            }
            override fun onBindingDied(name: ComponentName) {
                control.post { died(this@Session) }
            }
            override fun onNullBinding(name: ComponentName) {
                control.post {
                    if (session === this@Session) current?.let { finish(it, null) }
                    died(this@Session)
                }
            }
        }
    }

    private fun start(request: Request) {
        if (closed.get() || retryDelayMs(request.uri) > 0L || current != null) {
            request.complete(null)
            return
        }
        current = request
        request.stage("bindDecoder")
        control.postDelayed(request.timeout, requestTimeoutMs)
        ensureSession()
        dispatch()
    }

    private fun ensureSession() {
        if (closed.get() || session != null || current == null) return
        val worker = Session()
        session = worker
        try {
            worker.bound = context.bindService(Intent(context, ThumbnailDecoderService::class.java),
                worker.connection, Context.BIND_AUTO_CREATE)
            if (!worker.bound) {
                current?.let { finish(it, null) }
                died(worker)
            }
        } catch (error: RuntimeException) {
            Log.w(ThumbnailDecoderProtocol.TAG, "bind failed (${error.javaClass.simpleName})")
            current?.let { finish(it, null) }
            died(worker)
        }
    }

    private fun connected(worker: Session, binder: IBinder) {
        if (session !== worker || worker.dead || closed.get()) {
            unbind(worker)
            return
        }
        worker.binder = binder
        worker.remote = Messenger(binder)
        try {
            binder.linkToDeath(worker.recipient, 0)
            worker.remote?.send(Message.obtain(null, ThumbnailDecoderProtocol.HELLO).apply {
                replyTo = worker.receiver
            })
        } catch (_: RemoteException) {
            died(worker)
        }
    }

    private fun receive(worker: Session, message: Message) {
        if (session !== worker || worker.stopping || worker.dead || closed.get()) {
            if (message.what == ThumbnailDecoderProtocol.RESULT) readBitmap(message)?.recycle()
            return
        }
        when (message.what) {
            ThumbnailDecoderProtocol.HELLO -> {
                val workerPid = message.data.getInt(ThumbnailDecoderProtocol.PID)
                if (workerPid <= 0 || workerPid == Process.myPid()) {
                    current?.let { finish(it, null) }
                    retire(worker)
                    return
                }
                worker.pid = workerPid
                worker.ready = true
                Log.d(ThumbnailDecoderProtocol.TAG, "worker connected pid=$workerPid")
                dispatch()
            }
            ThumbnailDecoderProtocol.STAGE -> current?.takeIf { it.id == message.arg1 }?.stage(
                message.data.getString(ThumbnailDecoderProtocol.STAGE_NAME) ?: "decode")
            ThumbnailDecoderProtocol.RESULT -> {
                val bitmap = readBitmap(message)
                val request = current
                if (request != null && request.id == message.arg1) finish(request, bitmap)
                else bitmap?.recycle()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readBitmap(message: Message): Bitmap? = try {
        message.data.classLoader = Bitmap::class.java.classLoader
        message.data.getParcelable(ThumbnailDecoderProtocol.BITMAP)
    } catch (error: RuntimeException) {
        Log.w(ThumbnailDecoderProtocol.TAG, "invalid worker result (${error.javaClass.simpleName})")
        null
    }

    private fun dispatch() {
        val request = current ?: return
        val worker = session ?: return
        if (request.dispatched || !worker.ready || worker.stopping || closed.get()) return
        request.dispatched = true
        try {
            worker.remote?.send(Message.obtain(null, ThumbnailDecoderProtocol.DECODE, request.id, 0).apply {
                replyTo = worker.receiver
                data = Bundle().apply {
                    putString(ThumbnailDecoderProtocol.URI, request.uri)
                    putInt(ThumbnailDecoderProtocol.WIDTH, request.width)
                    putInt(ThumbnailDecoderProtocol.HEIGHT, request.height)
                }
            })
        } catch (_: RemoteException) {
            finish(request, null)
            retire(worker)
        }
    }

    private fun finish(request: Request, bitmap: Bitmap?) {
        control.removeCallbacks(request.timeout)
        if (current === request) current = null
        request.complete(bitmap)
    }

    private fun timeout(request: Request) {
        if (current !== request) return
        synchronized(cooldowns) {
            cooldowns[request.uri] = SystemClock.uptimeMillis() + TIMEOUT_COOLDOWN_MS
            while (cooldowns.size > MAX_COOLDOWNS) cooldowns.remove(cooldowns.entries.first().key)
        }
        request.stage("timeout")
        Log.w(ThumbnailDecoderProtocol.TAG, "request=${request.id} timeout after ${requestTimeoutMs}ms")
        finish(request, null)
        session?.let(::retire)
    }

    private fun cancel(request: Request) {
        if (current !== request) return
        finish(request, null)
        session?.let(::retire)
    }

    private fun retire(worker: Session) {
        if (worker.dead || worker.stopping) return
        worker.stopping = true
        try { worker.remote?.send(Message.obtain(null, ThumbnailDecoderProtocol.SHUTDOWN)) }
        catch (_: RemoteException) { }
        if (worker.pid > 0 && worker.pid != Process.myPid()) {
            try { Process.killProcess(worker.pid) }
            catch (error: RuntimeException) {
                Log.w(ThumbnailDecoderProtocol.TAG, "worker kill failed (${error.javaClass.simpleName})")
            }
        }
        if (worker.binder == null) {
            // No DECODE is sent before HELLO, so an unconnected binding owns no
            // native job. Unbinding destroys its service if startup was in flight.
            died(worker)
        } else {
            checkDeath(worker)
        }
    }

    private fun checkDeath(worker: Session) {
        if (worker.dead) return
        if (worker.binder?.isBinderAlive == false) died(worker) else {
            // Keep just one retiring session. Never launch replacement processes
            // until Binder confirms that this worker really has exited.
            control.postDelayed({ checkDeath(worker) }, DEATH_CHECK_MS)
        }
    }

    private fun died(worker: Session) {
        if (worker.dead) return
        worker.dead = true
        Log.d(ThumbnailDecoderProtocol.TAG, "worker died pid=${worker.pid}")
        worker.binder?.let { binder -> runCatching { binder.unlinkToDeath(worker.recipient, 0) } }
        unbind(worker)
        if (session !== worker) return
        session = null
        // A spontaneous process death fails this one request; a timeout already
        // completed its request before waiting for the death acknowledgement.
        current?.takeIf { it.dispatched }?.let { finish(it, null) }
        if (closed.get()) controlThread.quitSafely() else ensureSession()
    }

    private fun unbind(worker: Session) {
        if (!worker.bound) return
        worker.bound = false
        try { context.unbindService(worker.connection) } catch (_: IllegalArgumentException) { }
    }
}

package com.myp.sleepplayer

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Native media calls live in this disposable process, never in the player process. */
internal class ThumbnailDecoderService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false
    private var dedicatedProcess = false
    private val handler = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            ThumbnailDecoderProtocol.HELLO -> message.replyTo?.let { reply ->
                send(reply, ThumbnailDecoderProtocol.HELLO, 0, Bundle().apply {
                    putInt(ThumbnailDecoderProtocol.PID, Process.myPid())
                })
            }
            ThumbnailDecoderProtocol.DECODE -> decode(message)
            ThumbnailDecoderProtocol.SHUTDOWN -> terminateProcess()
        }
        true
    }
    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        val processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
            runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrNull()
        }
        dedicatedProcess = processName == "$packageName:thumbnail_decoder"
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun decode(message: Message) {
        val reply = message.replyTo ?: return
        val requestId = message.arg1
        val uri = message.data.getString(ThumbnailDecoderProtocol.URI) ?: return
        val width = message.data.getInt(ThumbnailDecoderProtocol.WIDTH).coerceIn(1, 384)
        val height = message.data.getInt(ThumbnailDecoderProtocol.HEIGHT).coerceIn(1, 384)
        if (busy) {
            send(reply, ThumbnailDecoderProtocol.RESULT, requestId)
            return
        }
        busy = true
        worker.execute {
            var bitmap: Bitmap? = null
            fun stage(name: String) = send(reply, ThumbnailDecoderProtocol.STAGE, requestId,
                Bundle().apply { putString(ThumbnailDecoderProtocol.STAGE_NAME, name) })
            try {
                // Instrumentation can reproduce a non-returning native call without
                // requiring a corrupt file or exposing a fault path to release builds.
                if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
                    uri == "nightingale-thumbnail-test://stall") {
                    stage("extractFrame")
                    CountDownLatch(1).await()
                } else {
                    bitmap = BoundedVideoFrameDecoder(this).decode(uri, width, height, ::stage)
                }
            } catch (error: Exception) {
                Log.w(ThumbnailDecoderProtocol.TAG, "worker request=$requestId failed (${error.javaClass.simpleName})")
            } finally {
                // A decoder may also hang in release. Send no result until cleanup
                // has finished, so the client's deadline covers this stage too.
                stage("release")
            }
            val result = Bundle().apply { putParcelable(ThumbnailDecoderProtocol.BITMAP, bitmap) }
            handler.post {
                // Become available before acknowledging completion: the client can
                // immediately submit the next request after receiving this reply.
                busy = false
                send(reply, ThumbnailDecoderProtocol.RESULT, requestId, result)
            }
        }
    }

    private fun send(reply: Messenger, what: Int, requestId: Int, data: Bundle = Bundle()) {
        try {
            reply.send(Message.obtain(null, what, requestId, 0).apply { this.data = data })
        } catch (_: RemoteException) {
            // The player has gone away; onUnbind/onDestroy retires this process.
        } catch (error: RuntimeException) {
            Log.w(ThumbnailDecoderProtocol.TAG, "worker reply failed (${error.javaClass.simpleName})")
        }
    }

    private fun terminateProcess() {
        if (dedicatedProcess) Process.killProcess(Process.myPid())
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
        // Unbinding must also reclaim a worker stuck before its HELLO was received.
        terminateProcess()
    }
}

internal object ThumbnailDecoderProtocol {
    const val TAG = "RemoteThumbnails"
    const val HELLO = 1
    const val DECODE = 2
    const val STAGE = 3
    const val RESULT = 4
    const val SHUTDOWN = 5
    const val URI = "uri"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val PID = "pid"
    const val STAGE_NAME = "stage"
    const val BITMAP = "bitmap"
}

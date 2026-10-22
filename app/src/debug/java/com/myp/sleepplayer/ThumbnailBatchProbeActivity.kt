package com.myp.sleepplayer

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Exercises the production decoder repeatedly without touching player stores or showing pixels. */
class ThumbnailBatchProbeActivity : Activity() {
    private val decoderRef = AtomicReference<RemoteThumbnailDecoder?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            finish()
            return
        }
        val uris = (0..2).mapNotNull { intent.getStringExtra("uri$it") }
        val count = intent.getIntExtra("count", 21)
        val saveSynthetic = intent.getBooleanExtra("saveSynthetic", false)
        val syntheticPrefix = "file:///sdcard/Movies/NightingaleRegression/synthetic/"
        val syntheticNames = setOf("color-bars.mp4", "color-bars-rotate90.mp4", "color-bars-short.mp4")
        val runId = intent.getStringExtra("runId") ?: "${Process.myPid()}-${SystemClock.elapsedRealtime()}"
        if (uris.size != 3 || count !in 1..100) {
            finish()
            return
        }
        val status = TextView(this).apply { text = "正在连续验证封面解码，仅记录统计数据。" }
        setContentView(status)
        thread(name = "ThumbnailBatchProbe", isDaemon = true) {
            val decoder = RemoteThumbnailDecoder(applicationContext)
            decoderRef.set(decoder)
            val started = SystemClock.elapsedRealtime()
            var present = 0
            var nonBlack = 0
            var timeouts = 0
            var errors = 0
            fun emit(data: JSONObject) {
                Log.i("ThumbnailBatchProbe", data.put("runId", runId).put("pid", Process.myPid()).toString())
            }
            emit(JSONObject().put("event", "start").put("count", count))
            try {
                repeat(count) { index ->
                    val at = SystemClock.elapsedRealtime()
                    var stage = "notStarted"
                    var timedOut = false
                    var error: String? = null
                    var bitmap: Bitmap? = null
                    try {
                        bitmap = decoder.decode(uris[index % uris.size], 294, 165) { name ->
                            stage = name
                            if (name == "timeout") timedOut = true
                        }
                    } catch (failure: Exception) {
                        error = failure.javaClass.simpleName
                    }
                    val result = JSONObject().put("event", "decode").put("index", index)
                        .put("sample", index % uris.size).put("elapsedMs", SystemClock.elapsedRealtime() - at)
                        .put("bitmapPresent", bitmap != null).put("timedOut", timedOut).put("lastStage", stage)
                        .put("errorType", error ?: JSONObject.NULL)
                    if (timedOut) timeouts++
                    if (error != null) errors++
                    bitmap?.let { frame ->
                        present++
                        val pixels = IntArray(frame.width * frame.height)
                        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                        var black = 0
                        var brightness = 0L
                        var maxChannel = 0
                        pixels.forEach { pixel ->
                            val r = pixel shr 16 and 255
                            val g = pixel shr 8 and 255
                            val b = pixel and 255
                            if (maxOf(r, g, b) <= 16) black++
                            brightness += (r * 299 + g * 587 + b * 114) / 1000
                            maxChannel = maxOf(maxChannel, r, g, b)
                        }
                        val blackFraction = black.toDouble() / pixels.size
                        if (blackFraction < 0.98) nonBlack++
                        result.put("width", frame.width).put("height", frame.height)
                            .put("meanLuma", brightness.toDouble() / pixels.size)
                            .put("nearBlackFraction", blackFraction).put("maxChannel", maxChannel)
                        // Saving is restricted to known generated fixtures; real sample pixels stay private.
                        val input = uris[index % uris.size]
                        if (saveSynthetic && input.startsWith(syntheticPrefix) &&
                            Uri.parse(input).lastPathSegment in syntheticNames) {
                            val file = File(filesDir, "synthetic-frame-$index.png")
                            file.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            result.put("syntheticPng", file.name)
                        }
                        frame.recycle()
                    }
                    emit(result)
                }
                emit(JSONObject().put("event", "complete").put("count", count)
                    .put("bitmapPresentCount", present).put("nonBlackCount", nonBlack)
                    .put("timeoutCount", timeouts).put("errorCount", errors)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started))
                runOnUiThread { status.text = "连续解码诊断结束，结果已写入开发日志。" }
            } finally {
                decoderRef.compareAndSet(decoder, null)
                decoder.close()
            }
        }
    }

    override fun onDestroy() {
        decoderRef.getAndSet(null)?.close()
        super.onDestroy()
    }
}

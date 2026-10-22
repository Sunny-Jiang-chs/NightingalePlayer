package com.myp.sleepplayer

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

/** Shell-only diagnostic probe. Reports statistics and never displays media pixels. */
class ThumbnailFrameProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            finish()
            return
        }
        val uri = intent.getStringExtra("uri") ?: run { finish(); return }
        val timeUs = intent.getLongExtra("timeUs", 0L)
        val option = intent.getIntExtra("option", MediaMetadataRetriever.OPTION_CLOSEST)
        val runId = intent.getStringExtra("runId") ?: "${Process.myPid()}-${SystemClock.elapsedRealtime()}"
        if (timeUs !in 0L..60_000_000L || option !in 0..3) {
            finish()
            return
        }
        val status = TextView(this).apply { text = "封面参数诊断：只记录耗时和像素统计。" }
        setContentView(status)
        thread(name = "ThumbnailFrameProbe", isDaemon = true) {
            val started = SystemClock.elapsedRealtime()
            var bitmap: Bitmap? = null
            var retriever: MediaMetadataRetriever? = null
            var lastStage = "start"
            var errorType: String? = null
            val timings = JSONObject()
            fun emit(data: JSONObject) {
                Log.i("ThumbnailFrameProbe", data.put("runId", runId).put("pid", Process.myPid())
                    .put("timeUs", timeUs).put("option", option).toString())
            }
            fun stage(name: String) {
                lastStage = name
                emit(JSONObject().put("event", "stage").put("stage", name)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started))
            }
            emit(JSONObject().put("event", "start"))
            try {
                stage("createRetriever")
                retriever = MediaMetadataRetriever()
                var at = SystemClock.elapsedRealtime()
                stage("setDataSource")
                retriever.setDataSource(this, Uri.parse(uri))
                timings.put("setDataSourceMs", SystemClock.elapsedRealtime() - at)
                at = SystemClock.elapsedRealtime()
                stage("extractFrame")
                bitmap = retriever.getScaledFrameAtTime(timeUs, option, 294, 165)
                timings.put("extractFrameMs", SystemClock.elapsedRealtime() - at)
            } catch (error: Exception) {
                errorType = error.javaClass.simpleName
            } finally {
                val at = SystemClock.elapsedRealtime()
                stage("release")
                try { retriever?.release() } catch (error: Exception) {
                    errorType = errorType ?: error.javaClass.simpleName
                }
                timings.put("releaseMs", SystemClock.elapsedRealtime() - at)
            }
            val result = JSONObject().put("event", "complete").put("bitmapPresent", bitmap != null)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("timings", timings)
                .put("errorType", errorType ?: JSONObject.NULL).put("lastStage", lastStage)
            bitmap?.let { frame ->
                val pixels = IntArray(frame.width * frame.height)
                frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                var black = 0
                var brightness = 0L
                var minChannel = 255
                var maxChannel = 0
                pixels.forEach { pixel ->
                    val r = pixel shr 16 and 255
                    val g = pixel shr 8 and 255
                    val b = pixel and 255
                    if (maxOf(r, g, b) <= 16) black++
                    brightness += (r * 299 + g * 587 + b * 114) / 1000
                    minChannel = minOf(minChannel, r, g, b)
                    maxChannel = maxOf(maxChannel, r, g, b)
                }
                result.put("width", frame.width).put("height", frame.height)
                    .put("meanLuma", brightness.toDouble() / pixels.size)
                    .put("nearBlackFraction", black.toDouble() / pixels.size)
                    .put("minChannel", minChannel).put("maxChannel", maxChannel)
                frame.recycle()
            }
            emit(result)
            runOnUiThread { status.text = "诊断完成，结果已写入开发日志。" }
        }
    }
}

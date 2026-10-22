package com.myp.sleepplayer

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Debug-only, shell-permission entry point; does not open the player or its stores. */
class ThumbnailRecoverySmokeActivity : Activity() {
    private companion object {
        const val TAG = "ThumbnailRecoverySmoke"
        const val DEFAULT_STALL_URI = "nightingale-thumbnail-test://stall"
        const val DEFAULT_TIMEOUT_MS = 8_000L
        const val WIDTH = 294
        const val HEIGHT = 165
    }

    private val destroyed = AtomicBoolean()
    private val activeDecoder = AtomicReference<RemoteThumbnailDecoder?>(null)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ThumbnailRecoverySmoke").apply { isDaemon = true }
    }
    private lateinit var resultView: TextView
    private val runId = "${Process.myPid()}-${SystemClock.elapsedRealtime()}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            finish()
            return
        }
        val padding = (20 * resources.displayMetrics.density).roundToInt()
        resultView = TextView(this).apply {
            text = "正在验证超时后能否继续加载正常封面……"
            textSize = 15f
            setTextColor(getColor(R.color.player_text))
            setTextIsSelectable(true)
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(getColor(R.color.player_background))
            addView(TextView(this@ThumbnailRecoverySmokeActivity).apply {
                text = "封面超时恢复验证"
                textSize = 20f
                setTextColor(getColor(R.color.player_text))
                setPadding(0, 0, 0, padding)
            })
            addView(resultView)
        })

        val stallUri = intent.getStringExtra("stallUri")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_STALL_URI
        val goodUri = intent.getStringExtra("goodUri")?.takeIf { it.isNotBlank() }
        val timeoutMs = intent.getLongExtra("timeoutMs", DEFAULT_TIMEOUT_MS)
        if (goodUri == null || timeoutMs !in 500L..30_000L) {
            val reason = if (goodUri == null) "missingGoodUri" else "invalidTimeoutMs"
            emit(JSONObject().put("event", "complete").put("success", false)
                .put("reason", reason).put("timeoutMs", timeoutMs))
            resultView.text = "参数无效：需要 goodUri，timeoutMs 范围为 500–30000 毫秒。"
            return
        }
        worker.execute { runSmoke(stallUri, goodUri, timeoutMs) }
    }

    private fun runSmoke(stallUri: String, goodUri: String, timeoutMs: Long) {
        val startedAt = SystemClock.elapsedRealtime()
        val decoder = RemoteThumbnailDecoder(applicationContext, requestTimeoutMs = timeoutMs)
        activeDecoder.set(decoder)
        if (destroyed.get()) {
            activeDecoder.compareAndSet(decoder, null)
            decoder.close()
            return
        }
        emit(JSONObject().put("event", "start").put("timeoutMs", timeoutMs)
            .put("requestedWidth", WIDTH).put("requestedHeight", HEIGHT))
        try {
            val stalled = decodeOne(decoder, "stall", stallUri)
            if (destroyed.get()) return
            showResult("超时试验${if (stalled.success) "通过" else "失败"}，${stalled.elapsedMs} 毫秒。\n正在加载正常 MP4……")
            // Reuse this exact client immediately; a new instance would hide a broken reset.
            val recovered = decodeOne(decoder, "good", goodUri)
            val success = stalled.success && recovered.success
            emit(JSONObject().put("event", "complete").put("success", success)
                .put("stallTimedOut", stalled.success).put("recovered", recovered.success)
                .put("timeoutMs", timeoutMs)
                .put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
                .put("stallElapsedMs", stalled.elapsedMs).put("goodElapsedMs", recovered.elapsedMs)
                .put("width", recovered.width).put("height", recovered.height))
            showResult("${if (success) "验证通过" else "验证失败"}\n\n" +
                "超时试验：${if (stalled.success) "按时返回" else "未满足预期"}（${stalled.elapsedMs} 毫秒）\n" +
                "正常封面：${if (recovered.success) "恢复成功" else "加载失败"}（${recovered.elapsedMs} 毫秒）\n" +
                "封面尺寸：${recovered.width} × ${recovered.height}")
        } catch (error: Exception) {
            emit(JSONObject().put("event", "complete").put("success", false)
                .put("reason", "harnessException").put("errorType", error.javaClass.simpleName)
                .put("elapsedMs", SystemClock.elapsedRealtime() - startedAt))
            showResult("验证失败：${error.javaClass.simpleName}")
        } finally {
            activeDecoder.compareAndSet(decoder, null)
            decoder.close()
        }
    }

    private data class Outcome(val success: Boolean, val elapsedMs: Long, val width: Int, val height: Int)

    private fun decodeOne(decoder: RemoteThumbnailDecoder, phase: String, uri: String): Outcome {
        val startedAt = SystemClock.elapsedRealtime()
        val lastStage = AtomicReference("notStarted")
        val reachedExtractFrame = AtomicBoolean()
        val timedOut = AtomicBoolean()
        var bitmap: Bitmap? = null
        var errorType: String? = null
        try {
            bitmap = decoder.decode(uri, WIDTH, HEIGHT) { stage ->
                lastStage.set(stage)
                if (stage == "extractFrame") reachedExtractFrame.set(true)
                if (stage == "timeout") timedOut.set(true)
            }
        } catch (error: Exception) {
            errorType = error.javaClass.simpleName
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val width = bitmap?.width ?: 0
        val height = bitmap?.height ?: 0
        val success = if (phase == "stall") {
            errorType == null && bitmap == null && reachedExtractFrame.get() && timedOut.get()
        } else {
            errorType == null && bitmap != null && width > 0 && height > 0 && !timedOut.get()
        }
        emit(JSONObject().put("event", "decode").put("phase", phase).put("success", success)
            .put("elapsedMs", elapsedMs).put("bitmapPresent", bitmap != null)
            .put("width", width).put("height", height)
            .put("reachedExtractFrame", reachedExtractFrame.get()).put("timedOut", timedOut.get())
            .put("lastStage", lastStage.get()).put("errorType", errorType ?: JSONObject.NULL))
        // The harness never displays the pixels, so no view or cache can still own them.
        bitmap?.recycle()
        return Outcome(success, elapsedMs, width, height)
    }

    private fun emit(message: JSONObject) {
        Log.i(TAG, message.put("runId", runId).toString())
    }

    private fun showResult(message: String) {
        runOnUiThread {
            if (!destroyed.get()) resultView.text = message
        }
    }

    override fun onDestroy() {
        destroyed.set(true)
        activeDecoder.getAndSet(null)?.close()
        worker.shutdownNow()
        super.onDestroy()
    }
}

package com.myp.sleepplayer

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Deliver completion even when a decoder throws before it can return a result. */
internal class ThumbnailDecodeTask<T>(
    decode: () -> T?,
    private val onComplete: (T?, Throwable?) -> Unit
) : FutureTask<T?>(Callable { decode() }) {
    override fun done() {
        var value: T? = null
        var failure: Throwable? = null
        try {
            value = get()
        } catch (_: CancellationException) {
            // A queued request was removed, or the owning loader was closed.
        } catch (error: ExecutionException) {
            failure = error.cause ?: error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            failure = error
        } finally {
            onComplete(value, failure)
        }
    }
}

package com.myp.sleepplayer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** JVM checks for the completion contract used by thumbnail requests. */
object ThumbnailDecodeTaskTest {
    @JvmStatic
    fun main(args: Array<String>) {
        successfulTaskCompletesOnce()
        failingTaskStillCompletes()
        canceledQueuedTaskCompletesWithoutDecoding()
        workerContinuesAfterDecodeError()
        println("ThumbnailDecodeTaskTest: 4 checks passed")
    }

    private fun successfulTaskCompletesOnce() {
        var calls = 0
        var result: String? = null
        val task = ThumbnailDecodeTask(
            decode = { "frame" },
            onComplete = { value, error ->
                check(error == null)
                result = value
                calls += 1
            }
        )
        task.run()
        task.run()
        check(result == "frame")
        check(calls == 1)
    }

    private fun failingTaskStillCompletes() {
        val expected = OutOfMemoryError("simulated frame allocation failure")
        var calls = 0
        var result: String? = "uncompleted"
        var failure: Throwable? = null
        val task = ThumbnailDecodeTask<String>(
            decode = { throw expected },
            onComplete = { value, error ->
                result = value
                failure = error
                calls += 1
            }
        )
        task.run()
        check(result == null)
        check(failure === expected)
        check(calls == 1)
    }

    private fun canceledQueuedTaskCompletesWithoutDecoding() {
        var decodes = 0
        var calls = 0
        val task = ThumbnailDecodeTask(
            decode = { decodes += 1; "frame" },
            onComplete = { value, error ->
                check(value == null)
                check(error == null)
                calls += 1
            }
        )
        check(task.cancel(false))
        task.run()
        check(decodes == 0)
        check(calls == 1)
    }

    private fun workerContinuesAfterDecodeError() {
        val executor = Executors.newSingleThreadExecutor()
        val completed = CountDownLatch(2)
        var recovered = false
        try {
            executor.execute(ThumbnailDecodeTask<String>(
                decode = { throw IllegalStateException("simulated retriever constructor failure") },
                onComplete = { _, error ->
                    check(error is IllegalStateException)
                    completed.countDown()
                }
            ))
            executor.execute(ThumbnailDecodeTask(
                decode = { "next frame" },
                onComplete = { value, error ->
                    check(error == null)
                    recovered = value == "next frame"
                    completed.countDown()
                }
            ))
            check(completed.await(5, TimeUnit.SECONDS)) { "Completion was not delivered after decoder failure" }
            check(recovered)
        } finally {
            executor.shutdownNow()
        }
    }
}

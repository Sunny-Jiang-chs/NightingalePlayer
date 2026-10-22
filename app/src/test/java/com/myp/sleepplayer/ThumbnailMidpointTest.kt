package com.myp.sleepplayer

/** Focused checks for selecting the timestamp midpoint without scanning a file. */
object ThumbnailMidpointTest {
    @JvmStatic
    fun main(args: Array<String>) {
        check(ThumbnailMidpoint.targetPtsUs(0L, 60_000_000L) == 30_000_000L)
        check(ThumbnailMidpoint.targetPtsUs(2_000_000L, 10_000_000L) == 7_000_000L)
        check(ThumbnailMidpoint.targetPtsUs(-500_000L, 1_000_000L) == 0L)
        check(ThumbnailMidpoint.targetPtsUs(0L, 0L) == null)
        check(ThumbnailMidpoint.targetPtsUs(Long.MAX_VALUE - 1L, 10L) == null)
        check(!ThumbnailMidpoint.hasReachedTarget(30_000_000L, 29_966_667L))
        check(ThumbnailMidpoint.hasReachedTarget(30_000_000L, 30_000_000L))
        check(ThumbnailMidpoint.hasReachedTarget(30_000_000L, 30_033_333L))
        println("ThumbnailMidpointTest: 8 checks passed")
    }
}

package com.myp.sleepplayer

/** Small, deterministic timestamp helpers for midpoint thumbnail selection. */
internal object ThumbnailMidpoint {
    fun targetPtsUs(firstPtsUs: Long, durationUs: Long): Long? {
        if (durationUs <= 0L || firstPtsUs > Long.MAX_VALUE - durationUs / 2) return null
        return (firstPtsUs + durationUs / 2).coerceAtLeast(0L)
    }

    fun hasReachedTarget(targetPtsUs: Long, framePtsUs: Long): Boolean =
        framePtsUs >= targetPtsUs
}

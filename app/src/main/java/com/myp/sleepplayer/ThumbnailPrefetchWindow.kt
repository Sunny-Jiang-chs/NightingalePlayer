package com.myp.sleepplayer

/** One viewport ahead and behind; shared by row mounting and thumbnail eligibility. */
internal class ThumbnailPrefetchWindow(val top: Int, val bottom: Int) {
    private val height = (bottom - top).coerceAtLeast(0)
    private val start = top - height
    private val end = bottom + height

    fun rows(itemCount: Int, rowStride: Int, contentTop: Int): IntRange {
        if (height == 0 || itemCount == 0 || end <= contentTop ||
            start >= contentTop + itemCount * rowStride) return IntRange.EMPTY
        val first = ((start - contentTop).coerceAtLeast(0) / rowStride).coerceAtMost(itemCount - 1)
        val last = ((end - contentTop - 1).coerceAtLeast(0) / rowStride).coerceAtMost(itemCount - 1)
        return first..last
    }

    /** Visible rows have priority zero; null means outside the prefetch window. */
    fun distance(rowTop: Int, rowBottom: Int): Int? {
        if (height == 0 || rowBottom <= start || rowTop >= end || rowBottom <= rowTop) return null
        return when {
            rowBottom <= top -> top - rowBottom + 1
            rowTop >= bottom -> rowTop - bottom + 1
            else -> 0
        }
    }
}

package com.myp.sleepplayer.playback

internal class SubtitleSeekSnapper(cueStartTimesMs: Iterable<Long>, durationMs: Long) {
    private val cueStarts = cueStartTimesMs
        .asSequence()
        .filter { it in 0L..durationMs }
        .distinct()
        .sorted()
        .toList()

    val isAvailable: Boolean
        get() = cueStarts.isNotEmpty()

    fun snap(targetMs: Long, originMs: Long, direction: Int): Long? {
        if (cueStarts.isEmpty()) return null
        val target = targetMs.coerceAtLeast(0L)
        val origin = originMs.coerceAtLeast(0L)
        val fromIndex = if (direction > 0) upperBound(origin) else 0
        val untilIndex = if (direction < 0) lowerBound(origin) else cueStarts.size
        if (fromIndex >= untilIndex) return null

        val insertionPoint = lowerBound(target, fromIndex, untilIndex)
        if (insertionPoint < untilIndex && cueStarts[insertionPoint] == target) {
            return cueStarts[insertionPoint]
        }
        val before = cueStarts.getOrNull(insertionPoint - 1)
            ?.takeIf { insertionPoint - 1 >= fromIndex }
        val after = cueStarts.getOrNull(insertionPoint)
            ?.takeIf { insertionPoint < untilIndex }
        if (before == null) return after
        if (after == null) return before

        val beforeDistance = target - before
        val afterDistance = after - target
        return when {
            beforeDistance < afterDistance -> before
            afterDistance < beforeDistance -> after
            direction < 0 -> before
            else -> after
        }
    }

    private fun lowerBound(value: Long): Int = lowerBound(value, 0, cueStarts.size)

    private fun upperBound(value: Long): Int {
        var low = 0
        var high = cueStarts.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cueStarts[middle] <= value) low = middle + 1 else high = middle
        }
        return low
    }

    private fun lowerBound(value: Long, fromIndex: Int, untilIndex: Int): Int {
        var low = fromIndex
        var high = untilIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cueStarts[middle] < value) low = middle + 1 else high = middle
        }
        return low
    }
}

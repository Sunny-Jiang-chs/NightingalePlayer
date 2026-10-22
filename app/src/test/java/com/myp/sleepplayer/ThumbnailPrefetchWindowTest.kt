package com.myp.sleepplayer

/** Pure geometry checks; no device or decoder is exercised. */
object ThumbnailPrefetchWindowTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val middle = ThumbnailPrefetchWindow(3_000, 3_600)
        check(middle.rows(1_000, 60, 0) == 40..69)
        check(ThumbnailPrefetchWindow(0, 600).rows(1_000, 60, 0) == 0..19)
        check(ThumbnailPrefetchWindow(5_700, 6_300).rows(100, 60, 0) == 85..99)
        check(ThumbnailPrefetchWindow(-1_000, -400).rows(100, 60, 0) == 0..3)
        check(ThumbnailPrefetchWindow(-2_000, -1_400).rows(100, 60, 0).isEmpty())
        check(ThumbnailPrefetchWindow(6_600, 7_200).rows(100, 60, 0).isEmpty())
        check(middle.rows(0, 60, 0).isEmpty())
        check(ThumbnailPrefetchWindow(100, 100).rows(100, 60, 0).isEmpty())
        check(ThumbnailPrefetchWindow(0, 600).rows(100, 60, 20) == 0..19)
        check(middle.distance(3_000, 3_060) == 0)
        check(middle.distance(2_940, 3_001) == 0)
        check(middle.distance(2_940, 3_000) == 1)
        check(middle.distance(3_600, 3_660) == 1)
        check(middle.distance(2_400, 2_460) == 541)
        check(middle.distance(2_340, 2_400) == null)
        check(middle.distance(4_200, 4_260) == null)
        check(middle.distance(3_000, 3_000) == null)
        // Every eligible row is mounted, including clipped first/last rows.
        for (top in listOf(-1_000, 0, 37, 3_000, 5_700, 6_600)) {
            val window = ThumbnailPrefetchWindow(top, top + 600)
            val rows = window.rows(100, 60, 20)
            for (index in 0 until 100) {
                check((window.distance(20 + index * 60, 20 + (index + 1) * 60) != null) == (index in rows))
            }
            check(rows.count() <= 31)
        }
        println("ThumbnailPrefetchWindowTest: boundaries, priority and mounting consistency passed")
    }
}

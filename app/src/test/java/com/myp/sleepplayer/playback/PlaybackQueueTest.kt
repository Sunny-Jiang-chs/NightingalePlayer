package com.myp.sleepplayer.playback

object PlaybackQueueTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val available = listOf("a", "b", "c")
        assertEquals(available, PlaybackQueue.resolve(available, null))
        assertEquals(listOf("c", "a"), PlaybackQueue.resolve(available, listOf("c", "missing", "a", "c")))
        assertEquals(emptyList<String>(), PlaybackQueue.resolve(emptyList(), listOf("a")))
        assertEquals(emptyList<String>(), PlaybackQueue.resolve(available, emptyList()))
        assertEquals(listOf("c", "a", "b"), PlaybackQueue.updateSelection(listOf("c", "a"), available))
        assertEquals(listOf("c", "b"), PlaybackQueue.updateSelection(listOf("c", "a"), listOf("b", "c")))
        assertEquals(listOf("b", "c", "a"), PlaybackQueue.move(available, 0, 2))
        assertEquals(listOf("c", "a", "b"), PlaybackQueue.move(available, 2, 0))
        assertEquals(available, PlaybackQueue.move(available, 0, -1))
        assertEquals(available, PlaybackQueue.move(available, 3, 0))

        val saved = PlaybackSnapshot("a", 1_200L, true)
        assertEquals(PlaylistResume(0, 1_200L, true), resolvePlaylistResume(available, null, 0, false, saved))
        assertEquals(PlaylistResume(2, 8_000L, false), resolvePlaylistResume(
            listOf("c", "a", "b"), "b", 8_000, false, saved
        ))
        assertEquals(PlaylistResume(0, 0L, false), resolvePlaylistResume(
            listOf("c"), "b", 8_000, true, saved
        ))
        assertEquals(PlaylistResume(0, 0L, false), resolvePlaylistResume(
            listOf("c"), null, 0, false, saved
        ))
        assertEquals(PlaylistResume(1, 0L, true), resolvePlaylistResume(
            available, "b", -1, true, saved
        ))
        assertEquals(PlaybackMode.SEQUENTIAL, PlaybackMode.fromRepeatMode(99))
        assertEquals(PlaybackMode.REPEAT_ALL, PlaybackMode.fromRepeatMode(1))
        assertEquals(PlaybackMode.REPEAT_ALL, PlaybackMode.fromRepeatMode(2))
        println("Playback queue: 18 checks passed")
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        check(expected == actual) { "Expected <$expected>, got <$actual>" }
    }
}

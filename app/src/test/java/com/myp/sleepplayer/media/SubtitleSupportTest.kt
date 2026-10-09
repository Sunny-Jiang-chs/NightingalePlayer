package com.myp.sleepplayer.media

import com.myp.sleepplayer.playback.SubtitleSeekSnapper

object SubtitleSupportTest {
    @JvmStatic
    fun main(args: Array<String>) {
        parsesLrcOffsetAndMultipleTimestamps()
        mergesTextsWithTheSameStartAndUsesMinimumFinalDuration()
        rejectsMalformedTimestampAndSerializesWebVtt()
        extractsWebVttCueStarts()
        snapsSeekTargetsToNearestCueStart()
    }

    private fun parsesLrcOffsetAndMultipleTimestamps() {
        val cues = SubtitleParser.parseLrc(
            listOf(
                "[offset:-500]",
                "[00:01.2][00:03.45]First line",
                "[00:05.678]Second line"
            )
        )

        assertEquals(
            listOf(
                SubtitleCue(700L, 2_950L, "First line"),
                SubtitleCue(2_950L, 5_178L, "First line"),
                SubtitleCue(5_178L, 10_178L, "Second line")
            ),
            cues
        )
    }

    private fun mergesTextsWithTheSameStartAndUsesMinimumFinalDuration() {
        val cues = SubtitleParser.parseLrc(
            listOf(
                "[00:01.00]first",
                "[00:01.00]second",
                "[00:02.00]third"
            )
        )

        assertEquals("first\nsecond", cues[0].text)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(2_000L, cues[0].endMs)
        assertEquals(2_000L, cues[1].startMs)
        assertEquals(7_000L, cues[1].endMs)
    }

    private fun rejectsMalformedTimestampAndSerializesWebVtt() {
        check(parseLrcTimestamp("bad") == null)
        assertEquals(
            "WEBVTT\n\n00:00:01.000 --> 00:00:06.000\nhello\n\n",
            SubtitleParser.toWebVtt(listOf(SubtitleCue(1_000L, 6_000L, "hello")))
        )
    }

    private fun extractsWebVttCueStarts() {
        assertEquals(
            listOf(1_200L, 5_000L, 3_600_500L),
            SubtitleParser.parseWebVttCueStarts(
                listOf(
                    "WEBVTT",
                    "",
                    "cue-one",
                    "00:00:01.200 --> 00:00:03.000 align:start",
                    "First cue",
                    "",
                    "00:05.000 --> 00:06.000",
                    "Second cue",
                    "",
                    "01:00:00.500 --> 01:00:02.000",
                    "Third cue",
                    "",
                    "00:60.000 --> 01:00.000"
                )
            )
        )
        check(parseWebVttTimestamp("00:60.000") == null)
    }

    private fun snapsSeekTargetsToNearestCueStart() {
        val snapper = SubtitleSeekSnapper(
            listOf(9_000L, 5_000L, 1_000L, 5_000L),
            durationMs = 10_000L
        )

        assertEquals(5_000L, snapper.snap(3_000L, originMs = 2_000L, direction = 1))
        assertEquals(1_000L, snapper.snap(3_000L, originMs = 4_000L, direction = -1))
        assertEquals(9_000L, snapper.snap(8_200L, originMs = 4_000L, direction = 1))
        assertEquals(null, snapper.snap(9_500L, originMs = 9_000L, direction = 1))
        assertEquals(
            null,
            SubtitleSeekSnapper(emptyList(), 6_000L).snap(1_000L, originMs = 0L, direction = 1)
        )
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        check(expected == actual) { "Expected <$expected>, got <$actual>" }
    }
}

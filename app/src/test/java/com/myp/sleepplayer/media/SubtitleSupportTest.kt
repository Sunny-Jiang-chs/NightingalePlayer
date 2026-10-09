package com.myp.sleepplayer.media

object SubtitleSupportTest {
    @JvmStatic
    fun main(args: Array<String>) {
        parsesLrcOffsetAndMultipleTimestamps()
        mergesTextsWithTheSameStartAndUsesMinimumFinalDuration()
        rejectsMalformedTimestampAndSerializesWebVtt()
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

    private fun assertEquals(expected: Any?, actual: Any?) {
        check(expected == actual) { "Expected <$expected>, got <$actual>" }
    }
}

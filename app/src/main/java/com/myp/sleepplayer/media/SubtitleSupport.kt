package com.myp.sleepplayer.media

import java.util.Locale

internal val lrcTimestampPattern =
    Regex("\\[(\\d{1,3}:\\d{2}(?::\\d{2})?(?:[.:]\\d{1,3})?)\\]")
internal val lrcOffsetPattern = Regex("^\\[offset:([-+]?\\d+)\\]", RegexOption.IGNORE_CASE)

internal data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

internal object SubtitleParser {
    private val webVttTimingPattern = Regex("^\\s*(\\S+)\\s+-->\\s+(\\S+)(?:\\s+.*)?$")

    fun parseLrc(lines: List<String>): List<SubtitleCue> {
        val offsetMs = lines.asSequence()
            .mapNotNull { line -> lrcOffsetPattern.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() }
            .firstOrNull()
            ?: 0L
        val timedLines = lines.asSequence()
            .flatMap { line ->
                val timestamps = lrcTimestampPattern.findAll(line).toList()
                val text = lrcTimestampPattern.replace(line, "").trim()
                if (timestamps.isEmpty() || text.isEmpty()) {
                    emptySequence()
                } else {
                    timestamps.asSequence().mapNotNull { match ->
                        parseLrcTimestamp(match.groupValues[1])
                            ?.let { start -> (start + offsetMs).coerceAtLeast(0L) to text }
                    }
                }
            }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .map { (start, texts) -> start to texts.distinct().joinToString("\n") }

        return timedLines.mapIndexed { index, (start, text) ->
            val nextStart = timedLines.getOrNull(index + 1)?.first
            val end = (nextStart ?: (start + 5_000L)).coerceAtLeast(start + 500L)
            SubtitleCue(start, end, text)
        }
    }

    fun parseWebVttCueStarts(lines: List<String>): List<Long> = lines.mapNotNull { line ->
        val match = webVttTimingPattern.matchEntire(line) ?: return@mapNotNull null
        val start = parseWebVttTimestamp(match.groupValues[1]) ?: return@mapNotNull null
        val end = parseWebVttTimestamp(match.groupValues[2]) ?: return@mapNotNull null
        start.takeIf { end > start }
    }.distinct().sorted()

    fun toWebVtt(cues: List<SubtitleCue>): String = buildString {
        append("WEBVTT\n\n")
        cues.forEach { cue ->
            append(formatVttTimestamp(cue.startMs))
            append(" --> ")
            append(formatVttTimestamp(cue.endMs))
            append('\n')
            append(cue.text)
            append("\n\n")
        }
    }
}

internal fun parseWebVttTimestamp(timestamp: String): Long? {
    val parts = timestamp.split(':')
    if (parts.size !in 2..3) return null
    val secondParts = parts.last().split('.', limit = 2)
    if (secondParts.size != 2) return null
    val seconds = secondParts[0].toLongOrNull()?.takeIf { it in 0L..59L } ?: return null
    val fractionText = secondParts[1]
    if (fractionText.isEmpty() || fractionText.length > 3 || !fractionText.all(Char::isDigit)) return null
    val fraction = fractionText.padEnd(3, '0').toLongOrNull() ?: return null
    return if (parts.size == 2) {
        val minutes = parts[0].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        minutes * 60_000L + seconds * 1_000L + fraction
    } else {
        val hours = parts[0].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val minutes = parts[1].toLongOrNull()?.takeIf { it in 0L..59L } ?: return null
        hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + fraction
    }
}

internal fun parseLrcTimestamp(timestamp: String): Long? {
    val parts = timestamp.split(':')
    if (parts.size !in 2..3) return null
    val secondParts = parts.last().split('.', limit = 2)
    val seconds = secondParts[0].toLongOrNull() ?: return null
    val fraction = secondParts.getOrNull(1)
        ?.padEnd(3, '0')
        ?.take(3)
        ?.toLongOrNull()
        ?: 0L
    return if (parts.size == 2) {
        val minutes = parts[0].toLongOrNull() ?: return null
        minutes * 60_000L + seconds * 1_000L + fraction
    } else {
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + fraction
    }
}

internal fun formatVttTimestamp(milliseconds: Long): String {
    val safe = milliseconds.coerceAtLeast(0L)
    val hours = safe / 3_600_000L
    val minutes = (safe % 3_600_000L) / 60_000L
    val seconds = (safe % 60_000L) / 1_000L
    val millis = safe % 1_000L
    return "%02d:%02d:%02d.%03d".format(Locale.ROOT, hours, minutes, seconds, millis)
}

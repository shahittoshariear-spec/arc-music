package com.arcmusic.player

/** One lyric line. [timeMs] is -1 when the text has no timestamps. */
data class LyricLine(val timeMs: Long, val text: String)

private val LRC = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]\s*(.*)""")

/**
 * Accepts both LRC (`[01:23.45] line`) and plain text. A blank raw string yields an
 * empty list, not a list containing an empty line.
 */
fun parseLyrics(raw: String): List<LyricLine> {
    val timed = raw.lineSequence().mapNotNull { line ->
        val match = LRC.find(line) ?: return@mapNotNull null
        val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val seconds = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        LyricLine(minutes * 60_000L + seconds * 1_000L + fraction, match.groupValues[4].trim())
    }.toList()

    if (timed.isNotEmpty()) return timed.sortedBy { it.timeMs }

    return raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { LyricLine(-1L, it) }
        .toList()
}

/** Index of the line that should be highlighted, or -1 for untimed text. */
fun currentLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty() || lines.first().timeMs < 0L) return -1
    var index = -1
    for (i in lines.indices) {
        if (lines[i].timeMs <= positionMs) index = i else break
    }
    return index
}

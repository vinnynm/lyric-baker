package com.enigma.devlyric.core

/**
 * A single lyric line with an optional timestamp (milliseconds from start).
 */
data class LyricLine(
    val timestampMs: Long?,   // null = no timestamp (plain lyric)
    val text: String
)

/**
 * A parsed lyric document, including optional metadata tags and lines.
 */
data class LyricDocument(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val offsetMs: Long = 0L,  // global offset from [offset:] tag
    val lines: List<LyricLine>
) {
    /** Returns lines as plain text (no timestamps). */
    fun toPlainText(): String = lines.joinToString("\n") { it.text }

    /** Returns lines in LRC format. */
    fun toLrcText(): String = buildString {
        title?.let { appendLine("[ti:$it]") }
        artist?.let { appendLine("[ar:$it]") }
        album?.let { appendLine("[al:$it]") }
        if (offsetMs != 0L) appendLine("[offset:$offsetMs]")
        for (line in lines) {
            val ts = line.timestampMs
            if (ts != null) {
                val mins = ts / 60000
                val secs = (ts % 60000) / 1000.0
                append("[%02d:%05.2f]".format(mins, secs))
            }
            appendLine(line.text)
        }
    }
}

/** The format of the source lyric file. */
enum class LyricFormat { LRC, SRT, PLAIN }

/** Result of a bake operation. */
sealed class BakeResult {
    data class Success(val bytesWritten: Int) : BakeResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : BakeResult()
}

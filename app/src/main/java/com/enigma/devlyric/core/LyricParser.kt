package com.enigma.devlyric.core

import java.io.File
import java.io.InputStream

/**
 * Parses LRC, SRT, and plain-text lyric files into a [LyricDocument].
 *
 * LRC format reference:
 *   [mm:ss.xx]Lyric text
 *   [ti:Title], [ar:Artist], [al:Album], [offset:ms]
 *
 * SRT format reference:
 *   index
 *   HH:MM:SS,mmm --> HH:MM:SS,mmm
 *   text line(s)
 *   (blank line)
 */
object LyricParser {

    private val LRC_TIMESTAMP = Regex("""^\[(\d{1,3}):(\d{2})\.(\d{2,3})](.*)$""")
    private val LRC_TAG       = Regex("""^\[(\w+):(.+)]$""")
    private val SRT_TIMESTAMP = Regex(
        """^(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})$"""
    )

    fun parse(file: File, format: LyricFormat = detectFormat(file)): LyricDocument =
        file.inputStream().use { parse(it, format) }

    fun parse(text: String, format: LyricFormat): LyricDocument =
        parse(text.byteInputStream(), format)

    fun parse(stream: InputStream, format: LyricFormat): LyricDocument {
        val lines = stream.bufferedReader(Charsets.UTF_8).readLines()
        return when (format) {
            LyricFormat.LRC   -> parseLrc(lines)
            LyricFormat.SRT   -> parseSrt(lines)
            LyricFormat.PLAIN -> parsePlain(lines)
        }
    }

    // ── LRC ──────────────────────────────────────────────────────────────────

    private fun parseLrc(rawLines: List<String>): LyricDocument {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var offsetMs: Long = 0L
        val lyricLines = mutableListOf<LyricLine>()

        for (raw in rawLines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            // A single line can have multiple timestamp tags: [00:10.00][00:20.00]text
            val timestamps = mutableListOf<Long>()
            var rest = line
            while (rest.startsWith("[")) {
                val close = rest.indexOf(']')
                if (close == -1) break
                val tag = rest.substring(1, close)
                rest = rest.substring(close + 1)

                val tsMatch = Regex("""^(\d{1,3}):(\d{2})\.(\d{2,3})$""").matchEntire(tag)
                if (tsMatch != null) {
                    val (mm, ss, cs) = tsMatch.destructured
                    val centis = cs.padEnd(3, '0').take(3).toLong()   // normalise to ms
                    timestamps += mm.toLong() * 60_000 + ss.toLong() * 1_000 + centis
                } else {
                    // metadata tag
                    val eqIdx = tag.indexOf(':')
                    if (eqIdx != -1) {
                        val key = tag.substring(0, eqIdx).lowercase()
                        val value = tag.substring(eqIdx + 1).trim()
                        when (key) {
                            "ti"     -> title = value
                            "ar"     -> artist = value
                            "al"     -> album = value
                            "offset" -> offsetMs = value.toLongOrNull() ?: 0L
                        }
                    }
                }
            }

            val text = rest  // whatever remains after all tags
            if (timestamps.isEmpty()) {
                // Bare metadata line or untimed lyric
                if (text.isNotBlank()) lyricLines += LyricLine(null, text)
            } else {
                timestamps.forEach { ts ->
                    lyricLines += LyricLine(ts + offsetMs, text)
                }
            }
        }

        lyricLines.sortBy { it.timestampMs ?: Long.MAX_VALUE }
        return LyricDocument(title, artist, album, offsetMs, lyricLines)
    }

    // ── SRT ──────────────────────────────────────────────────────────────────

    private fun parseSrt(rawLines: List<String>): LyricDocument {
        val lyricLines = mutableListOf<LyricLine>()
        var i = 0
        while (i < rawLines.size) {
            val line = rawLines[i].trim()
            // Skip index lines (pure integers) and blanks
            if (line.isEmpty() || line.matches(Regex("""^\d+$"""))) { i++; continue }

            val tsMatch = SRT_TIMESTAMP.matchEntire(line)
            if (tsMatch != null) {
                val (h, m, s, ms) = tsMatch.groupValues.drop(1).take(4)
                val startMs = h.toLong() * 3_600_000 + m.toLong() * 60_000 +
                              s.toLong() * 1_000 + ms.toLong()
                i++
                val textLines = mutableListOf<String>()
                while (i < rawLines.size && rawLines[i].trim().isNotEmpty()) {
                    textLines += rawLines[i].trim()
                    i++
                }
                val text = textLines.joinToString(" ")
                if (text.isNotBlank()) lyricLines += LyricLine(startMs, text)
                continue
            }
            i++
        }
        return LyricDocument(lines = lyricLines)
    }

    // ── Plain ─────────────────────────────────────────────────────────────────

    private fun parsePlain(rawLines: List<String>): LyricDocument =
        LyricDocument(lines = rawLines.filter { it.isNotBlank() }.map { LyricLine(null, it.trim()) })

    // ── Format detection ──────────────────────────────────────────────────────

    fun detectFormat(file: File): LyricFormat = when (file.extension.lowercase()) {
        "lrc"        -> LyricFormat.LRC
        "srt"        -> LyricFormat.SRT
        else         -> LyricFormat.PLAIN
    }
}

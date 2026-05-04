package com.enigma.devlyric.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Reads, modifies, and writes ID3v2.3 tags in MP3 files.
 *
 * ID3v2 structure (all big-endian):
 *   Header:  "ID3" | version(2) | flags(1) | syncsafe-size(4)
 *   Frames:  frame-id(4) | size(4) | flags(2) | data
 *
 * Relevant frames:
 *   USLT – Unsynchronised Lyrics (timed or plain)
 *   SYLT – Synchronised Lyrics    (timed, with ms offsets)
 *   COMM – Comment (used as fallback plain storage)
 */
internal object Id3v2Writer {

    private const val ID3_MAGIC = "ID3"
    private const val VERSION_MAJOR: Byte = 3      // ID3v2.3
    private const val VERSION_REVISION: Byte = 0

    /**
     * Bakes [lyrics] into the [mp3Bytes] byte array.
     * Returns a new byte array with the updated ID3v2 header.
     *
     * Strategy:
     *  1. If an ID3v2 tag already exists, parse it, strip any existing USLT/SYLT frames,
     *     append new ones, rebuild the tag.
     *  2. If no tag exists, create a fresh ID3v2.3 header.
     */
    fun bake(mp3Bytes: ByteArray, lyrics: LyricDocument, options: Mp3BakeOptions): ByteArray {
        val (existingFrames, audioStart) = extractExistingFrames(mp3Bytes)

        // Remove old lyric frames
        val filteredFrames = existingFrames.filter { (id, _) ->
            id !in setOf("USLT", "SYLT", "COMM")
        }.toMutableList()

        // USLT – plain/timed lyric text (most compatible)
        if (options.writeUslt) {
            filteredFrames += "USLT" to buildUsltFrame(lyrics, options.language)
        }

        // SYLT – synchronised lyrics (players that show scrolling lyrics)
        if (options.writeSylt && lyrics.lines.any { it.timestampMs != null }) {
            filteredFrames += "SYLT" to buildSyltFrame(lyrics, options.language)
        }

        val audioBytes = mp3Bytes.copyOfRange(audioStart, mp3Bytes.size)
        return buildTag(filteredFrames) + audioBytes
    }

    // ── Frame extraction ──────────────────────────────────────────────────────

    /** Returns (list of id→data pairs, byte offset where audio begins). */
    private fun extractExistingFrames(bytes: ByteArray): Pair<List<Pair<String, ByteArray>>, Int> {
        if (bytes.size < 10) return emptyList<Pair<String, ByteArray>>() to 0
        if (!bytes.startsWith(ID3_MAGIC)) return emptyList<Pair<String, ByteArray>>() to 0

        val tagSize = decodeSyncsafe(bytes, 6) + 10   // +10 for the header itself
        val frames = mutableListOf<Pair<String, ByteArray>>()
        var pos = 10   // skip ID3 header

        // Handle extended header (flag bit 6)
        val flags = bytes[5].toInt() and 0xFF
        if (flags and 0x40 != 0) {
            val extSize = ((bytes[pos].toInt() and 0xFF) shl 24) or
                          ((bytes[pos+1].toInt() and 0xFF) shl 16) or
                          ((bytes[pos+2].toInt() and 0xFF) shl 8)  or
                           (bytes[pos+3].toInt() and 0xFF)
            pos += extSize
        }

        while (pos + 10 <= tagSize && pos + 10 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break  // padding
            val size = ((bytes[pos+4].toInt() and 0xFF) shl 24) or
                       ((bytes[pos+5].toInt() and 0xFF) shl 16) or
                       ((bytes[pos+6].toInt() and 0xFF) shl 8)  or
                        (bytes[pos+7].toInt() and 0xFF)
            pos += 10
            if (size < 0 || pos + size > bytes.size) break
            frames += id to bytes.copyOfRange(pos, pos + size)
            pos += size
        }

        return frames to tagSize
    }

    // ── USLT frame ────────────────────────────────────────────────────────────

    /**
     * USLT frame data layout:
     *   encoding(1) | language(3) | content-descriptor(var+0x00) | lyrics(var)
     *
     * We use UTF-16LE (encoding=0x01) for full Unicode support.
     */
    private fun buildUsltFrame(lyrics: LyricDocument, language: String): ByteArray {
        val bos = ByteArrayOutputStream()
        bos.write(0x01)                                         // UTF-16 with BOM
        bos.write(language.take(3).padEnd(3, 'X').toByteArray(Charsets.ISO_8859_1))
        bos.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))    // BOM for descriptor (empty)
        bos.write(0x00); bos.write(0x00)                        // empty descriptor null terminator (UTF-16)
        bos.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))    // BOM for lyrics
        bos.write(lyrics.toLrcText().toByteArray(Charsets.UTF_16LE))
        bos.write(0x00); bos.write(0x00)                        // null terminator
        return bos.toByteArray()
    }

    // ── SYLT frame ────────────────────────────────────────────────────────────

    /**
     * SYLT frame data layout:
     *   encoding(1) | language(3) | timestamp-format(1) | content-type(1) |
     *   content-descriptor(var+0x00) |
     *   repeated: text(var+0x00) | timestamp(4)
     *
     * timestamp-format=2 → milliseconds
     * content-type=1     → lyrics
     */
    private fun buildSyltFrame(lyrics: LyricDocument, language: String): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.write(0x01)   // encoding: UTF-16
        dos.write(language.take(3).padEnd(3, 'X').toByteArray(Charsets.ISO_8859_1))
        dos.write(0x02)   // timestamp format: ms
        dos.write(0x01)   // content type: lyrics
        // empty content descriptor (UTF-16 null)
        dos.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        dos.write(0x00); dos.write(0x00)

        for (line in lyrics.lines) {
            val ts = line.timestampMs ?: continue
            // UTF-16LE text + null terminator
            dos.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            dos.write(line.text.toByteArray(Charsets.UTF_16LE))
            dos.write(0x00); dos.write(0x00)
            dos.writeInt(ts.toInt())
        }
        dos.flush()
        return bos.toByteArray()
    }

    // ── Tag assembly ──────────────────────────────────────────────────────────

    private fun buildTag(frames: List<Pair<String, ByteArray>>): ByteArray {
        // Serialise all frames
        val frameBytes = ByteArrayOutputStream()
        for ((id, data) in frames) {
            frameBytes.write(id.toByteArray(Charsets.ISO_8859_1))    // 4 bytes id
            frameBytes.write(encodeInt32(data.size))                        // 4 bytes size
            frameBytes.write(byteArrayOf(0x00, 0x00))                      // 2 bytes flags
            frameBytes.write(data)
        }

        val rawFrames = frameBytes.toByteArray()
        val padding = 512   // small padding for re-baking without full rewrite

        val bos = ByteArrayOutputStream()
        bos.write(ID3_MAGIC.toByteArray(Charsets.ISO_8859_1))
        bos.write(VERSION_MAJOR.toInt())
        bos.write(VERSION_REVISION.toInt())
        bos.write(0x00)   // flags
        bos.write(encodeSyncsafe(rawFrames.size + padding))
        bos.write(rawFrames)
        repeat(padding) { bos.write(0x00) }
        return bos.toByteArray()
    }

    // ── Encoding helpers ──────────────────────────────────────────────────────

    /** Decode a 4-byte syncsafe integer (MSB first, 7 bits per byte). */
    private fun decodeSyncsafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset  ].toInt() and 0x7F) shl 21) or
        ((bytes[offset+1].toInt() and 0x7F) shl 14) or
        ((bytes[offset+2].toInt() and 0x7F) shl  7) or
         (bytes[offset+3].toInt() and 0x7F)

    /** Encode an integer as a 4-byte syncsafe integer. */
    private fun encodeSyncsafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr  7) and 0x7F).toByte(),
        ( value         and 0x7F).toByte()
    )

    /** Encode an integer as a 4-byte big-endian value (for frame sizes). */
    private fun encodeInt32(value: Int): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr  8) and 0xFF).toByte(),
        ( value         and 0xFF).toByte()
    )

    private fun ByteArray.startsWith(s: String): Boolean {
        if (size < s.length) return false
        return String(this, 0, s.length, Charsets.ISO_8859_1) == s
    }
}

/** Options controlling what gets written to the MP3 ID3 tag. */
data class Mp3BakeOptions(
    /** Write USLT (unsynchronised lyrics) frame — most compatible. Default: true. */
    val writeUslt: Boolean = true,
    /** Write SYLT (synchronised lyrics) frame — enables scrolling lyrics. Default: true. */
    val writeSylt: Boolean = true,
    /** ISO 639-2 language code (3 chars) for the lyric frames. Default: "eng". */
    val language: String = "eng"
)

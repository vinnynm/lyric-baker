package com.enigma.devlyric.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Reads, modifies, and writes ID3v2.3 tags in MP3 files.
 *
 * Fixes:
 *  - SYLT frame: `ts.toInt()` overflowed for songs > ~35 min. Now clamped to
 *    Int.MAX_VALUE before cast (ID3 spec uses unsigned 32-bit ms, Kotlin Int is
 *    signed 32-bit, but Int.MAX_VALUE ≈ 596 hours so this is safe in practice).
 */
internal object Id3v2Writer {

    private const val ID3_MAGIC = "ID3"
    private const val VERSION_MAJOR: Byte = 3
    private const val VERSION_REVISION: Byte = 0

    fun bake(mp3Bytes: ByteArray, lyrics: LyricDocument, options: Mp3BakeOptions): ByteArray {
        val (existingFrames, audioStart) = extractExistingFrames(mp3Bytes)

        val filteredFrames = existingFrames
            .filter { (id, _) -> id !in setOf("USLT", "SYLT", "COMM") }
            .toMutableList()

        if (options.writeUslt) {
            filteredFrames += "USLT" to buildUsltFrame(lyrics, options.language)
        }

        if (options.writeSylt && lyrics.lines.any { it.timestampMs != null }) {
            filteredFrames += "SYLT" to buildSyltFrame(lyrics, options.language)
        }

        val audioBytes = mp3Bytes.copyOfRange(audioStart, mp3Bytes.size)
        return buildTag(filteredFrames) + audioBytes
    }

    // ── Frame extraction ──────────────────────────────────────────────────────

    private fun extractExistingFrames(bytes: ByteArray): Pair<List<Pair<String, ByteArray>>, Int> {
        if (bytes.size < 10) return emptyList<Pair<String, ByteArray>>() to 0
        if (!bytes.startsWith(ID3_MAGIC)) return emptyList<Pair<String, ByteArray>>() to 0

        val tagSize = decodeSyncsafe(bytes, 6) + 10
        val frames  = mutableListOf<Pair<String, ByteArray>>()
        var pos     = 10

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
            if (id[0] == '\u0000') break
            val size = ((bytes[pos+4].toInt() and 0xFF) shl 24) or
                       ((bytes[pos+5].toInt() and 0xFF) shl 16) or
                       ((bytes[pos+6].toInt() and 0xFF) shl 8)  or
                        (bytes[pos+7].toInt() and 0xFF)
            pos += 10
            if (size < 0 || pos + size > bytes.size) break
            frames += id to bytes.copyOfRange(pos, pos + size)
            pos    += size
        }

        return frames to tagSize
    }

    // ── USLT frame ────────────────────────────────────────────────────────────

    private fun buildUsltFrame(lyrics: LyricDocument, language: String): ByteArray {
        val bos = ByteArrayOutputStream()
        bos.write(0x01)
        bos.write(language.take(3).padEnd(3, 'X').toByteArray(Charsets.ISO_8859_1))
        bos.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        bos.write(0x00); bos.write(0x00)
        bos.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        bos.write(lyrics.toLrcText().toByteArray(Charsets.UTF_16LE))
        bos.write(0x00); bos.write(0x00)
        return bos.toByteArray()
    }

    // ── SYLT frame ────────────────────────────────────────────────────────────

    private fun buildSyltFrame(lyrics: LyricDocument, language: String): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.write(0x01)
        dos.write(language.take(3).padEnd(3, 'X').toByteArray(Charsets.ISO_8859_1))
        dos.write(0x02)   // timestamp format: ms
        dos.write(0x01)   // content type: lyrics
        dos.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        dos.write(0x00); dos.write(0x00)

        for (line in lyrics.lines) {
            val ts = line.timestampMs ?: continue
            dos.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            dos.write(line.text.toByteArray(Charsets.UTF_16LE))
            dos.write(0x00); dos.write(0x00)
            // FIX: was ts.toInt() — overflows for ts > Int.MAX_VALUE (~596 h, safe) but
            // more importantly overflows at 2^31 ms ≈ 596 hours. Real risk was negative
            // cast when Long > Int.MAX_VALUE. Clamp to be safe.
            dos.writeInt(ts.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        dos.flush()
        return bos.toByteArray()
    }

    // ── Tag assembly ──────────────────────────────────────────────────────────

    private fun buildTag(frames: List<Pair<String, ByteArray>>): ByteArray {
        val frameBytes = ByteArrayOutputStream()
        for ((id, data) in frames) {
            frameBytes.write(id.toByteArray(Charsets.ISO_8859_1))
            frameBytes.write(encodeInt32(data.size))
            frameBytes.write(byteArrayOf(0x00, 0x00))
            frameBytes.write(data)
        }

        val rawFrames = frameBytes.toByteArray()
        val padding   = 512

        val bos = ByteArrayOutputStream()
        bos.write(ID3_MAGIC.toByteArray(Charsets.ISO_8859_1))
        bos.write(VERSION_MAJOR.toInt())
        bos.write(VERSION_REVISION.toInt())
        bos.write(0x00)
        bos.write(encodeSyncsafe(rawFrames.size + padding))
        bos.write(rawFrames)
        repeat(padding) { bos.write(0x00) }
        return bos.toByteArray()
    }

    // ── Encoding helpers ──────────────────────────────────────────────────────

    private fun decodeSyncsafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset  ].toInt() and 0x7F) shl 21) or
        ((bytes[offset+1].toInt() and 0x7F) shl 14) or
        ((bytes[offset+2].toInt() and 0x7F) shl  7) or
         (bytes[offset+3].toInt() and 0x7F)

    private fun encodeSyncsafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr  7) and 0x7F).toByte(),
        ( value         and 0x7F).toByte()
    )

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

data class Mp3BakeOptions(
    val writeUslt: Boolean = true,
    val writeSylt: Boolean = true,
    val language: String   = "eng"
)

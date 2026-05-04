package com.enigma.devlyric.core

import java.nio.charset.Charset

/**
 * Basic reader for ID3v2.3 tags to verify lyrics were written correctly.
 */
internal object Id3v2Reader {

    private const val ID3_MAGIC = "ID3"

    fun readLyrics(bytes: ByteArray): LyricDocument? {
        if (bytes.size < 10 || !bytes.startsWith(ID3_MAGIC)) return null

        val tagSize = decodeSyncsafe(bytes, 6) + 10
        var pos = 10

        // Handle extended header
        val flags = bytes[5].toInt() and 0xFF
        if (flags and 0x40 != 0) {
            val extSize = readInt32(bytes, pos)
            pos += extSize
        }

        var usltData: ByteArray? = null

        while (pos + 10 <= tagSize && pos + 10 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = readInt32(bytes, pos + 4)
            pos += 10
            if (size < 0 || pos + size > bytes.size) break
            
            if (id == "USLT") {
                usltData = bytes.copyOfRange(pos, pos + size)
            }
            pos += size
        }

        return usltData?.let { parseUslt(it) }
    }

    private fun parseUslt(data: ByteArray): LyricDocument? {
        if (data.size < 5) return null
        val encoding = data[0].toInt() and 0xFF
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        
        // Skip encoding(1) and language(3)
        var pos = 4
        
        // Find end of descriptor (null terminator)
        val descriptorEnd = findNullTerminator(data, pos, charset)
        pos = descriptorEnd + (if (charset == Charsets.UTF_16 || charset == Charsets.UTF_16BE) 2 else 1)
        
        if (pos >= data.size) return null
        
        val lyricsText = String(data, pos, data.size - pos, charset).trim { it <= '\u0000' }
        return LyricParser.parse(lyricsText, LyricFormat.LRC)
    }

    private fun findNullTerminator(data: ByteArray, start: Int, charset: Charset): Int {
        var i = start
        val step = if (charset == Charsets.UTF_16 || charset == Charsets.UTF_16BE) 2 else 1
        while (i + step <= data.size) {
            if (step == 1) {
                if (data[i] == 0.toByte()) return i
            } else {
                if (data[i] == 0.toByte() && data[i+1] == 0.toByte()) return i
            }
            i += step
        }
        return data.size
    }

    private fun decodeSyncsafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset  ].toInt() and 0x7F) shl 21) or
        ((bytes[offset+1].toInt() and 0x7F) shl 14) or
        ((bytes[offset+2].toInt() and 0x7F) shl  7) or
         (bytes[offset+3].toInt() and 0x7F)

    private fun readInt32(data: ByteArray, pos: Int): Int =
        ((data[pos  ].toInt() and 0xFF) shl 24) or
        ((data[pos+1].toInt() and 0xFF) shl 16) or
        ((data[pos+2].toInt() and 0xFF) shl  8) or
         (data[pos+3].toInt() and 0xFF)

    private fun ByteArray.startsWith(s: String): Boolean {
        if (size < s.length) return false
        return String(this, 0, s.length, Charsets.ISO_8859_1) == s
    }
}

package com.enigma.devlyric.core

/**
 * Basic reader for iTunes atoms to verify lyrics were written correctly.
 */
internal object ItunesAtomReader {

    private val LYRICS_ATOM = byteArrayOf(0xA9.toByte(), 0x6C, 0x79, 0x72) // ©lyr

    fun readLyrics(bytes: ByteArray): LyricDocument? {
        val atoms = parseAtoms(bytes, 0, bytes.size)
        val moov = atoms.find { it.type == "moov" } ?: return null
        val udta = parseAtoms(bytes, moov.dataOffset, moov.dataOffset + moov.dataSize).find { it.type == "udta" } ?: return null
        val meta = parseAtoms(bytes, udta.dataOffset, udta.dataOffset + udta.dataSize).find { it.type == "meta" } ?: return null
        
        // meta has 4 bytes version/flags before children
        val ilst = parseAtoms(bytes, meta.dataOffset + 4, meta.dataOffset + meta.dataSize).find { it.type == "ilst" } ?: return null
        val lyr = parseAtoms(bytes, ilst.dataOffset, ilst.dataOffset + ilst.dataSize).find { it.typeBytes.contentEquals(LYRICS_ATOM) } ?: return null
        val data = parseAtoms(bytes, lyr.dataOffset, lyr.dataOffset + lyr.dataSize).find { it.type == "data" } ?: return null
        
        // data atom: version(1) + flags(3) + locale(4) + text
        if (data.dataSize < 8) return null
        val text = String(bytes, data.dataOffset + 8, data.dataSize - 8, Charsets.UTF_8)
        return LyricParser.parse(text, LyricFormat.LRC)
    }

    private data class Atom(
        val dataOffset: Int,
        val dataSize: Int,
        val type: String,
        val typeBytes: ByteArray
    )

    private fun parseAtoms(src: ByteArray, from: Int, to: Int): List<Atom> {
        val list = mutableListOf<Atom>()
        var pos = from
        while (pos + 8 <= to && pos + 8 <= src.size) {
            val size = readInt32(src, pos)
            val typeBytes = src.copyOfRange(pos + 4, pos + 8)
            val type = String(typeBytes, Charsets.ISO_8859_1)
            val dataOffset: Int
            val dataSize: Int

            if (size == 1) {
                if (pos + 16 > src.size) break
                val bigSize = readInt64(src, pos + 8)
                dataOffset = pos + 16
                dataSize = bigSize.toInt() - 16
                pos += bigSize.toInt()
            } else {
                dataOffset = pos + 8
                dataSize = size - 8
                pos += size
            }
            list += Atom(dataOffset, dataSize, type, typeBytes)
            if (size <= 0) break
        }
        return list
    }

    private fun readInt32(data: ByteArray, pos: Int): Int =
        ((data[pos  ].toInt() and 0xFF) shl 24) or
        ((data[pos+1].toInt() and 0xFF) shl 16) or
        ((data[pos+2].toInt() and 0xFF) shl  8) or
         (data[pos+3].toInt() and 0xFF)

    private fun readInt64(data: ByteArray, pos: Int): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
        return v
    }
}

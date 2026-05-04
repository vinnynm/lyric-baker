package com.enigma.devlyric.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Reads and writes iTunes metadata atoms inside M4A/MP4 files to embed lyrics.
 *
 * M4A/MP4 atom structure:
 *   size(4) | type(4) | [version(1) | flags(3)] | data...
 *
 * iTunes metadata lives in:
 *   moov → udta → meta → ilst
 *
 * The lyrics atom is:
 *   ilst / ©lyr / data  (©lyr = 0xA9 6C 79 72)
 *
 * We also write:
 *   ©lyr  – lyrics as UTF-8 string
 *
 * Strategy:
 *   1. Parse the top-level atom tree.
 *   2. Locate moov → udta → meta → ilst, or create the chain if absent.
 *   3. Replace/insert ©lyr atom.
 *   4. Update all ancestor atom sizes.
 *   5. Concatenate: everything before moov + rebuilt moov + everything after moov.
 *
 * Note: mdat (audio data) offsets stored in stco/co64 atoms may need to be updated
 * if moov is BEFORE mdat and grows. We handle this with [fixupChunkOffsets].
 */
internal object ItunesAtomWriter {

    private val LYRICS_ATOM = byteArrayOf(0xA9.toByte(), 0x6C, 0x79, 0x72)   // ©lyr
    private const val ILST  = "ilst"
    private const val META  = "meta"
    private const val UDTA  = "udta"
    private const val MOOV  = "moov"
    private const val TRAK  = "trak"
    private const val MDIA  = "mdia"
    private const val MINF  = "minf"
    private const val STBL  = "stbl"
    private const val STCO  = "stco"
    private const val CO64  = "co64"

    fun bake(m4aBytes: ByteArray, lyrics: LyricDocument, options: M4aBakeOptions): ByteArray {
        val atoms = parseAtoms(m4aBytes, 0, m4aBytes.size)

        val moovIndex = atoms.indexOfFirst { it.type == MOOV }
        if (moovIndex == -1) error("No 'moov' atom found — not a valid M4A/MP4 file")

        val moovAtom = atoms[moovIndex]
        val originalMoovSize = moovAtom.totalSize

        // Build new moov with lyrics injected
        val newMoovData = injectLyrics(m4aBytes, moovAtom, lyrics, options)
        val newMoovSize = newMoovData.size
        val delta = newMoovSize - originalMoovSize

        // Assemble output
        val out = ByteArrayOutputStream(m4aBytes.size + delta + 256)
        // atoms before moov
        for (i in 0 until moovIndex) {
            out.write(m4aBytes, atoms[i].offset, atoms[i].totalSize)
        }
        // new moov
        out.write(newMoovData)
        // atoms after moov
        for (i in moovIndex + 1 until atoms.size) {
            out.write(m4aBytes, atoms[i].offset, atoms[i].totalSize)
        }

        val result = out.toByteArray()

        // Fix stco/co64 chunk offsets if moov comes before mdat
        val moovOffset  = atoms.subList(0, moovIndex).sumOf { it.totalSize }
        val mdatAtom    = atoms.firstOrNull { it.type == "mdat" }
        return if (delta != 0 && mdatAtom != null && mdatAtom.offset > moovOffset) {
            fixupChunkOffsets(result, delta)
        } else result
    }

    // ── Lyric injection ───────────────────────────────────────────────────────

    private fun injectLyrics(
        src: ByteArray,
        moovAtom: Atom,
        lyrics: LyricDocument,
        options: M4aBakeOptions
    ): ByteArray {
        // Recursively rebuild moov with lyrics in ilst
        val moovChildren = parseAtoms(src, moovAtom.dataOffset, moovAtom.dataOffset + moovAtom.dataSize)
        val newMoovChildren = moovChildren.map { child ->
            if (child.type == UDTA) rebuildUdta(src, child, lyrics, options)
            else src.copyOfRange(child.offset, child.offset + child.totalSize)
        }.toMutableList()

        // Add udta if absent
        if (moovChildren.none { it.type == UDTA }) {
            newMoovChildren += buildUdtaFromScratch(lyrics, options)
        }

        return wrapAtom(MOOV.toByteArray(Charsets.ISO_8859_1), newMoovChildren.concat())
    }

    private fun rebuildUdta(src: ByteArray, udta: Atom, lyrics: LyricDocument, opts: M4aBakeOptions): ByteArray {
        val children = parseAtoms(src, udta.dataOffset, udta.dataOffset + udta.dataSize)
        val newChildren = children.map { child ->
            if (child.type == META) rebuildMeta(src, child, lyrics, opts)
            else src.copyOfRange(child.offset, child.offset + child.totalSize)
        }.toMutableList()
        if (children.none { it.type == META }) {
            newChildren += buildMetaFromScratch(lyrics, opts)
        }
        return wrapAtom(UDTA.toByteArray(Charsets.ISO_8859_1), newChildren.concat())
    }

    private fun rebuildMeta(src: ByteArray, meta: Atom, lyrics: LyricDocument, opts: M4aBakeOptions): ByteArray {
        // meta has a 4-byte version/flags prefix before its children
        val versionFlags = src.copyOfRange(meta.dataOffset, meta.dataOffset + 4)
        val children = parseAtoms(src, meta.dataOffset + 4, meta.dataOffset + meta.dataSize)
        val newChildren = children.map { child ->
            if (child.type == ILST) rebuildIlst(src, child, lyrics, opts)
            else src.copyOfRange(child.offset, child.offset + child.totalSize)
        }.toMutableList()
        if (children.none { it.type == ILST }) {
            newChildren += buildIlstWithLyrics(lyrics, opts)
        }
        val innerData = versionFlags + newChildren.concat()
        return wrapAtom(META.toByteArray(Charsets.ISO_8859_1), innerData)
    }

    private fun rebuildIlst(src: ByteArray, ilst: Atom, lyrics: LyricDocument, opts: M4aBakeOptions): ByteArray {
        val children = parseAtoms(src, ilst.dataOffset, ilst.dataOffset + ilst.dataSize)
        val newChildren = children
            .filter { it.typeBytes.contentEquals(LYRICS_ATOM).not() || it.type != "\u00A9lyr" }
            .filter { it.type != "\u00A9lyr" }
            .map { src.copyOfRange(it.offset, it.offset + it.totalSize) }
            .toMutableList()

        if (opts.writeLyrics) {
            newChildren += buildLyricsAtom(lyrics)
        }
        return wrapAtom(ILST.toByteArray(Charsets.ISO_8859_1), newChildren.concat())
    }

    // ── Atom builders ─────────────────────────────────────────────────────────

    private fun buildUdtaFromScratch(lyrics: LyricDocument, opts: M4aBakeOptions): ByteArray =
        wrapAtom(UDTA.toByteArray(Charsets.ISO_8859_1), buildMetaFromScratch(lyrics, opts))

    private fun buildMetaFromScratch(lyrics: LyricDocument, opts: M4aBakeOptions): ByteArray {
        val versionFlags = byteArrayOf(0, 0, 0, 0)
        val ilst = buildIlstWithLyrics(lyrics, opts)
        return wrapAtom(META.toByteArray(Charsets.ISO_8859_1), versionFlags + ilst)
    }

    private fun buildIlstWithLyrics(lyrics: LyricDocument, opts: M4aBakeOptions): ByteArray {
        val items = mutableListOf<ByteArray>()
        if (opts.writeLyrics) items += buildLyricsAtom(lyrics)
        return wrapAtom(ILST.toByteArray(Charsets.ISO_8859_1), items.concat())
    }

    /**
     * ©lyr atom structure:
     *   ©lyr / data / [version(1)+flags(3)+locale(4)] / UTF-8 text
     *   flags = 0x00000001 (well-known type, UTF-8)
     */
    private fun buildLyricsAtom(lyrics: LyricDocument): ByteArray {
        val text = lyrics.toLrcText().toByteArray(Charsets.UTF_8)
        val dataHeader = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,   // flags: UTF-8
            0x00, 0x00, 0x00, 0x00    // locale
        )
        val dataPayload = dataHeader + text
        val dataAtom = wrapAtom("data".toByteArray(Charsets.ISO_8859_1), dataPayload)
        return wrapAtom(LYRICS_ATOM, dataAtom)
    }

    // ── Chunk offset fixup ────────────────────────────────────────────────────

    /**
     * After resizing moov, all absolute byte offsets stored in stco/co64 atoms
     * (pointing into mdat) must be incremented by [delta].
     */
    private fun fixupChunkOffsets(data: ByteArray, delta: Int): ByteArray {
        val copy = data.copyOf()
        patchOffsets(copy, 0, copy.size, delta)
        return copy
    }

    private fun patchOffsets(data: ByteArray, from: Int, to: Int, delta: Int) {
        val atoms = parseAtoms(data, from, to)
        for (atom in atoms) {
            when (atom.type) {
                STCO -> patchStco(data, atom, delta)
                CO64 -> patchCo64(data, atom, delta.toLong())
                MOOV, TRAK, MDIA, MINF, STBL -> patchOffsets(data, atom.dataOffset, atom.dataOffset + atom.dataSize, delta)
            }
        }
    }

    private fun patchStco(data: ByteArray, atom: Atom, delta: Int) {
        var pos = atom.dataOffset + 4   // skip version+flags
        val count = readInt32(data, pos); pos += 4
        repeat(count) {
            val old = readInt32(data, pos)
            writeInt32(data, pos, old + delta)
            pos += 4
        }
    }

    private fun patchCo64(data: ByteArray, atom: Atom, delta: Long) {
        var pos = atom.dataOffset + 4
        val count = readInt32(data, pos); pos += 4
        repeat(count) {
            val old = readInt64(data, pos)
            writeInt64(data, pos, old + delta)
            pos += 8
        }
    }

    // ── Atom parsing ──────────────────────────────────────────────────────────

    private data class Atom(
        val offset: Int,
        val totalSize: Int,
        val dataOffset: Int,
        val dataSize: Int,
        val type: String,
        val typeBytes: ByteArray
    )

    private fun parseAtoms(src: ByteArray, from: Int, to: Int): List<Atom> {
        val list = mutableListOf<Atom>()
        var pos = from
        while (pos + 8 <= to && pos + 8 <= src.size) {
            var size = readInt32(src, pos)
            val typeBytes = src.copyOfRange(pos + 4, pos + 8)
            val type = String(typeBytes, Charsets.ISO_8859_1)
            val dataOffset: Int
            val dataSize: Int
            val totalSize: Int

            when (size) {
                0    -> { totalSize = src.size - pos; dataOffset = pos + 8; dataSize = totalSize - 8 }
                1    -> {
                    // 64-bit size
                    if (pos + 16 > src.size) break
                    val bigSize = readInt64(src, pos + 8)
                    totalSize = bigSize.toInt()   // safe for files < 2 GB
                    dataOffset = pos + 16
                    dataSize = totalSize - 16
                }
                else -> { totalSize = size; dataOffset = pos + 8; dataSize = totalSize - 8 }
            }

            if (totalSize < 8 || pos + totalSize > src.size) break
            list += Atom(pos, totalSize, dataOffset, dataSize, type, typeBytes)
            pos += totalSize
        }
        return list
    }

    // ── Low-level helpers ─────────────────────────────────────────────────────

    private fun wrapAtom(type: ByteArray, data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(8 + data.size)
        val dos = DataOutputStream(bos)
        dos.writeInt(8 + data.size)
        dos.write(type)
        dos.write(data)
        dos.flush()
        return bos.toByteArray()
    }

    private fun List<ByteArray>.concat(): ByteArray {
        val total = sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        forEach { System.arraycopy(it, 0, out, pos, it.size); pos += it.size }
        return out
    }

    private fun readInt32(data: ByteArray, pos: Int): Int =
        ((data[pos  ].toInt() and 0xFF) shl 24) or
        ((data[pos+1].toInt() and 0xFF) shl 16) or
        ((data[pos+2].toInt() and 0xFF) shl  8) or
         (data[pos+3].toInt() and 0xFF)

    private fun writeInt32(data: ByteArray, pos: Int, value: Int) {
        data[pos  ] = ((value shr 24) and 0xFF).toByte()
        data[pos+1] = ((value shr 16) and 0xFF).toByte()
        data[pos+2] = ((value shr  8) and 0xFF).toByte()
        data[pos+3] = ( value         and 0xFF).toByte()
    }

    private fun readInt64(data: ByteArray, pos: Int): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
        return v
    }

    private fun writeInt64(data: ByteArray, pos: Int, value: Long) {
        var v = value
        for (i in 7 downTo 0) { data[pos + i] = (v and 0xFF).toByte(); v = v ushr 8 }
    }
}

/** Options controlling what gets written to the M4A iTunes metadata. */
data class M4aBakeOptions(
    /** Write ©lyr atom with LRC-formatted lyrics. Default: true. */
    val writeLyrics: Boolean = true
)

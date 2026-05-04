package com.enigma.devlyric.core

import kotlin.collections.get

class LyricParserTest {

    private fun assertEquals(expected: Any?, actual: Any?, message: String? = null) {
        if (expected != actual) throw AssertionError(message ?: "Expected: $expected, but was: $actual")
    }

    private fun assertNull(actual: Any?, message: String? = null) {
        if (actual != null) throw AssertionError(message ?: "Expected null, but was: $actual")
    }

    @org.junit.Test
    fun `parse basic LRC file`() {
        val lrc = """
            [ti:Test Song]
            [ar:Test Artist]
            [al:Test Album]
            [00:10.00]First line
            [00:20.50]Second line
            [01:05.33]Third line
        """.trimIndent()

        val doc = LyricParser.parse(lrc, LyricFormat.LRC)
        assertEquals("Test Song",   doc.title)
        assertEquals("Test Artist", doc.artist)
        assertEquals("Test Album",  doc.album)
        assertEquals(3, doc.lines.size)
        assertEquals(10_000L, doc.lines[0].timestampMs)
        assertEquals("First line",  doc.lines[0].text)
        assertEquals(20_500L, doc.lines[1].timestampMs)
        assertEquals(65_330L, doc.lines[2].timestampMs)
    }

    @org.junit.Test
    fun `parse LRC with multiple timestamps on one line`() {
        val lrc = "[00:10.00][00:20.00]Chorus line"
        val doc = LyricParser.parse(lrc, LyricFormat.LRC)
        assertEquals(2, doc.lines.size)
        assertEquals(10_000L, doc.lines[0].timestampMs)
        assertEquals(20_000L, doc.lines[1].timestampMs)
        doc.lines.forEach { assertEquals("Chorus line", it.text) }
    }

    @org.junit.Test
    fun `parse LRC with offset tag`() {
        val lrc = "[offset:500]\n[00:10.00]Line"
        val doc = LyricParser.parse(lrc, LyricFormat.LRC)
        assertEquals(500L, doc.offsetMs)
        assertEquals(10_500L, doc.lines[0].timestampMs)
    }

    @org.junit.Test
    fun `parse SRT file`() {
        val srt = """
            1
            00:00:10,000 --> 00:00:12,500
            Hello world

            2
            00:00:15,000 --> 00:00:17,000
            Second subtitle
        """.trimIndent()

        val doc = LyricParser.parse(srt, LyricFormat.SRT)
        assertEquals(2, doc.lines.size)
        assertEquals(10_000L, doc.lines[0].timestampMs)
        assertEquals("Hello world", doc.lines[0].text)
        assertEquals(15_000L, doc.lines[1].timestampMs)
    }

    @org.junit.Test
    fun `parse plain text`() {
        val plain = "Line one\nLine two\n\nLine three"
        val doc = LyricParser.parse(plain, LyricFormat.PLAIN)
        assertEquals(3, doc.lines.size)
        doc.lines.forEach { assertNull(it.timestampMs) }
        assertEquals("Line one",   doc.lines[0].text)
        assertEquals("Line three", doc.lines[2].text)
    }

    @org.junit.Test
    fun `toLrcText roundtrip`() {
        val lrc = "[ti:Song]\n[ar:Artist]\n[00:10.00]Hello\n[00:20.50]World\n"
        val doc = LyricParser.parse(lrc, LyricFormat.LRC)
        val lrcOut = doc.toLrcText()
        val doc2 = LyricParser.parse(lrcOut, LyricFormat.LRC)
        assertEquals(doc.lines.size, doc2.lines.size)
        assertEquals(doc.lines[0].timestampMs, doc2.lines[0].timestampMs)
        assertEquals(doc.lines[0].text, doc2.lines[0].text)
    }
}

class LyricDocumentTest {

    private fun assertEquals(expected: Any?, actual: Any?, message: String? = null) {
        if (expected != actual) throw AssertionError(message ?: "Expected: $expected, but was: $actual")
    }

    @org.junit.Test
    fun `toPlainText strips timestamps`() {
        val doc = LyricDocument(lines = listOf(
            LyricLine(1000L, "Hello"),
            LyricLine(2000L, "World")
        ))
        assertEquals("Hello\nWorld", doc.toPlainText())
    }
}

class Id3v2WriterTest {

    private fun assertEquals(expected: Any?, actual: Any?, message: String? = null) {
        if (expected != actual) throw AssertionError(message ?: "Expected: $expected, but was: $actual")
    }

    private fun assertNotNull(actual: Any?, message: String? = null) {
        if (actual == null) throw AssertionError(message ?: "Expected non-null, but was null")
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray, message: String? = null) {
        if (!expected.contentEquals(actual)) throw AssertionError(message ?: "Arrays are not equal")
    }

    private fun minimalMp3(): ByteArray {
        return byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00.toByte()) +
               ByteArray(128)
    }

    private fun sampleDoc() = LyricDocument(
        title  = "Test",
        artist = "Artist",
        lines  = listOf(
            LyricLine(1000L, "Hello"),
            LyricLine(2000L, "World")
        )
    )

    @org.junit.Test
    fun `bake creates ID3 header`() {
        val mp3 = minimalMp3()
        val result = Id3v2Writer.bake(mp3, sampleDoc(), Mp3BakeOptions())
        assertEquals('I', result[0].toInt().toChar())
        assertEquals('D', result[1].toInt().toChar())
        assertEquals('3', result[2].toInt().toChar())
    }

    @org.junit.Test
    fun `bake roundtrip – lyrics readable`() {
        val mp3 = minimalMp3()
        val doc = sampleDoc()
        val baked = Id3v2Writer.bake(mp3, doc, Mp3BakeOptions(writeUslt = true, writeSylt = false))
        val extracted = Id3v2Reader.readLyrics(baked)
        assertNotNull(extracted)
        assertEquals(doc.lines.size, extracted!!.lines.size)
        assertEquals(doc.lines[0].text, extracted.lines[0].text)
    }

    @org.junit.Test
    fun `bake replaces existing USLT frame`() {
        val mp3 = minimalMp3()
        val doc1 = LyricDocument(lines = listOf(LyricLine(1000L, "Old lyric")))
        val doc2 = LyricDocument(lines = listOf(LyricLine(1000L, "New lyric")))

        val baked1 = Id3v2Writer.bake(mp3, doc1, Mp3BakeOptions())
        val baked2 = Id3v2Writer.bake(baked1, doc2, Mp3BakeOptions())

        val extracted = Id3v2Reader.readLyrics(baked2)
        assertNotNull(extracted)
        assertEquals("New lyric", extracted!!.lines[0].text)
    }

    @org.junit.Test
    fun `audio data preserved after bake`() {
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00.toByte(), 1, 2, 3, 4, 5)
        val baked = Id3v2Writer.bake(audio, sampleDoc(), Mp3BakeOptions())
        val tail = baked.takeLast(5).toByteArray()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), tail)
    }
}

class M4aAtomTest {

    private fun assertEquals(expected: Any?, actual: Any?, message: String? = null) {
        if (expected != actual) throw AssertionError(message ?: "Expected: $expected, but was: $actual")
    }

    private fun assertNotNull(actual: Any?, message: String? = null) {
        if (actual == null) throw AssertionError(message ?: "Expected non-null, but was null")
    }

    private fun assertTrue(condition: Boolean, message: String? = null) {
        if (!condition) throw AssertionError(message ?: "Expected true, but was false")
    }

    private fun minimalM4a(): ByteArray {
        val ftyp = buildAtom("ftyp", byteArrayOf(
            *"M4A ".toByteArray(Charsets.ISO_8859_1),
            0, 0, 0, 0,
            *"isom".toByteArray(Charsets.ISO_8859_1)
        ))

        val stco = buildAtom("stco", byteArrayOf(0,0,0,0, 0,0,0,0))
        val stbl = buildAtom("stbl", stco)
        val minf = buildAtom("minf", stbl)
        val mdia = buildAtom("mdia", minf)
        val trak = buildAtom("trak", mdia)
        val moov = buildAtom("moov", trak)

        val mdat = buildAtom("mdat", byteArrayOf(1, 2, 3, 4, 5))

        return ftyp + moov + mdat
    }

    private fun buildAtom(type: String, data: ByteArray): ByteArray {
        val size = 8 + data.size
        return byteArrayOf(
            ((size shr 24) and 0xFF).toByte(),
            ((size shr 16) and 0xFF).toByte(),
            ((size shr  8) and 0xFF).toByte(),
            ( size         and 0xFF).toByte()
        ) + type.toByteArray(Charsets.ISO_8859_1) + data
    }

    private fun sampleDoc() = LyricDocument(
        lines = listOf(
            LyricLine(1000L, "Hello"),
            LyricLine(2000L, "World")
        )
    )

    @org.junit.Test
    fun `bake creates moov atom in output`() {
        val m4a = minimalM4a()
        val baked = ItunesAtomWriter.bake(m4a, sampleDoc(), M4aBakeOptions())
        val moovIdx = findAtomOffset(baked, "moov")
        assertTrue(moovIdx >= 0, "moov atom should be present")
    }

    @org.junit.Test
    fun `bake roundtrip – lyrics readable`() {
        val m4a = minimalM4a()
        val doc = sampleDoc()
        val baked = ItunesAtomWriter.bake(m4a, doc, M4aBakeOptions())
        val extracted = ItunesAtomReader.readLyrics(baked)
        assertNotNull(extracted, "Should be able to extract lyrics")
        assertEquals(doc.lines.size, extracted!!.lines.size)
        assertEquals("Hello", extracted.lines[0].text)
    }

    @org.junit.Test
    fun `bake replaces existing lyrics`() {
        val m4a = minimalM4a()
        val doc1 = LyricDocument(lines = listOf(LyricLine(0L, "Old")))
        val doc2 = LyricDocument(lines = listOf(LyricLine(0L, "New")))
        val baked1 = ItunesAtomWriter.bake(m4a, doc1, M4aBakeOptions())
        val baked2 = ItunesAtomWriter.bake(baked1, doc2, M4aBakeOptions())
        val extracted = ItunesAtomReader.readLyrics(baked2)
        assertNotNull(extracted)
        assertEquals("New", extracted!!.lines[0].text)
    }

    private fun findAtomOffset(bytes: ByteArray, type: String): Int {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val size = ((bytes[pos].toInt() and 0xFF) shl 24) or
                       ((bytes[pos+1].toInt() and 0xFF) shl 16) or
                       ((bytes[pos+2].toInt() and 0xFF) shl 8)  or
                        (bytes[pos+3].toInt() and 0xFF)
            val t = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            if (t == type) return pos
            if (size < 8) break
            pos += size
        }
        return -1
    }
}

class LyricBakerPublicApiTest {

    private fun assertTrue(condition: Boolean, message: String? = null) {
        if (!condition) throw AssertionError(message ?: "Expected true, but was false")
    }

    @org.junit.Test
    fun `bakeBytes throws on unsupported extension`() {
        try {
            LyricBaker.bakeBytes(ByteArray(10), "wav",
                LyricDocument(lines = listOf(LyricLine(0L, "x"))))
            throw AssertionError("Should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            // Success
        }
    }

    @org.junit.Test
    fun `bakeBytes mp3 returns non-empty result`() {
        val fakeAudio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte()) + ByteArray(64)
        val doc = LyricDocument(lines = listOf(LyricLine(1000L, "Test")))
        val result = LyricBaker.bakeBytes(fakeAudio, "mp3", doc)
        assertTrue(result.isNotEmpty())
        assertTrue(result.size > fakeAudio.size)
    }
}

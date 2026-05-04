package com.enigma.devlyric.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * # LyricBaker
 *
 * Bakes lyric files directly into MP3 and M4A audio files using native
 * Kotlin byte-level manipulation — no third-party tag libraries required.
 *
 * ## Supported formats
 * - **MP3**: ID3v2.3 `USLT` (unsynchronised) and `SYLT` (synchronised) frames
 * - **M4A / AAC**: iTunes `©lyr` metadata atom inside `moov → udta → meta → ilst`
 *
 * ## Supported lyric formats
 * - **LRC** (`.lrc`) — timestamped lyrics, standard LRC tags
 * - **SRT** (`.srt`) — SubRip subtitle format
 * - **Plain text** — raw lyrics, no timestamps
 *
 * ## Quick start
 * ```kotlin
 * // Bake from files
 * LyricBaker.bake(
 *     audioFile  = File("song.mp3"),
 *     lyricFile  = File("song.lrc"),
 *     outputFile = File("song_with_lyrics.mp3")
 * )
 *
 * // Bake from a LyricDocument (pre-parsed)
 * val doc = LyricParser.parse(File("song.lrc"))
 * LyricBaker.bake(audioFile = File("song.m4a"), lyrics = doc)
 *
 * // In-memory bake
 * val outputBytes = LyricBaker.bakeBytes(mp3Bytes, lyricText, LyricFormat.LRC)
 * ```
 */
object LyricBaker {

    // ── File-based API ────────────────────────────────────────────────────────

    /**
     * Bakes [lyricFile] into [audioFile] and writes the result to [outputFile].
     * If [outputFile] is null, **overwrites** [audioFile] in place.
     *
     * @param audioFile   Source MP3 or M4A file.
     * @param lyricFile   LRC, SRT, or plain-text lyric file.
     * @param outputFile  Destination file (null = overwrite source).
     * @param mp3Options  MP3-specific bake options.
     * @param m4aOptions  M4A-specific bake options.
     * @return [BakeResult.Success] with bytes written, or [BakeResult.Failure].
     */
    fun bake(
        audioFile: File,
        lyricFile: File,
        outputFile: File? = null,
        mp3Options: Mp3BakeOptions = Mp3BakeOptions(),
        m4aOptions: M4aBakeOptions = M4aBakeOptions()
    ): BakeResult = runCatching {
        val lyrics = LyricParser.parse(lyricFile)
        bake(audioFile, lyrics, outputFile, mp3Options, m4aOptions)
    }.getOrElse { BakeResult.Failure("Failed to parse lyric file: ${it.message}", it) }

    /**
     * Bakes a [LyricDocument] into [audioFile].
     */
    fun bake(
        audioFile: File,
        lyrics: LyricDocument,
        outputFile: File? = null,
        mp3Options: Mp3BakeOptions = Mp3BakeOptions(),
        m4aOptions: M4aBakeOptions = M4aBakeOptions()
    ): BakeResult = runCatching {
        val dest = outputFile ?: audioFile
        val result = bakeBytes(audioFile.readBytes(), audioFile.extension, lyrics, mp3Options, m4aOptions)
        dest.writeBytes(result)
        BakeResult.Success(result.size)
    }.getOrElse { BakeResult.Failure("Bake failed: ${it.message}", it) }

    // ── Stream-based API ──────────────────────────────────────────────────────

    /**
     * Reads audio from [audioStream], bakes [lyrics] into it, writes to [outputStream].
     * You must specify the [audioExtension] ("mp3" or "m4a") since streams carry no name.
     */
    fun bake(
        audioStream: InputStream,
        audioExtension: String,
        lyrics: LyricDocument,
        outputStream: OutputStream,
        mp3Options: Mp3BakeOptions = Mp3BakeOptions(),
        m4aOptions: M4aBakeOptions = M4aBakeOptions()
    ): BakeResult = runCatching {
        val audioBytes = audioStream.readBytes()
        val result = bakeBytes(audioBytes, audioExtension, lyrics, mp3Options, m4aOptions)
        outputStream.write(result)
        BakeResult.Success(result.size)
    }.getOrElse { BakeResult.Failure("Stream bake failed: ${it.message}", it) }

    // ── Byte-array API ────────────────────────────────────────────────────────

    /**
     * Bakes [lyricText] (parsed as [lyricFormat]) into [audioBytes].
     * Returns the modified audio as a new [ByteArray].
     */
    fun bakeBytes(
        audioBytes: ByteArray,
        audioExtension: String,
        lyricText: String,
        lyricFormat: LyricFormat,
        mp3Options: Mp3BakeOptions = Mp3BakeOptions(),
        m4aOptions: M4aBakeOptions = M4aBakeOptions()
    ): ByteArray {
        val lyrics = LyricParser.parse(lyricText, lyricFormat)
        return bakeBytes(audioBytes, audioExtension, lyrics, mp3Options, m4aOptions)
    }

    /**
     * Core byte-level bake operation.
     */
    fun bakeBytes(
        audioBytes: ByteArray,
        audioExtension: String,
        lyrics: LyricDocument,
        mp3Options: Mp3BakeOptions = Mp3BakeOptions(),
        m4aOptions: M4aBakeOptions = M4aBakeOptions()
    ): ByteArray = when (audioExtension.lowercase().trimStart('.')) {
        "mp3"       -> Id3v2Writer.bake(audioBytes, lyrics, mp3Options)
        "m4a", "aac", "mp4" -> ItunesAtomWriter.bake(audioBytes, lyrics, m4aOptions)
        else        -> error("Unsupported audio format: '$audioExtension'. Supported: mp3, m4a, aac, mp4")
    }

    // ── Lyric extraction API ──────────────────────────────────────────────────

    /**
     * Extracts embedded lyrics from an audio file, returning null if none found.
     */
    fun extractLyrics(audioFile: File): LyricDocument? =
        extractLyrics(audioFile.readBytes(), audioFile.extension)

    /**
     * Extracts embedded lyrics from [audioBytes].
     */
    fun extractLyrics(audioBytes: ByteArray, audioExtension: String): LyricDocument? =
        when (audioExtension.lowercase().trimStart('.')) {
            "mp3"            -> Id3v2Reader.readLyrics(audioBytes)
            "m4a", "aac", "mp4" -> ItunesAtomReader.readLyrics(audioBytes)
            else             -> null
        }
}

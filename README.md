# 🎵 Lyric Baker

[![](https://www.jitpack.io/v/vinnynm/lyric-baker.svg)](https://www.jitpack.io/#vinnynm/lyric-baker)

A lightweight, pure Kotlin library for seamlessly embedding synchronized lyrics into MP3 and M4A audio files. Perfect for music applications, audio processors, and multimedia tools that need to programmatically manage lyric metadata.

## Overview

**Lyric Baker** simplifies the process of reading and writing lyrics directly into audio file metadata without external dependencies. Whether you're building a music player, music production tool, or audio processing application, this library provides a straightforward API for lyric manipulation.

### Key Features

- 🎼 **Multi-Format Support**: Work with both MP3 (ID3v2) and M4A (iTunes atoms) audio formats
- 📝 **Synchronized Lyrics**: Embed time-synced lyrics with millisecond precision
- ⚙️ **Zero Dependencies**: Pure Kotlin implementation using only the standard library
- 🔧 **Easy API**: Simple, intuitive interface for reading and writing lyrics
- 🚀 **Production Ready**: Thoroughly tested and optimized for reliability
- 📦 **Android Compatible**: Works seamlessly in Android applications (API 21+)

## Installation

### Using JitPack

Add JitPack to your root `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://www.jitpack.io") }
}
```

Add the dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.vinnynm:lyric-baker:direwolf")
}
```

## Quick Start

### Basic Usage

```kotlin
import com.enigma.devlyric.core.LyricBaker

// Create an instance of LyricBaker
val lyricBaker = LyricBaker()

// Write lyrics to an MP3 file
val mp3File = File("path/to/song.mp3")
val lyrics = """
    [00:05.00]First line of lyrics
    [00:10.50]Second line of lyrics
    [00:15.75]Third line of lyrics
""".trimIndent()

lyricBaker.writeLyricsToMp3(mp3File, lyrics)

// Read lyrics from an MP3 file
val readLyrics = lyricBaker.readLyricsFromMp3(mp3File)
println(readLyrics)

// Write lyrics to an M4A file
val m4aFile = File("path/to/song.m4a")
lyricBaker.writeLyricsToM4a(m4aFile, lyrics)

// Read lyrics from an M4A file
val m4aLyrics = lyricBaker.readLyricsFromM4a(m4aFile)
println(m4aLyrics)
```

## Supported Lyric Formats

### Synchronized Lyrics (LRC Format)

The library primarily supports the popular LRC (Lyric) format with timestamp information:

```
[00:12.00]Line 1
[00:17.20]Line 2
[00:21.10]Line 3
[01:04.00][01:15.50]Line 4 (multiple timestamps)
```

Each line starts with a timestamp in `[MM:SS.CS]` format (minutes:seconds.centiseconds), followed by the lyric text.

### Unsynced Lyrics

Plain text lyrics without timestamps are also supported for


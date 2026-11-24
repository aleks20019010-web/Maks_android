package com.maks.music.media

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

data class LyricLine(
    val timeMs: Long, // -1L для обычного текста без таймингов
    val text: String,
)

object LyricsExtractor {

    private val LRC_TAG_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun extract(context: Context, uri: Uri): List<LyricLine> {
        val raw = runCatching { readRawLyrics(context, uri) }.getOrNull() ?: return emptyList()
        return parseLyrics(raw)
    }

    fun parseLyrics(raw: String): List<LyricLine> {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val synced = mutableListOf<LyricLine>()
        val plain = mutableListOf<LyricLine>()

        for (line in lines) {
            val matches = LRC_TAG_REGEX.findAll(line).toList()
            if (matches.isNotEmpty()) {
                val text = LRC_TAG_REGEX.replace(line, "").trim()
                if (text.isEmpty()) continue
                for (m in matches) {
                    val min = m.groupValues[1].toLongOrNull() ?: 0L
                    val sec = m.groupValues[2].toLongOrNull() ?: 0L
                    val fracStr = m.groupValues[3]
                    val ms = when (fracStr.length) {
                        0 -> 0L
                        1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                        2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                        else -> (fracStr.take(3).toLongOrNull() ?: 0L)
                    }
                    synced += LyricLine(timeMs = (min * 60_000L) + (sec * 1_000L) + ms, text = text)
                }
            } else {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("[")) {
                    plain += LyricLine(timeMs = -1L, text = trimmed)
                }
            }
        }

        return if (synced.isNotEmpty()) {
            synced.sortedBy { it.timeMs }
        } else {
            plain
        }
    }

    private fun readRawLyrics(context: Context, uri: Uri): String? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            // Читаем заголовок (до 512 КБ достаточно для ID3v2 / FLAC Vorbis / M4A metadata)
            val buffer = ByteArray(16 * 1024)
            val out = ByteArrayOutputStream()
            var total = 0
            while (total < 512 * 1024) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
                total += read
            }
            out.toByteArray()
        } ?: return null

        if (bytes.size < 16) return null

        // 1. MP3 ID3v2 (USLT)
        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            parseId3v2Uslt(bytes)?.let { return it }
        }

        // 2. FLAC Vorbis Comment (LYRICS= / UNSYNCEDLYRICS=)
        if (bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte()
        ) {
            parseFlacLyrics(bytes)?.let { return it }
        }

        // 3. M4A / MP4 (©lyr) или OGG Vorbis Comment
        parseMp4Lyrics(bytes)?.let { return it }
        parseVorbisScan(bytes)?.let { return it }

        return null
    }

    private fun parseId3v2Uslt(data: ByteArray): String? {
        val version = data[3].toInt() and 0xFF
        val tagSize = syncSafeInt(data, 6)
        val limit = minOf(data.size, 10 + tagSize)
        var pos = 10

        while (pos + 10 <= limit) {
            val frameId = String(data, pos, 4, Charsets.ISO_8859_1)
            if (frameId[0] == '\u0000') break
            val frameSize = if (version >= 4) syncSafeInt(data, pos + 4) else bigEndianInt(data, pos + 4)
            if (frameSize <= 0 || pos + 10 + frameSize > data.size) break

            if (frameId == "USLT") {
                val contentStart = pos + 10
                val encoding = data[contentStart].toInt() and 0xFF
                // Пропускаем encoding (1 байт) + language (3 байта)
                var textStart = contentStart + 4
                val frameEnd = contentStart + frameSize
                if (textStart >= frameEnd) return null

                val charset: Charset = when (encoding) {
                    1 -> Charsets.UTF_16
                    2 -> Charsets.UTF_16BE
                    3 -> Charsets.UTF_8
                    else -> Charsets.ISO_8859_1
                }

                // Пропускаем content descriptor до нулевого терминатора
                if (encoding == 1 || encoding == 2) {
                    while (textStart + 1 < frameEnd) {
                        if (data[textStart] == 0.toByte() && data[textStart + 1] == 0.toByte()) {
                            textStart += 2
                            break
                        }
                        textStart += 2
                    }
                } else {
                    while (textStart < frameEnd) {
                        if (data[textStart] == 0.toByte()) {
                            textStart += 1
                            break
                        }
                        textStart += 1
                    }
                }

                if (textStart < frameEnd) {
                    val decoded = String(data, textStart, frameEnd - textStart, charset)
                        .trim('\u0000', '\uFEFF', ' ', '\n', '\r')
                    if (decoded.isNotBlank()) return decoded
                }
            }
            pos += 10 + frameSize
        }
        return null
    }

    private fun parseFlacLyrics(data: ByteArray): String? {
        var pos = 4
        while (pos + 4 <= data.size) {
            val header = data[pos].toInt() and 0xFF
            val isLast = (header and 0x80) != 0
            val blockType = header and 0x7F
            val length = ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
            pos += 4
            if (pos + length > data.size) break

            if (blockType == 4) { // VORBIS_COMMENT
                parseVorbisCommentBlock(data, pos, pos + length)?.let { return it }
            }
            pos += length
            if (isLast) break
        }
        return null
    }

    private fun parseVorbisCommentBlock(data: ByteArray, start: Int, end: Int): String? {
        var p = start
        if (p + 4 > end) return null
        val vendorLen = littleEndianInt(data, p)
        p += 4 + vendorLen
        if (p + 4 > end) return null
        val count = littleEndianInt(data, p)
        p += 4
        for (i in 0 until count) {
            if (p + 4 > end) break
            val len = littleEndianInt(data, p)
            p += 4
            if (len <= 0 || p + len > end) break
            val entry = String(data, p, len, Charsets.UTF_8)
            p += len
            val eqIdx = entry.indexOf('=')
            if (eqIdx > 0) {
                val key = entry.substring(0, eqIdx).uppercase()
                if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "SYNCEDLYRICS") {
                    val value = entry.substring(eqIdx + 1).trim()
                    if (value.isNotBlank()) return value
                }
            }
        }
        return null
    }

    private fun parseVorbisScan(data: ByteArray): String? {
        val text = String(data, Charsets.UTF_8)
        val markers = listOf("LYRICS=", "UNSYNCEDLYRICS=", "lyrics=")
        for (marker in markers) {
            val idx = text.indexOf(marker)
            if (idx >= 0) {
                val start = idx + marker.length
                val end = text.indexOf('\u0000', start).let { if (it > start) it else minOf(text.length, start + 8192) }
                val candidate = text.substring(start, end).trim()
                if (candidate.length > 10) return candidate
            }
        }
        return null
    }

    private fun parseMp4Lyrics(data: ByteArray): String? {
        // Ищем атом ©lyr (0xA9, 'l', 'y', 'r')
        for (i in 0 until data.size - 24) {
            if (data[i] == 0xA9.toByte() &&
                data[i + 1] == 'l'.code.toByte() &&
                data[i + 2] == 'y'.code.toByte() &&
                data[i + 3] == 'r'.code.toByte()
            ) {
                // Внутри ©lyr лежит атом data
                val dataAtomPos = i + 4
                if (dataAtomPos + 16 < data.size &&
                    data[dataAtomPos + 4] == 'd'.code.toByte() &&
                    data[dataAtomPos + 5] == 'a'.code.toByte() &&
                    data[dataAtomPos + 6] == 't'.code.toByte() &&
                    data[dataAtomPos + 7] == 'a'.code.toByte()
                ) {
                    val atomSize = bigEndianInt(data, dataAtomPos)
                    val textStart = dataAtomPos + 16
                    val textLen = atomSize - 16
                    if (textLen > 0 && textStart + textLen <= data.size) {
                        val lyrics = String(data, textStart, textLen, Charsets.UTF_8).trim()
                        if (lyrics.isNotBlank()) return lyrics
                    }
                }
            }
        }
        return null
    }

    private fun syncSafeInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0x7F) shl 21) or
            ((b[offset + 1].toInt() and 0x7F) shl 14) or
            ((b[offset + 2].toInt() and 0x7F) shl 7) or
            (b[offset + 3].toInt() and 0x7F)

    private fun bigEndianInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)

    private fun littleEndianInt(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)
}
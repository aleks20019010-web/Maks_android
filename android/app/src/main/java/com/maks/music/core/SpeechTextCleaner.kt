package com.maks.music.core

/**
 * Очищает разметку Yandex SpeechKit из JSON-файлов папки responses:
 *   - ударения вида «Включ+аю» → «Включаю»
 *   - паузы sil<[150]> → естественные паузы для встроенного Android TTS и чистый текст для чата.
 */
object SpeechTextCleaner {

    private val SILENCE_REGEX = Regex("""sil<\[\s*(\d+)\s*\]>""")
    private val STRESS_PLUS_REGEX = Regex("""\+([аеёиоуыэюяАЕЁИОУЫЭЮЯ])""")
    private val MULTI_SPACES_REGEX = Regex("""\s+""")
    private val SPACE_BEFORE_PUNCT_REGEX = Regex("""\s+([,.!?;:])""")
    private val DOUBLE_PUNCT_REGEX = Regex("""([,.!?])\s*[,.]+""")

    data class SpeechChunk(
        val text: String,
        val pauseAfterMs: Long = 0L,
    )

    /** Чистый человекочитаемый текст для отображения в чате. */
    fun toDisplayText(raw: String): String {
        if (raw.isBlank()) return ""
        var text = STRESS_PLUS_REGEX.replace(raw, "$1")
        text = SILENCE_REGEX.replace(text) { match ->
            val ms = match.groupValues[1].toIntOrNull() ?: 0
            if (ms >= 150) ", " else " "
        }
        text = text.trim().trimStart(',', ';', ':').trim()
        text = SPACE_BEFORE_PUNCT_REGEX.replace(text, "$1")
        text = DOUBLE_PUNCT_REGEX.replace(text, "$1")
        text = MULTI_SPACES_REGEX.replace(text, " ").trim()
        return text
    }

    /**
     * Разбивает фразу на сегменты + длительности пауз (для Android TextToSpeech.playSilentUtterance),
     * сохраняя фирменные интонационные паузы Макса без Yandex SpeechKit.
     */
    fun toSpeechChunks(raw: String): List<SpeechChunk> {
        if (raw.isBlank()) return emptyList()
        val noStress = STRESS_PLUS_REGEX.replace(raw, "$1")
        val chunks = mutableListOf<SpeechChunk>()

        var lastIdx = 0
        for (match in SILENCE_REGEX.findAll(noStress)) {
            val part = cleanPart(noStress.substring(lastIdx, match.range.first))
            val pauseMs = (match.groupValues[1].toLongOrNull() ?: 100L).coerceIn(40L, 600L)
            if (part.isNotEmpty()) {
                chunks += SpeechChunk(text = part, pauseAfterMs = pauseMs)
            } else if (chunks.isNotEmpty()) {
                val prev = chunks.removeAt(chunks.lastIndex)
                chunks += prev.copy(pauseAfterMs = (prev.pauseAfterMs + pauseMs).coerceAtMost(700L))
            }
            lastIdx = match.range.last + 1
        }

        if (lastIdx < noStress.length) {
            val tail = cleanPart(noStress.substring(lastIdx))
            if (tail.isNotEmpty()) {
                chunks += SpeechChunk(text = tail, pauseAfterMs = 0L)
            }
        }
        return chunks
    }

    private fun cleanPart(part: String): String {
        return MULTI_SPACES_REGEX
            .replace(SPACE_BEFORE_PUNCT_REGEX.replace(part, "$1"), " ")
            .trim()
    }
}

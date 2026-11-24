package com.maks.music.core.nlu

import com.maks.music.core.AssetProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Порт парсера громкости из brain/skills/controls.py:
 * «громкость 50», «громкость на пятьдесят», «звук 30», «громкость сто».
 */
class VolumeParser(assets: AssetProvider) {

    private val numberWords: Map<String, Int>

    init {
        val loaded = runCatching {
            val raw = assets.readText("config/music_words.json") ?: return@runCatching emptyMap()
            val obj = Json.parseToJsonElement(raw).jsonObject["number_words"]?.jsonObject
                ?: return@runCatching emptyMap()
            obj.mapNotNull { (k, v) ->
                val num = v.jsonPrimitive.intOrNull ?: return@mapNotNull null
                k.lowercase().trim() to num
            }.toMap()
        }.getOrDefault(emptyMap())

        numberWords = loaded + EXTRA_NUMBER_WORDS
    }

    /** Извлечь процент громкости 0..100 или null, если число не найдено. */
    fun parseVolume(text: String): Int? {
        val lower = text.lowercase()
        if (!VOLUME_HINT_REGEX.containsMatchIn(lower)) return null

        // 1. Цифры
        DIGIT_REGEX.find(lower)?.let { m ->
            return m.value.toIntOrNull()?.coerceIn(0, 100)
        }

        // 2. Числа словами (включая составные: «тридцать пять» → 35)
        val tokens = lower.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        var total = 0
        var matched = false
        for (token in tokens) {
            val v = numberWords[token]
            if (v != null) {
                total += v
                matched = true
            }
        }
        return if (matched) total.coerceIn(0, 100) else null
    }

    companion object {
        private val VOLUME_HINT_REGEX = Regex("""(громкост|звук|процент)""")
        private val DIGIT_REGEX = Regex("""\b(\d{1,3})\b""")
        private val EXTRA_NUMBER_WORDS = mapOf(
            "один" to 1, "два" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
            "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9,
            "пятнадцать" to 15, "двадцать" to 20, "тридцать" to 30,
            "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60,
            "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90,
            "сто" to 100, "ноль" to 0, "максимум" to 100, "минимум" to 10
        )
    }
}

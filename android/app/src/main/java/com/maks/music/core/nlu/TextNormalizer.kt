package com.maks.music.core.nlu

import com.maks.music.core.AssetProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Точный порт brain/nlu/normalizer.py.
 * Чистит STOP_PREFIXES («макс», «слушаю»...), пунктуацию, общие филлеры и лишние пробелы.
 */
class TextNormalizer(assets: AssetProvider) {

    private val stopPrefixes: List<String>

    init {
        val loaded = runCatching {
            val raw = assets.readText("config/music_words.json") ?: return@runCatching emptyList()
            val obj = Json.parseToJsonElement(raw).jsonObject
            obj["stop_prefixes"]?.jsonArray
                ?.map { it.jsonPrimitive.content.lowercase().trim() }
                ?.filter { it.isNotEmpty() }
                ?.sortedByDescending { it.length }
                .orEmpty()
        }.getOrDefault(emptyList())
        stopPrefixes = if (loaded.isNotEmpty()) loaded else listOf("макс", "макса", "максу")
    }

    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        var t = text.lowercase().trim()
        t = stripStopPrefixes(t)
        t = PUNCT_REGEX.replace(t, " ")
        t = FILLERS_REGEX.replace(t, " ")
        return SPACES_REGEX.replace(t, " ").trim()
    }

    private fun stripStopPrefixes(input: String): String {
        var q = input
        var changed = true
        while (changed) {
            changed = false
            for (prefix in stopPrefixes) {
                if (q == prefix || (q.startsWith(prefix) && q.length > prefix.length && !q[prefix.length].isLetterOrDigit())) {
                    q = q.removePrefix(prefix).trimStart(' ', ',', '.', '!', '?', '-', ':')
                    changed = true
                    break
                }
            }
        }
        return q
    }

    companion object {
        private val PUNCT_REGEX = Regex("""[^\p{L}\p{N}\s]""")
        private val SPACES_REGEX = Regex("""\s+""")
        private val FILLERS_REGEX = Regex(
            """(?<![а-яёa-z0-9])(пожалуйста|будь добр|будь добра|макс|эй|слушай|ну|вот|как бы|типа|давай)(?![а-яёa-z0-9])""",
            RegexOption.IGNORE_CASE
        )
    }
}

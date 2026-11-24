package com.maks.music.core.nlu

import com.maks.music.core.AssetProvider
import com.maks.music.core.Phonetic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MusicIntentResolver(
    assets: AssetProvider,
    private val volumeParser: VolumeParser,
) {
    private val exactMap: Map<String, MusicIntent>
    private val allRoutes: List<Pair<String, MusicIntent>>
    private val playTriggers: Set<String>

    init {
        val exact = LinkedHashMap<String, MusicIntent>()
        val routes = mutableListOf<Pair<String, MusicIntent>>()

        runCatching {
            val raw = assets.readText("config/intent_routes_music.json") ?: return@runCatching
            val obj = Json.parseToJsonElement(raw).jsonObject
            for ((intentName, el) in obj) {
                val intent = MusicIntent.fromName(intentName)
                if (intent == MusicIntent.UNKNOWN) continue
                for (phraseEl in el.jsonArray) {
                    val p = phraseEl.jsonPrimitive.content.lowercase().trim()
                    if (p.isNotEmpty()) {
                        exact.putIfAbsent(p, intent)
                        routes += p to intent
                    }
                }
            }
        }
        exactMap = exact
        allRoutes = routes

        val triggers = runCatching {
            val raw = assets.readText("config/music_words.json") ?: return@runCatching emptySet()
            Json.parseToJsonElement(raw).jsonObject["play_triggers"]?.jsonArray
                ?.map { it.jsonPrimitive.content.lowercase().trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())

        playTriggers = triggers + setOf("навали", "зажги", "послушаем", "послушать", "хочу послушать")
    }

    fun resolve(normalizedText: String): Pair<MusicIntent, Double> {
        val text = normalizedText.lowercase().trim()
        if (text.isEmpty()) return MusicIntent.UNKNOWN to 0.0

        // 1. Локальные лайки («включи мою музыку», «моя музыка», «мои лайки», «любимые») — выше exactMap
        if (LOCAL_LIKED_PHRASES.any { text.contains(it) }) {
            return MusicIntent.MUSIC_LOCAL_LIKED to 0.98
        }

        // 2. Явные лайки / дизлайки текущего трека
        if (text.contains("не нравится") || text.contains("дизлайк") || text.contains("убери трек") || text.contains("больше не ставь")) {
            return MusicIntent.MUSIC_DISLIKE to 0.96
        }
        if (text.contains("мне нравится") || text == "лайк" || text.contains("лайкни") || text.contains("в любимые") || text.contains("в избранное")) {
            return MusicIntent.MUSIC_LIKE to 0.96
        }

        // 3. Точное совпадение по словарю
        exactMap[text]?.let { return it to 1.0 }

        // 4. Явная установка громкости
        if (volumeParser.parseVolume(text) != null) {
            return MusicIntent.VOLUME_SET to 0.96
        }

        // 5. Волна
        if (text.contains("волну") || text.contains("волна")) {
            return MusicIntent.MUSIC_WAVE to 0.95
        }

        // 6. Запуск по триггеру
        val firstWord = text.substringBefore(' ')
        if (firstWord in playTriggers || text.startsWith("хочу послушать ") || text.startsWith("давай послушаем ")) {
            return MusicIntent.MUSIC_PLAY to 0.92
        }

        // 7. Fuzzy-поиск по словарю фраз
        var bestIntent = MusicIntent.UNKNOWN
        var bestScore = 0.0
        for ((phrase, intent) in allRoutes) {
            val score = Phonetic.ratio(text, phrase)
            if (score > bestScore) {
                bestScore = score
                bestIntent = intent
            }
        }
        if (bestScore >= FUZZY_THRESHOLD) {
            return bestIntent to bestScore
        }

        return MusicIntent.UNKNOWN to bestScore
    }

    companion object {
        private const val FUZZY_THRESHOLD = 0.80
        private val LOCAL_LIKED_PHRASES = listOf(
            "мои лайки", "моих лайков",
            "мою музыку", "моя музыка", "моей музыки",
            "любимую музыку", "любимая музыка",
            "любимые треки", "любимые песни", "любимое", "любимые", "избранное"
        )
    }
}
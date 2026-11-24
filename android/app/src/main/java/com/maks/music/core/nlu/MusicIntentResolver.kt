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

        playTriggers = triggers + setOf(
            "\u043d\u0430\u0432\u0430\u043b\u0438",
            "\u0437\u0430\u0436\u0433\u0438",
            "\u043f\u043e\u0441\u043b\u0443\u0448\u0430\u0435\u043c",
            "\u043f\u043e\u0441\u043b\u0443\u0448\u0430\u0442\u044c",
            "\u0445\u043e\u0447\u0443 \u043f\u043e\u0441\u043b\u0443\u0448\u0430\u0442\u044c"
        )
    }

    fun resolve(normalizedText: String): Pair<MusicIntent, Double> {
        val text = normalizedText.lowercase().trim()
        if (text.isEmpty()) return MusicIntent.UNKNOWN to 0.0

        if (LOCAL_LIKED_PHRASES.any { text.contains(it) }) {
            return MusicIntent.MUSIC_LOCAL_LIKED to 0.98
        }

        if (DISLIKE_PHRASES.any { text.contains(it) }) {
            return MusicIntent.MUSIC_DISLIKE to 0.96
        }

        if (text == "\u043b\u0430\u0439\u043a" || LIKE_PHRASES.any { text.contains(it) }) {
            return MusicIntent.MUSIC_LIKE to 0.96
        }

        exactMap[text]?.let { return it to 1.0 }

        if (volumeParser.parseVolume(text) != null) {
            return MusicIntent.VOLUME_SET to 0.96
        }

        if (text.contains("\u0432\u043e\u043b\u043d\u0443") || text.contains("\u0432\u043e\u043b\u043d\u0430")) {
            return MusicIntent.MUSIC_WAVE to 0.95
        }

        val firstWord = text.substringBefore(' ')
        if (firstWord in playTriggers ||
            text.startsWith("\u0445\u043e\u0447\u0443 \u043f\u043e\u0441\u043b\u0443\u0448\u0430\u0442\u044c ") ||
            text.startsWith("\u0434\u0430\u0432\u0430\u0439 \u043f\u043e\u0441\u043b\u0443\u0448\u0430\u0435\u043c ")
        ) {
            return MusicIntent.MUSIC_PLAY to 0.92
        }

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
            "\u043c\u043e\u0438 \u043b\u0430\u0439\u043a\u0438",
            "\u043c\u043e\u0438\u0445 \u043b\u0430\u0439\u043a\u043e\u0432",
            "\u043c\u043e\u044e \u043c\u0443\u0437\u044b\u043a\u0443",
            "\u043c\u043e\u044f \u043c\u0443\u0437\u044b\u043a\u0430",
            "\u043c\u043e\u0435\u0439 \u043c\u0443\u0437\u044b\u043a\u0438",
            "\u043b\u044e\u0431\u0438\u043c\u0443\u044e \u043c\u0443\u0437\u044b\u043a\u0443",
            "\u043b\u044e\u0431\u0438\u043c\u0430\u044f \u043c\u0443\u0437\u044b\u043a\u0430",
            "\u043b\u044e\u0431\u0438\u043c\u044b\u0435 \u0442\u0440\u0435\u043a\u0438",
            "\u043b\u044e\u0431\u0438\u043c\u044b\u0435 \u043f\u0435\u0441\u043d\u0438",
            "\u043b\u044e\u0431\u0438\u043c\u043e\u0435",
            "\u043b\u044e\u0431\u0438\u043c\u044b\u0435",
            "\u0438\u0437\u0431\u0440\u0430\u043d\u043d\u043e\u0435"
        )

        private val DISLIKE_PHRASES = listOf(
            "\u043d\u0435 \u043d\u0440\u0430\u0432\u0438\u0442\u0441\u044f",
            "\u0434\u0438\u0437\u043b\u0430\u0439\u043a",
            "\u0443\u0431\u0435\u0440\u0438 \u0442\u0440\u0435\u043a",
            "\u0431\u043e\u043b\u044c\u0448\u0435 \u043d\u0435 \u0441\u0442\u0430\u0432\u044c"
        )

        private val LIKE_PHRASES = listOf(
            "\u043c\u043d\u0435 \u043d\u0440\u0430\u0432\u0438\u0442\u0441\u044f",
            "\u043b\u0430\u0439\u043a\u043d\u0438",
            "\u043f\u043e\u0441\u0442\u0430\u0432\u044c \u043b\u0430\u0439\u043a",
            "\u0432 \u043b\u044e\u0431\u0438\u043c\u044b\u0435",
            "\u0432 \u0438\u0437\u0431\u0440\u0430\u043d\u043d\u043e\u0435"
        )
    }
}
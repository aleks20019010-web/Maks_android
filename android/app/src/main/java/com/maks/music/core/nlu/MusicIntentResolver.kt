package com.maks.music.core.nlu

import com.maks.music.core.AssetProvider
import com.maks.music.core.Phonetic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Оффлайн-классификатор музыкальных интентов без тяжелых нейросетей (~0.3 мс):
 *   1. Точное совпадение по config/intent_routes_music.json
 *   2. Явные правила: VOLUME_SET, MUSIC_LOCAL_LIKED, MUSIC_WAVE, play_triggers → MUSIC_PLAY
 *   3. Fuzzy-поиск (Levenshtein ratio) по всем музыкальным фразам (порог 0.80)
 */
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

        // 1. Точное совпадение (мгновенно)
        exactMap[text]?.let { return it to 1.0 }

        // 2. Явная установка громкости («громкость 50», «звук на тридцать»)
        if (volumeParser.parseVolume(text) != null) {
            return MusicIntent.VOLUME_SET to 0.96
        }

        // 3. Явные лайки / дизлайки / любимые
        if (LOCAL_LIKED_PHRASES.any { text.contains(it) }) {
            return MusicIntent.MUSIC_LOCAL_LIKED to 0.95
        }
        if (text.contains("не нравится") || text.contains("дизлайк") || text.contains("убери трек") || text.contains("больше не ставь")) {
            return MusicIntent.MUSIC_DISLIKE to 0.95
        }
        if (text.contains("мне нравится") || text == "лайк" || text.contains("лайкни") || text.contains("в любимые") || text.contains("в избранное")) {
            return MusicIntent.MUSIC_LIKE to 0.95
        }

        // 4. Волна («включи мою волну», «русская волна»)
        if (text.contains("волну") || text.contains("волна")) {
            return MusicIntent.MUSIC_WAVE to 0.95
        }

        // 5. Начинается с play_trigger («включи Арию», «поставь русский рок», «вруби что-нибудь бодрое»)
        val firstWord = text.substringBefore(' ')
        if (firstWord in playTriggers || text.startsWith("хочу послушать ") || text.startsWith("давай послушаем ")) {
            return MusicIntent.MUSIC_PLAY to 0.92
        }

        // 6. Fuzzy-поиск по словарю фраз (аналог _fuzzy_fallback в brain/nlu/intent.py)
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
            "мои лайки", "мою музыку", "моя музыка", "любимые треки", "любимое", "любимые", "избранное"
        )
    }
}

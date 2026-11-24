package com.maks.music.core.nlu

import com.maks.music.core.AssetProvider
import com.maks.music.core.Phonetic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Представление локального каталога для EntityExtractor (без зависимостей от Android).
 */
interface LocalCatalogLookup {
    /** lower/phon key -> каноническое имя артиста */
    fun artistIndex(): Map<String, String>
    /** lower/phon key -> "Artist Title" */
    fun trackIndex(): Map<String, String>
}

/**
 * Порт brain/nlu/entity/music.py под 100% локальную медиатеку:
 *   0. local_liked («мою музыку» / «мои лайки» / «любимые»)
 *   1. mood_aliases (из config/mood_aliases.json)
 *   2. genre_aliases (из config/genre_aliases.json — приоритетнее одноимённого трека!)
 *   3. local_index (точные + фонетические phon() по артистам и трекам)
 *   4. fuzzy по mood + local_artists + genres (порог 0.80 для артистов, 0.72 для жанров/настроений)
 *   5. UNRESOLVED → DecisionEngine превратит в play_search по локальной библиотеке
 */
class MusicEntityExtractor(
    assets: AssetProvider,
    private val catalogProvider: () -> LocalCatalogLookup,
) {
    private val playTriggers: Set<String>
    private val noiseWords: Set<String>
    private val artistHints: Set<String>
    private val trackHints: Set<String>
    private val moodAliases: Map<String, String>
    private val genreAliases: Map<String, String>
    private val artistAliases: Map<String, String>
    private val artistAliasesPhon: Map<String, String>

    init {
        val wordsObj = runCatching {
            assets.readText("config/music_words.json")?.let { Json.parseToJsonElement(it).jsonObject }
        }.getOrNull()

        fun loadSet(key: String): Set<String> =
            wordsObj?.get(key)?.jsonArray
                ?.map { it.jsonPrimitive.content.lowercase().trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()

        playTriggers = loadSet("play_triggers") + setOf("навали", "зажги")
        noiseWords = loadSet("noise_words") + setOf(
            "хочу", "послушать", "послушаем",
            "что-то", "чего-то", "какую-нибудь", "какой-нибудь", "какое-нибудь", "какие-нибудь"
        )
        artistHints = loadSet("artist_hints")
        trackHints = loadSet("track_hints")

        moodAliases = loadStringMap(assets, "config/mood_aliases.json")
        genreAliases = loadStringMap(assets, "config/genre_aliases.json")
        artistAliases = loadArtistAliases(assets, "config/aliases.json")
        artistAliasesPhon = buildMap {
            for ((k, v) in artistAliases) {
                val p = Phonetic.phon(k)
                if (p.length >= 2) putIfAbsent(p, v)
            }
        }
    }

    fun extract(normalizedText: String, intent: MusicIntent): Entity {
        val text = normalizedText.lowercase().trim()
        if (text.isEmpty()) return Entity(type = "music", state = EntityState.ABSENT)

        // 0. Локальные лайки
        if (intent == MusicIntent.MUSIC_LOCAL_LIKED || LOCAL_LIKED_KEYS.any { text.contains(it) }) {
            return Entity(
                type = "local_liked",
                value = "liked",
                state = EntityState.FOUND,
                matchConfidence = 1.0,
                source = "local_liked",
            )
        }

        // Извлекаем языковой модификатор (русская / зарубежная)
        val tokens = text.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        var detectedLang: String? = null
        for (tok in tokens) {
            LANG_MODIFIERS[tok]?.let { detectedLang = it }
        }

        // Если это явная волна — достаточно языка
        if (intent == MusicIntent.MUSIC_WAVE) {
            return if (detectedLang != null) {
                Entity(
                    type = "wave_lang",
                    value = detectedLang,
                    state = EntityState.FOUND,
                    matchConfidence = 1.0,
                    source = "wave_lang",
                    language = detectedLang,
                )
            } else {
                Entity(type = "wave", state = EntityState.ABSENT)
            }
        }

        // Очищаем триггеры, шум и подсказки («включи группу арию» → «арию»)
        val candidate = extractCandidate(tokens)
        if (candidate.isNullOrBlank()) {
            // Если остался только язык («включи русскую музыку» / «включи что-то русское») → wave_lang
            return if (detectedLang != null) {
                Entity(
                    type = "wave_lang",
                    value = detectedLang,
                    state = EntityState.FOUND,
                    matchConfidence = 1.0,
                    source = "wave_lang",
                    language = detectedLang,
                )
            } else {
                Entity(type = "music", state = EntityState.ABSENT)
            }
        }

        return matchCandidate(candidate, detectedLang)
    }

    private fun extractCandidate(tokens: List<String>): String? {
        val filtered = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            if (i + 1 < tokens.size && (tokens[i] == "что" || tokens[i] == "чего") && tokens[i + 1] == "нибудь") {
                i += 2
                continue
            }
            val w = tokens[i]
            if (w in playTriggers || w in noiseWords || w in artistHints || w in trackHints) {
                i++
                continue
            }
            filtered += w
            i++
        }

        val joined = filtered.joinToString(" ").trim()
        if (joined.isEmpty() || joined in GENERIC_MUSIC_WORDS) return null

        // Если фраза = только языковой модификатор + «музыку» («русскую музыку») → ABSENT (обработается как wave_lang)
        val withoutGenericAndLang = filtered.filter { it !in GENERIC_MUSIC_WORDS && it !in LANG_MODIFIERS }
        if (withoutGenericAndLang.isEmpty()) return null

        // Но для «русский рок» сохраняем «русский» (он есть в genre_aliases!)
        val cleaned = filtered.filter { it !in GENERIC_MUSIC_WORDS }.joinToString(" ").trim()
        return cleaned.ifEmpty { null }
    }

    private fun matchCandidate(candidate: String, language: String?): Entity {
        val candLow = candidate.lowercase().trim()
        val candPhon = Phonetic.phon(candLow)

        // 1. Точное совпадение по настроению (mood_aliases.json)
        moodAliases[candLow]?.let { moodName ->
            return Entity(
                type = "mood",
                value = moodName,
                state = EntityState.FOUND,
                matchConfidence = 1.0,
                source = "mood_exact",
                language = language,
            )
        }

        // 2. Точное совпадение по жанру (genre_aliases.json — выше локальных названий треков!)
        genreAliases[candLow]?.let { genreId ->
            return Entity(
                type = "genre",
                value = genreId,
                state = EntityState.FOUND,
                matchConfidence = 1.0,
                source = "genre_exact",
                language = language,
            )
        }

        val catalog = catalogProvider()
        val artists = catalog.artistIndex()
        val tracks = catalog.trackIndex()

        // 3. Точное + фонетическое совпадение в локальной библиотеке (артисты)
        artists[candLow]?.let { artist ->
            return Entity("artist", artist, EntityState.FOUND, 1.0, source = "local_artist_exact", language = language)
        }
        artists[candPhon]?.let { artist ->
            return Entity("artist", artist, EntityState.FOUND, 0.96, source = "local_artist_phon", language = language)
        }

        // 3b. Срез частых русских падежных окончаний для локальных артистов
        for (stem in russianCaseVariants(candLow)) {
            artists[stem]?.let { artist ->
                return Entity("artist", artist, EntityState.FOUND, 0.94, source = "local_artist_case", language = language)
            }
            artists[Phonetic.phon(stem)]?.let { artist ->
                return Entity("artist", artist, EntityState.FOUND, 0.92, source = "local_artist_case_phon", language = language)
            }
        }

        // 3c. Вшитый словарь алиасов артистов
        artistAliases[candLow]?.let { canonical ->
            val resolved = resolveAgainstLocalArtists(canonical, artists)
            return Entity("artist", resolved, EntityState.FOUND, 0.98, source = "artist_alias_exact", language = language)
        }
        artistAliasesPhon[candPhon]?.let { canonical ->
            val resolved = resolveAgainstLocalArtists(canonical, artists)
            return Entity("artist", resolved, EntityState.FOUND, 0.95, source = "artist_alias_phon", language = language)
        }
        for (stem in russianCaseVariants(candLow)) {
            artistAliases[stem]?.let { canonical ->
                val resolved = resolveAgainstLocalArtists(canonical, artists)
                return Entity("artist", resolved, EntityState.FOUND, 0.93, source = "artist_alias_case", language = language)
            }
            artistAliasesPhon[Phonetic.phon(stem)]?.let { canonical ->
                val resolved = resolveAgainstLocalArtists(canonical, artists)
                return Entity("artist", resolved, EntityState.FOUND, 0.91, source = "artist_alias_case_phon", language = language)
            }
        }

        // 4. Точное + фонетическое совпадение по названиям локальных треков
        tracks[candLow]?.let { fullTrack ->
            return Entity("track", fullTrack, EntityState.FOUND, 1.0, source = "local_track_exact", language = language)
        }
        tracks[candPhon]?.let { fullTrack ->
            return Entity("track", fullTrack, EntityState.FOUND, 0.95, source = "local_track_phon", language = language)
        }

        // 5. Fuzzy-поиск среди настроений, жанров, локальных артистов и словаря алиасов
        val candidates = mutableListOf<EntityCandidate>()

        bestFuzzy(candLow, moodAliases, MIN_MATCH_CONFIDENCE)?.let { (v, s) ->
            candidates += EntityCandidate("mood", v, s, "mood_fuzzy")
        }
        bestFuzzy(candLow, genreAliases, MIN_MATCH_CONFIDENCE)?.let { (v, s) ->
            candidates += EntityCandidate("genre", v, s, "genre_fuzzy")
        }
        bestFuzzy(candLow, artists, ARTIST_FUZZY_THRESHOLD)?.let { (v, s) ->
            candidates += EntityCandidate("artist", v, s, "artist_fuzzy")
        }
        bestFuzzy(candLow, artistAliases, ARTIST_ALIAS_FUZZY_THRESHOLD)?.let { (v, s) ->
            val resolved = resolveAgainstLocalArtists(v, artists)
            candidates += EntityCandidate("artist", resolved, s, "artist_alias_fuzzy")
        }

        if (candidates.isNotEmpty()) {
            val sorted = candidates.sortedByDescending { it.score }
            val top = sorted.first()
            if (sorted.size >= 2 && (top.score - sorted[1].score) < AMBIGUITY_GAP && top.value != sorted[1].value) {
                return Entity(
                    type = top.type,
                    value = top.value,
                    state = EntityState.AMBIGUOUS,
                    matchConfidence = top.score,
                    candidates = sorted.take(3),
                    source = top.source,
                    language = language,
                )
            }
            return Entity(
                type = top.type,
                value = top.value,
                state = EntityState.FOUND,
                matchConfidence = top.score,
                candidates = sorted.take(3),
                source = top.source,
                language = language,
            )
        }

        // 6. Не нашли точно — UNRESOLVED
        return Entity(
            type = "search",
            value = candLow,
            state = EntityState.UNRESOLVED,
            matchConfidence = 0.0,
            source = "unresolved",
            language = language,
        )
    }

    private fun resolveAgainstLocalArtists(canonical: String, localArtists: Map<String, String>): String {
        val low = canonical.lowercase().trim()
        return localArtists[low] ?: localArtists[Phonetic.phon(low)] ?: canonical
    }

    private fun bestFuzzy(query: String, map: Map<String, String>, threshold: Double): Pair<String, Double>? {
        var bestVal: String? = null
        var bestScore = 0.0
        val qPhon = Phonetic.phon(query)
        for ((k, v) in map) {
            val s1 = Phonetic.ratio(query, k)
            val s2 = Phonetic.ratio(qPhon, Phonetic.phon(k))
            val score = maxOf(s1, s2)
            if (score > bestScore) {
                bestScore = score
                bestVal = v
            }
        }
        return if (bestVal != null && bestScore >= threshold) bestVal to bestScore else null
    }

    private fun russianCaseVariants(word: String): List<String> {
        if (word.length < 4) return emptyList()
        val out = mutableListOf<String>()
        when {
            word.endsWith("ю") -> {
                out += word.dropLast(1) + "я"
                out += word.dropLast(1) + "а"
            }
            word.endsWith("у") -> {
                out += word.dropLast(1) + "а"
                out += word.dropLast(1)
                if (word.length >= 5 && word.endsWith("ку")) {
                    out += word.dropLast(2) + "ок"
                }
            }
            word.endsWith("а") -> {
                out += word.dropLast(1)
                if (word.length >= 5 && word.endsWith("ка")) {
                    out += word.dropLast(2) + "ок"
                }
            }
            word.endsWith("я") -> {
                out += word.dropLast(1) + "й"
            }
            word.endsWith("ой") || word.endsWith("ей") -> {
                out += word.dropLast(2) + "а"
                out += word.dropLast(2) + "я"
            }
        }
        return out
    }

    private fun loadStringMap(assets: AssetProvider, path: String): Map<String, String> = runCatching {
        val raw = assets.readText(path) ?: return@runCatching emptyMap()
        Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.content }
            .mapKeys { it.key.lowercase().trim() }
    }.getOrDefault(emptyMap())

    private fun loadArtistAliases(assets: AssetProvider, path: String): Map<String, String> = runCatching {
        val raw = assets.readText(path) ?: return@runCatching emptyMap()
        val obj = Json.parseToJsonElement(raw).jsonObject
        buildMap {
            for ((k, v) in obj) {
                val key = k.lowercase().trim()
                if (key.isEmpty()) continue
                val artistName = runCatching {
                    v.jsonObject["artist_name"]?.jsonPrimitive?.content
                }.getOrNull() ?: runCatching {
                    v.jsonPrimitive.content
                }.getOrNull()
                if (!artistName.isNullOrBlank()) {
                    put(key, artistName.trim())
                }
            }
        }
    }.getOrDefault(emptyMap())

    companion object {
        private const val MIN_MATCH_CONFIDENCE = 0.72
        private const val ARTIST_FUZZY_THRESHOLD = 0.80
        private const val ARTIST_ALIAS_FUZZY_THRESHOLD = 0.84
        private const val AMBIGUITY_GAP = 0.15

        private val LOCAL_LIKED_KEYS = listOf(
            "мою музыку", "моя музыка", "мои лайки", "любимое", "любимые", "избранное"
        )

        private val GENERIC_MUSIC_WORDS = setOf(
            "музыку", "музыка", "музыки", "музыке",
            "песни", "песню", "песен", "треки", "треков"
        )

        private val LANG_MODIFIERS = mapOf(
            "русская" to "russian", "русскую" to "russian", "русский" to "russian",
            "русские" to "russian", "русского" to "russian", "русской" to "russian", "русское" to "russian",
            "отечественная" to "russian", "отечественную" to "russian", "отечественный" to "russian", "отечественное" to "russian",
            "наша" to "russian", "нашу" to "russian", "наши" to "russian", "наше" to "russian",
            "зарубежная" to "not-russian", "зарубежную" to "not-russian", "зарубежный" to "not-russian",
            "зарубежные" to "not-russian", "зарубежного" to "not-russian", "зарубежное" to "not-russian",
            "иностранная" to "not-russian", "иностранную" to "not-russian", "иностранный" to "not-russian", "иностранное" to "not-russian",
            "английская" to "not-russian", "английскую" to "not-russian", "английский" to "not-russian", "английское" to "not-russian"
        )
    }
}
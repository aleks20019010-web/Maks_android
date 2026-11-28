package com.maks.music.media

import com.maks.music.core.TimeBucket
import com.maks.music.core.memory.MusicMemoryDb
import com.maks.music.core.memory.TasteMapEngine
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.random.Random

enum class WaveCharacter(val label: String) {
    ALL("Всё подряд"),
    FAVORITES("Любимое"),
    FORGOTTEN("Давно не слушал"),
    NEW("Новое"),
}

enum class WaveMood(val label: String) {
    ANY("Любое"),
    ENERGETIC("Бодрое"),
    CALM("Спокойное"),
    HEAVY("Тяжёлое"),
}

data class WaveSettings(
    val character: WaveCharacter = WaveCharacter.ALL,
    val mood: WaveMood = WaveMood.ANY,
    val languageFilter: String? = null,
    val genreFilter: String? = null,
) {
    fun displayLabel(): String {
        val parts = mutableListOf<String>()
        if (character != WaveCharacter.ALL) parts += character.label
        if (mood != WaveMood.ANY) parts += mood.label
        when (languageFilter) {
            "russian" -> parts += "Русская"
            "not-russian" -> parts += "Зарубежная"
        }
        genreFilter?.takeIf { it.isNotBlank() }?.let { g ->
            val gLabel = when (g.lowercase().removePrefix("genre:")) {
                "rock" -> "Рок"
                "electronic", "dnb", "dance" -> "Электроника"
                "pop" -> "Поп"
                "rap", "hip-hop" -> "Рэп"
                "metal" -> "Метал"
                "indie" -> "Инди"
                "jazz", "blues" -> "Джаз"
                "classical", "ambient" -> "Классика"
                else -> g.removePrefix("genre:")
            }
            parts += gLabel
        }
        return if (parts.isEmpty()) "Моя волна" else "Волна · ${parts.joinToString(" · ")}"
    }
}

class SmartWaveEngine(
    private val memoryDb: MusicMemoryDb,
    private val random: Random = Random.Default,
    val coordinator: WaveCoordinator = WaveCoordinator(memoryDb),
) {
    val tasteMapEngine = TasteMapEngine(memoryDb)

    private val sessionArtistPenalty = ConcurrentHashMap<String, Double>()
    private val sessionGenrePenalty = ConcurrentHashMap<String, Double>()

    @Volatile
    private var lastAnchorArtistKey: String = ""

    fun resetSessionFeedback() {
        sessionArtistPenalty.clear()
        sessionGenrePenalty.clear()
        lastAnchorArtistKey = ""
        tasteMapEngine.invalidate()
    }

    fun recordSessionSkip(track: LocalTrack, completionRatio: Float) {
        val severity = when {
            completionRatio < 0.15f -> 0.25
            completionRatio < 0.45f -> 0.50
            else -> 0.75
        }
        val artistKey = track.artist.trim().lowercase()
        if (artistKey.isNotEmpty()) {
            val prev = sessionArtistPenalty[artistKey] ?: 1.0
            sessionArtistPenalty[artistKey] = (prev * severity).coerceAtLeast(0.08)
        }
        val bucketKey = GenreSimilarity.resolveBucket(track.genre)?.canonical
            ?: track.genre.trim().lowercase()
        if (bucketKey.isNotEmpty()) {
            val prevG = sessionGenrePenalty[bucketKey] ?: 1.0
            val gFactor = if (completionRatio < 0.20f) 0.65 else 0.85
            sessionGenrePenalty[bucketKey] = (prevG * gFactor).coerceAtLeast(0.25)
        }
        tasteMapEngine.invalidate()
    }

    fun recordSessionPositive(track: LocalTrack) {
        val artistKey = track.artist.trim().lowercase()
        if (artistKey.isNotEmpty()) {
            sessionArtistPenalty.remove(artistKey)
            lastAnchorArtistKey = artistKey
        }
        val bucketKey = GenreSimilarity.resolveBucket(track.genre)?.canonical
            ?: track.genre.trim().lowercase()
        if (bucketKey.isNotEmpty()) {
            val cur = sessionGenrePenalty[bucketKey] ?: 1.0
            if (cur < 1.0) {
                sessionGenrePenalty[bucketKey] = (cur * 1.25).coerceAtMost(1.0)
            }
        }
        tasteMapEngine.invalidate()
    }

    fun markPlayed(trackId: String) {
        coordinator.markPlayedById(trackId)
    }

    fun markPlayed(track: LocalTrack) {
        coordinator.markPlayed(track)
    }

    fun buildWaveQueue(
        allTracks: List<LocalTrack>,
        languageFilter: String? = null,
        size: Int = 30,
    ): List<LocalTrack> = buildWaveQueue(
        allTracks = allTracks,
        settings = WaveSettings(languageFilter = languageFilter),
        size = size,
    )

    fun buildWaveQueue(
        allTracks: List<LocalTrack>,
        settings: WaveSettings,
        size: Int = 30,
    ): List<LocalTrack> {
        if (allTracks.isEmpty()) return emptyList()

        val blacklistedTracks = runCatching { memoryDb.getBlacklistedTrackIds() }.getOrDefault(emptySet())
        val blacklistedArtists = runCatching { memoryDb.getBlacklistedArtists() }.getOrDefault(emptySet())
        val artistWeights = runCatching { memoryDb.getArtistWeights() }.getOrDefault(emptyMap())
        val habitScores = runCatching { memoryDb.getHabitScores(TimeBucket.current()) }.getOrDefault(emptyMap())
        val trackStats = runCatching { memoryDb.getTrackPlayStats() }.getOrDefault(emptyMap())
        val likedIds = runCatching { memoryDb.getLikedTrackIds() }.getOrDefault(emptySet())
        val likedKeys = runCatching { memoryDb.getLikedTrackKeys() }.getOrDefault(emptySet())
        val tasteMap = runCatching { tasteMapEngine.getOrBuild(allTracks) }.getOrNull()

        // 1. Чёрный список
        var pool = allTracks.filter { t ->
            t.id !in blacklistedTracks && t.artist.trim().lowercase() !in blacklistedArtists
        }
        if (pool.isEmpty()) pool = allTracks

        // 2. Язык (строго)
        pool = when (settings.languageFilter) {
            "russian" -> pool.filter { it.isRussian }
            "not-russian" -> pool.filter { !it.isRussian }
            else -> pool
        }
        if (pool.isEmpty()) return emptyList()

        // 3. Характер (для FAVORITES — строго лайкнутые)
        val now = System.currentTimeMillis()
        val weekAgo = now - 7L * 24L * 3600L * 1000L
        pool = when (settings.character) {
            WaveCharacter.ALL -> pool
            WaveCharacter.FAVORITES -> pool.filter { t ->
                isTrackLiked(t, likedIds, likedKeys)
            }
            WaveCharacter.FORGOTTEN -> pool.filter { t ->
                val stat = trackStats[t.id]
                stat != null && stat.first > 0 && stat.second < weekAgo
            }
            WaveCharacter.NEW -> pool.filter { t ->
                (trackStats[t.id]?.first ?: 0) == 0
            }
        }
        if (pool.isEmpty()) return emptyList()

        // 4. Жанр с плавной эскалацией на похожие стили (GenreSimilarity)
        val targetGenre = settings.genreFilter?.trim()?.lowercase()?.removePrefix("genre:")
        val genreWeightMap = HashMap<String, Double>(pool.size)
        if (!targetGenre.isNullOrBlank()) {
            val exactMatches = mutableListOf<LocalTrack>()
            val neighborMatches = mutableListOf<LocalTrack>()

            for (t in pool) {
                val w = GenreSimilarity.matchWeight(t, targetGenre)
                if (w >= 1.0) {
                    exactMatches += t
                    genreWeightMap[t.id] = 1.0
                } else if (w > 0.0) {
                    neighborMatches += t
                    genreWeightMap[t.id] = w
                }
            }

            val freshExact = coordinator.filterLocalCandidates(
                candidates = exactMatches,
                settings = settings,
                trackStats = trackStats,
                blacklistedTracks = blacklistedTracks,
                blacklistedArtists = blacklistedArtists,
                nowMs = now,
                strictSessionOnly = true,
            )

            pool = when {
                freshExact.size >= 5 -> exactMatches
                exactMatches.isNotEmpty() || neighborMatches.isNotEmpty() -> {
                    (exactMatches + neighborMatches).distinctBy { it.id }
                }
                else -> pool
            }
        }

        // 5. Настроение: если в текущем пуле достаточно треков с явным совпадением настроения, сужаем пул;
        // иначе оставляем пул и усиливаем совпадения в pickWeightedTrack.
        val moodMatchMap = HashMap<String, Boolean>(pool.size)
        if (settings.mood != WaveMood.ANY) {
            for (t in pool) {
                moodMatchMap[t.id] = matchesMood(t, settings.mood)
            }
            val moodPool = pool.filter { moodMatchMap[it.id] == true }
            if (moodPool.size >= 6) {
                pool = moodPool
            }
        }

        val fresh = coordinator.filterLocalCandidates(
            candidates = pool,
            settings = settings,
            trackStats = trackStats,
            blacklistedTracks = blacklistedTracks,
            blacklistedArtists = blacklistedArtists,
            nowMs = now,
        ).ifEmpty { pool }

        val remaining = fresh.toMutableList()
        val queue = mutableListOf<LocalTrack>()
        val recentArtists = ArrayDeque<String>()

        while (queue.size < size && remaining.isNotEmpty()) {
            val slotIndex = queue.size
            val explorationBoost = (settings.character == WaveCharacter.ALL && slotIndex > 0 && slotIndex % 5 == 4)

            val picked = pickWeightedTrack(
                candidates = remaining,
                settings = settings,
                tasteMap = tasteMap,
                artistWeights = artistWeights,
                habitScores = habitScores,
                trackStats = trackStats,
                likedIds = likedIds,
                likedKeys = likedKeys,
                genreWeightMap = genreWeightMap,
                moodMatchMap = moodMatchMap,
                nowMs = now,
                recentArtists = recentArtists,
                explorationBoost = explorationBoost,
            )
            queue += picked
            remaining.remove(picked)

            val aKey = picked.artist.trim().lowercase()
            if (aKey.isNotEmpty()) {
                recentArtists.addLast(aKey)
                while (recentArtists.size > 4) recentArtists.removeFirst()
            }
        }

        coordinator.registerQueueTracks(queue)
        return queue
    }

    private fun isTrackLiked(
        track: LocalTrack,
        likedIds: Set<String>,
        likedKeys: Set<String>,
    ): Boolean {
        val key = MusicMemoryDb.trackKey(track.artist, track.title)
        return track.id in likedIds ||
            "ya_${track.id}" in likedIds ||
            (key.isNotEmpty() && key in likedKeys)
    }

    private fun matchesMood(track: LocalTrack, mood: WaveMood): Boolean {
        if (mood == WaveMood.ANY) return true
        val hay = "${track.genre} ${track.album} ${track.title}".lowercase()
        val bucket = GenreSimilarity.resolveBucket(track.genre)?.canonical.orEmpty()
        return when (mood) {
            WaveMood.ENERGETIC -> bucket in setOf("pop", "dance", "electronic", "rap", "rock") ||
                listOf("rock", "pop", "dance", "dnb", "drum", "house", "punk", "electronic", "rap", "hip", "disco", "энерг", "бодр")
                    .any { it in hay }
            WaveMood.CALM -> bucket in setOf("ambient", "jazz", "rnb") ||
                listOf("ambient", "chill", "acoustic", "indie", "jazz", "blues", "classical", "ballad", "lofi", "спокой", "лирик", "ночь")
                    .any { it in hay }
            WaveMood.HEAVY -> bucket in setOf("metal", "rock") ||
                listOf("metal", "hard", "heavy", "thrash", "death", "doom", "punk", "core", "industrial", "grunge", "метал", "хард")
                    .any { it in hay }
            WaveMood.ANY -> true
        }
    }

    private fun pickWeightedTrack(
        candidates: List<LocalTrack>,
        settings: WaveSettings,
        tasteMap: TasteMapEngine.TasteMap?,
        artistWeights: Map<String, Double>,
        habitScores: Map<String, Double>,
        trackStats: Map<String, Pair<Int, Long>>,
        likedIds: Set<String>,
        likedKeys: Set<String>,
        genreWeightMap: Map<String, Double>,
        moodMatchMap: Map<String, Boolean>,
        nowMs: Long,
        recentArtists: ArrayDeque<String>,
        explorationBoost: Boolean,
    ): LocalTrack {
        val anchorNeighbors = lastAnchorArtistKey
            .takeIf { it.isNotEmpty() }
            ?.let { tasteMap?.relatedArtists?.get(it) }
            .orEmpty()

        val scored = candidates.map { track ->
            val artistKey = track.artist.trim().lowercase()
            val tKey = MusicMemoryDb.trackKey(track.artist, track.title)
            val w = artistWeights[artistKey] ?: 0.0
            val habit = habitScores[artistKey] ?: 0.0
            val stat = trackStats[track.id]
            val playCount = stat?.first ?: 0
            val lastPlayed = stat?.second ?: 0L
            val isLiked = isTrackLiked(track, likedIds, likedKeys)

            val affinity = tasteMap?.trackAffinities?.get(track.id) ?: tasteMap?.trackAffinities?.get(tKey)

            var score = exp((w * 0.25).coerceIn(-1.5, 2.0)) + (habit * 0.15).coerceAtMost(1.5)
            if (isLiked) score *= 1.35

            if (affinity != null) {
                val affinityFactor = (1.0 + affinity.score * 0.12).coerceIn(0.45, 1.85)
                score *= affinityFactor
                if (affinity.isFatigued && settings.character != WaveCharacter.FAVORITES) {
                    score *= 0.22
                }
            }
            if (tasteMap != null && track.id in tasteMap.risingGemIds) {
                score *= 1.25
            }

            anchorNeighbors[artistKey]?.let { linkStrength ->
                score *= (1.0 + linkStrength * 0.45)
            }

            val bucketKey = GenreSimilarity.resolveBucket(track.genre)?.canonical
                ?: track.genre.trim().lowercase()
            if (bucketKey.isNotEmpty()) {
                tasteMap?.bucketGenreAffinity?.get(bucketKey)?.let { timeMult ->
                    score *= timeMult
                }
                sessionGenrePenalty[bucketKey]?.let { gp ->
                    score *= gp
                }
            }
            genreWeightMap[track.id]?.let { gw -> score *= gw }

            if (settings.mood != WaveMood.ANY) {
                if (moodMatchMap[track.id] == true) {
                    score *= 1.45
                } else {
                    score *= 0.55
                }
            }

            if (artistKey.isNotEmpty()) {
                sessionArtistPenalty[artistKey]?.let { p -> score *= p }
            }

            if (artistKey.isNotEmpty() && artistKey in recentArtists) {
                val distFromEnd = recentArtists.reversed().indexOf(artistKey)
                score *= when (distFromEnd) {
                    0 -> 0.08
                    1 -> 0.25
                    else -> 0.55
                }
            }

            if (playCount > 1) {
                score /= (1.0 + (playCount - 1) * 0.2).coerceAtMost(2.5)
            }
            if (lastPlayed > 0L) {
                val hoursAgo = ((nowMs - lastPlayed).coerceAtLeast(0L)) / 3_600_000.0
                when {
                    hoursAgo < 12.0 -> score *= 0.35
                    hoursAgo < 24.0 -> score *= 0.65
                    hoursAgo > 72.0 -> score *= if (explorationBoost) 1.65 else 1.15
                }
            } else {
                score *= if (explorationBoost) 1.85 else 1.15
            }

            score *= (0.78 + random.nextDouble() * 0.44)
            track to score.coerceAtLeast(0.03)
        }

        val total = scored.sumOf { it.second }
        var r = random.nextDouble() * total
        for ((track, s) in scored) {
            r -= s
            if (r <= 0.0) return track
        }
        return scored.last().first
    }
}
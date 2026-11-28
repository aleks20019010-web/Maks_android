package com.maks.music.media

import com.maks.music.core.TimeBucket
import com.maks.music.core.memory.MusicMemoryDb
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
    val languageFilter: String? = null, // null = любой, "russian", "not-russian"
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
            val gLabel = when (g.lowercase()) {
                "rock" -> "Рок"
                "electronic", "dnb", "dance" -> "Электроника"
                "pop" -> "Поп"
                "rap", "hip-hop" -> "Рэп"
                "metal" -> "Метал"
                "indie" -> "Инди"
                "jazz", "blues" -> "Джаз"
                "classical", "ambient" -> "Классика"
                else -> g
            }
            parts += gLabel
        }
        return if (parts.isEmpty()) "Моя волна" else "Волна · ${parts.joinToString(" · ")}"
    }
}

/**
 * Адаптивный рекомендательный движок MaxSound:
 * - Многосигнальный скоринг (веса артистов, контекст времени суток, лайки, частота и давность)
 * - Плавный переход по графу жанровой близости (GenreSimilarity) при нехватке основного жанра
 * - Сессионная реакция на скипы в реальном времени (наказание скипнутого артиста/жанра в текущей волне)
 * - Баланс Exploration / Exploitation (чередование проверенного, забытого и нового)
 */
class SmartWaveEngine(
    private val memoryDb: MusicMemoryDb,
    private val random: Random = Random.Default,
    val coordinator: WaveCoordinator = WaveCoordinator(memoryDb),
) {
    // Временные штрафы внутри текущей сессии волны (0.1..1.0)
    private val sessionArtistPenalty = ConcurrentHashMap<String, Double>()
    private val sessionGenrePenalty = ConcurrentHashMap<String, Double>()

    fun resetSessionFeedback() {
        sessionArtistPenalty.clear()
        sessionGenrePenalty.clear()
    }

    /**
     * Вызывается при пользовательском скипе в волне: мгновенно снижает вес артиста и его жанрового бакета
     * в рамках текущей сессии, чтобы хвост волны перестроился на лету.
     */
    fun recordSessionSkip(track: LocalTrack, completionRatio: Float) {
        val severity = when {
            completionRatio < 0.15f -> 0.25 // жёсткий ранний скип
            completionRatio < 0.45f -> 0.50 // средний скип
            else -> 0.75                    // поздний скип
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
    }

    /**
     * Вызывается при дослушивании или лайке: мягко снимает сессионные штрафы с артиста и жанра.
     */
    fun recordSessionPositive(track: LocalTrack) {
        val artistKey = track.artist.trim().lowercase()
        if (artistKey.isNotEmpty()) {
            sessionArtistPenalty.remove(artistKey)
        }
        val bucketKey = GenreSimilarity.resolveBucket(track.genre)?.canonical
            ?: track.genre.trim().lowercase()
        if (bucketKey.isNotEmpty()) {
            val cur = sessionGenrePenalty[bucketKey] ?: 1.0
            if (cur < 1.0) {
                sessionGenrePenalty[bucketKey] = (cur * 1.25).coerceAtMost(1.0)
            }
        }
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

        val blacklistedTracks = memoryDb.getBlacklistedTrackIds()
        val blacklistedArtists = memoryDb.getBlacklistedArtists()
        val artistWeights = memoryDb.getArtistWeights()
        val habitScores = memoryDb.getHabitScores(TimeBucket.current())
        val trackStats = memoryDb.getTrackPlayStats()
        val likedIds = memoryDb.getLikedTrackIds()
        val likedKeys = memoryDb.getLikedTrackKeys()

        var pool = allTracks.filter { t ->
            t.id !in blacklistedTracks && t.artist.trim().lowercase() !in blacklistedArtists
        }
        if (pool.isEmpty()) pool = allTracks

        // 1. Язык
        if (settings.languageFilter == "russian") {
            pool = pool.filter { it.isRussian }
        } else if (settings.languageFilter == "not-russian") {
            pool = pool.filter { !it.isRussian }
        }
        if (pool.isEmpty()) return emptyList()

        // 2. Настроение
        if (settings.mood != WaveMood.ANY) {
            val hints = when (settings.mood) {
                WaveMood.ENERGETIC -> listOf("rock", "pop", "dance", "dnb", "punk", "electronic", "rap", "hip")
                WaveMood.CALM -> listOf("ambient", "chill", "acoustic", "indie", "jazz", "classical", "ballad", "lofi")
                WaveMood.HEAVY -> listOf("metal", "hard", "rock", "punk", "core", "industrial", "grunge")
                WaveMood.ANY -> emptyList()
            }
            val moodFiltered = pool.filter { t ->
                hints.any { h -> t.genre.contains(h, ignoreCase = true) || t.album.contains(h, ignoreCase = true) }
            }
            if (moodFiltered.size >= 5) pool = moodFiltered
        }

        // 3. Характер (строгие лайки для FAVORITES)
        val now = System.currentTimeMillis()
        val weekAgo = now - 7L * 24L * 3600L * 1000L
        pool = when (settings.character) {
            WaveCharacter.ALL -> pool
            WaveCharacter.FAVORITES -> pool.filter { t ->
                t.id in likedIds ||
                    "ya_${t.id}" in likedIds ||
                    MusicMemoryDb.trackKey(t.artist, t.title) in likedKeys
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

            // Проверяем, сколько свежих (ещё не игравших в волне) треков осталось в основном жанре
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
                    // Основной жанр на исходе -> плавно подключаем похожие жанры с весом 0.65
                    (exactMatches + neighborMatches).distinctBy { it.id }
                }
                else -> pool // если теги жанров в библиотеке пустые, не падаем в пустоту
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
            // Слоты Exploration/Exploitation для общего режима:
            // каждый 5-й слот отдаём приоритет новым/забытым трекам, чтобы волна не зацикливалась
            val slotIndex = queue.size
            val explorationBoost = (settings.character == WaveCharacter.ALL && slotIndex > 0 && slotIndex % 5 == 4)

            val picked = pickWeightedTrack(
                candidates = remaining,
                artistWeights = artistWeights,
                habitScores = habitScores,
                trackStats = trackStats,
                likedIds = likedIds,
                likedKeys = likedKeys,
                genreWeightMap = genreWeightMap,
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

    private fun pickWeightedTrack(
        candidates: List<LocalTrack>,
        artistWeights: Map<String, Double>,
        habitScores: Map<String, Double>,
        trackStats: Map<String, Pair<Int, Long>>,
        likedIds: Set<String>,
        likedKeys: Set<String>,
        genreWeightMap: Map<String, Double>,
        nowMs: Long,
        recentArtists: ArrayDeque<String>,
        explorationBoost: Boolean,
    ): LocalTrack {
        val scored = candidates.map { track ->
            val artistKey = track.artist.trim().lowercase()
            val w = artistWeights[artistKey] ?: 0.0
            val habit = habitScores[artistKey] ?: 0.0
            val stat = trackStats[track.id]
            val playCount = stat?.first ?: 0
            val lastPlayed = stat?.second ?: 0L
            val isLiked = track.id in likedIds ||
                "ya_${track.id}" in likedIds ||
                MusicMemoryDb.trackKey(track.artist, track.title) in likedKeys

            // 1. Базовый вес по предпочтениям и контексту времени суток
            var score = exp((w * 0.25).coerceIn(-1.5, 2.0)) + (habit * 0.15).coerceAtMost(1.5)
            if (isLiked) score *= 1.35

            // 2. Жанровая близость (1.0 для точного жанра, 0.65 для похожего)
            genreWeightMap[track.id]?.let { gw -> score *= gw }

            // 3. Сессионные штрафы (реакция на скипы в текущей волне)
            if (artistKey.isNotEmpty()) {
                sessionArtistPenalty[artistKey]?.let { p -> score *= p }
            }
            val bucketKey = GenreSimilarity.resolveBucket(track.genre)?.canonical
                ?: track.genre.trim().lowercase()
            if (bucketKey.isNotEmpty()) {
                sessionGenrePenalty[bucketKey]?.let { gp -> score *= gp }
            }

            // 4. Анти-усталость по артисту (diversity window на последние 4 трека)
            if (artistKey.isNotEmpty() && artistKey in recentArtists) {
                val distFromEnd = recentArtists.reversed().indexOf(artistKey)
                score *= when (distFromEnd) {
                    0 -> 0.08 // подряд — почти исключаем
                    1 -> 0.25 // через 1 трек — сильно режем
                    else -> 0.55
                }
            }

            // 5. Давность и Exploration/Exploitation
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
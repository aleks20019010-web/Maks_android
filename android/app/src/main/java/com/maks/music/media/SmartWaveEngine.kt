package com.maks.music.media

import com.maks.music.core.TimeBucket
import com.maks.music.core.memory.MusicMemoryDb
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
) {
    fun displayLabel(): String {
        val parts = mutableListOf<String>()
        if (character != WaveCharacter.ALL) parts += character.label
        if (mood != WaveMood.ANY) parts += mood.label
        when (languageFilter) {
            "russian" -> parts += "Русская"
            "not-russian" -> parts += "Зарубежная"
        }
        return if (parts.isEmpty()) "Моя волна" else "Волна · ${parts.joinToString(" · ")}"
    }
}

/**
 * Локальная «Моя Волна» Макса (100% оффлайн) с тонкой настройкой по 3 осям:
 *   - Характер (Любимое / Давно не слушал / Новое)
 *   - Настроение (Бодрое / Спокойное / Тяжёлое)
 *   - Язык (Любой / Русский / Зарубежный)
 */
class SmartWaveEngine(
    private val memoryDb: MusicMemoryDb,
    private val random: Random = Random.Default,
) {
    private val recentlyPlayedIds = ArrayDeque<String>()

    fun markPlayed(trackId: String) {
        recentlyPlayedIds.remove(trackId)
        recentlyPlayedIds.addLast(trackId)
        while (recentlyPlayedIds.size > RECENT_HISTORY_LIMIT) {
            recentlyPlayedIds.removeFirst()
        }
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

        // 1. Языковой фильтр
        if (settings.languageFilter == "russian") {
            pool = pool.filter { it.isRussian }
        } else if (settings.languageFilter == "not-russian") {
            pool = pool.filter { !it.isRussian }
        }
        if (pool.isEmpty()) return emptyList()

        // 2. Настроение (мягкий фильтр с фоллбэком)
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

        // 3. Характер (Любимое / Давно не слушал / Новое)
        val now = System.currentTimeMillis()
        val weekAgo = now - 7L * 24L * 3600L * 1000L
        val charPool = when (settings.character) {
            WaveCharacter.ALL -> pool
            WaveCharacter.FAVORITES -> pool.filter { t ->
                t.id in likedIds ||
                    MusicMemoryDb.trackKey(t.artist, t.title) in likedKeys ||
                    (trackStats[t.id]?.first ?: 0) >= 2
            }
            WaveCharacter.FORGOTTEN -> pool.filter { t ->
                val stat = trackStats[t.id]
                stat != null && stat.first > 0 && stat.second < weekAgo
            }
            WaveCharacter.NEW -> pool.filter { t ->
                (trackStats[t.id]?.first ?: 0) == 0
            }
        }
        if (charPool.isNotEmpty()) pool = charPool

        val fresh = pool.filter { it.id !in recentlyPlayedIds }.ifEmpty { pool }
        val remaining = fresh.toMutableList()
        val queue = mutableListOf<LocalTrack>()
        var lastArtist = ""

        while (queue.size < size && remaining.isNotEmpty()) {
            val picked = pickWeightedTrack(remaining, artistWeights, habitScores, lastArtist)
            queue += picked
            remaining.remove(picked)
            lastArtist = picked.artist.trim().lowercase()
        }

        return queue
    }

    private fun pickWeightedTrack(
        candidates: List<LocalTrack>,
        artistWeights: Map<String, Double>,
        habitScores: Map<String, Double>,
        lastArtist: String,
    ): LocalTrack {
        val scored = candidates.map { track ->
            val artistKey = track.artist.trim().lowercase()
            val w = artistWeights[artistKey] ?: 0.0
            val habit = habitScores[artistKey] ?: 0.0

            var score = exp((w * 0.25).coerceIn(-1.5, 2.0)) + (habit * 0.15).coerceAtMost(1.5)
            if (artistKey.isNotEmpty() && artistKey == lastArtist) {
                score *= 0.15
            }
            score *= (0.75 + random.nextDouble() * 0.5)
            track to score.coerceAtLeast(0.05)
        }

        val total = scored.sumOf { it.second }
        var r = random.nextDouble() * total
        for ((track, s) in scored) {
            r -= s
            if (r <= 0.0) return track
        }
        return scored.last().first
    }

    companion object {
        private const val RECENT_HISTORY_LIMIT = 40
    }
}
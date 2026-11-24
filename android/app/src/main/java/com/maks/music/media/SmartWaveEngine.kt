package com.maks.music.media

import com.maks.music.core.TimeBucket
import com.maks.music.core.memory.MusicMemoryDb
import kotlin.math.exp
import kotlin.random.Random

/**
 * Локальная «Моя Волна» Макса (100% оффлайн):
 * подбирает очередь из локальной медиатеки с учётом:
 *   - весов артистов из memory.db (artist_stats)
 *   - привычек по времени суток (habits[morning/day/evening/night])
 *   - языкового фильтра («русская» / «зарубежная»)
 *   - чёрного списка треков и артистов
 *   - защиты от повтора недавно сыгранных треков и одного артиста подряд
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
    ): List<LocalTrack> {
        if (allTracks.isEmpty()) return emptyList()

        val blacklistedTracks = memoryDb.getBlacklistedTrackIds()
        val blacklistedArtists = memoryDb.getBlacklistedArtists()
        val artistWeights = memoryDb.getArtistWeights()
        val habitScores = memoryDb.getHabitScores(TimeBucket.current())

        var pool = allTracks.filter { t ->
            t.id !in blacklistedTracks && t.artist.trim().lowercase() !in blacklistedArtists
        }
        if (pool.isEmpty()) pool = allTracks

        // Строгий языковой фильтр («русская» / «зарубежная»)
        if (languageFilter == "russian") {
            pool = pool.filter { it.isRussian }
        } else if (languageFilter == "not-russian") {
            pool = pool.filter { !it.isRussian }
        }
        if (pool.isEmpty()) return emptyList()

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
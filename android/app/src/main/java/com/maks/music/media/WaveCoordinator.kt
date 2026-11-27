package com.maks.music.media

import com.maks.music.core.memory.MusicMemoryDb
import java.util.Collections

/**
 * Единый координатор «Моей волны»:
 * - хранит историю треков, уже звучавших или поставленных в очередь в текущей волне;
 * - сверяется с долговременной памятью (MusicMemoryDb) по ID и нормализованному ключу artist+title;
 * - фильтрует кандидатов и для SmartWaveEngine, и для YandexQueueBridge по единым правилам.
 */
class WaveCoordinator(
    private val memoryDb: MusicMemoryDb,
) {
    private val lock = Any()

    private val waveSeenIds: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val waveSeenKeys: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val recentSessionIds: ArrayDeque<String> = ArrayDeque()
    private val recentSessionKeys: ArrayDeque<String> = ArrayDeque()

    @Volatile
    private var activeSettings: WaveSettings = WaveSettings()

    @Volatile
    private var activeWaveStartedAtMs: Long = 0L

    fun startNewWave(settings: WaveSettings, seedQueue: List<LocalTrack> = emptyList()) {
        synchronized(lock) {
            activeSettings = settings
            activeWaveStartedAtMs = System.currentTimeMillis()
            waveSeenIds.clear()
            waveSeenKeys.clear()
            seedQueue.forEach { track ->
                markTrackReservedInternal(track)
            }
        }
    }

    fun registerQueueTracks(tracks: List<LocalTrack>) {
        synchronized(lock) {
            tracks.forEach { markTrackReservedInternal(it) }
        }
    }

    fun markPlayed(track: LocalTrack) {
        synchronized(lock) {
            markTrackReservedInternal(track)
            pushRecent(recentSessionIds, track.id)
            val key = canonicalKey(track)
            if (key.isNotBlank()) pushRecent(recentSessionKeys, key)
        }
    }

    fun markPlayedById(trackId: String) {
        synchronized(lock) {
            if (trackId.isBlank()) return
            waveSeenIds.add(trackId)
            pushRecent(recentSessionIds, trackId)
        }
    }

    fun isAlreadyUsedInWave(track: LocalTrack): Boolean {
        val key = canonicalKey(track)
        synchronized(lock) {
            if (track.id in waveSeenIds) return true
            if (key.isNotBlank() && key in waveSeenKeys) return true
            if (track.id in recentSessionIds) return true
            if (key.isNotBlank() && key in recentSessionKeys) return true
        }
        return false
    }

    fun filterLocalCandidates(
        candidates: List<LocalTrack>,
        settings: WaveSettings = activeSettings,
        trackStats: Map<String, Pair<Int, Long>> = runCatching { memoryDb.getTrackPlayStats() }.getOrDefault(emptyMap()),
        blacklistedTracks: Set<String> = runCatching { memoryDb.getBlacklistedTrackIds() }.getOrDefault(emptySet()),
        blacklistedArtists: Set<String> = runCatching { memoryDb.getBlacklistedArtists() }.getOrDefault(emptySet()),
        nowMs: Long = System.currentTimeMillis(),
        strictSessionOnly: Boolean = false,
    ): List<LocalTrack> {
        val filtered = candidates.filter { track ->
            isTrackEligible(
                track = track,
                settings = settings,
                trackStats = trackStats,
                blacklistedTracks = blacklistedTracks,
                blacklistedArtists = blacklistedArtists,
                nowMs = nowMs,
                enforceCooldown = !strictSessionOnly,
            )
        }
        if (filtered.isNotEmpty()) return filtered

        // Мягкий фоллбэк: если пул исчерпан, разрешаем треки с истёкшим кулдауном,
        // но всё ещё не добавленные в текущую волну и не в чёрном списке.
        return candidates.filter { track ->
            isTrackEligible(
                track = track,
                settings = settings,
                trackStats = trackStats,
                blacklistedTracks = blacklistedTracks,
                blacklistedArtists = blacklistedArtists,
                nowMs = nowMs,
                enforceCooldown = false,
            )
        }
    }

    fun acceptRemoteCandidate(
        track: LocalTrack,
        settings: WaveSettings = activeSettings,
        blacklistedTracks: Set<String> = runCatching { memoryDb.getBlacklistedTrackIds() }.getOrDefault(emptySet()),
        blacklistedArtists: Set<String> = runCatching { memoryDb.getBlacklistedArtists() }.getOrDefault(emptySet()),
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val trackStats = runCatching { memoryDb.getTrackPlayStats() }.getOrDefault(emptyMap())
        val ok = isTrackEligible(
            track = track,
            settings = settings,
            trackStats = trackStats,
            blacklistedTracks = blacklistedTracks,
            blacklistedArtists = blacklistedArtists,
            nowMs = nowMs,
            enforceCooldown = settings.character != WaveCharacter.FAVORITES,
        )
        if (ok) {
            synchronized(lock) {
                markTrackReservedInternal(track)
            }
        }
        return ok
    }

    private fun isTrackEligible(
        track: LocalTrack,
        settings: WaveSettings,
        trackStats: Map<String, Pair<Int, Long>>,
        blacklistedTracks: Set<String>,
        blacklistedArtists: Set<String>,
        nowMs: Long,
        enforceCooldown: Boolean,
    ): Boolean {
        if (track.id in blacklistedTracks) return false
        val artistKey = track.artist.trim().lowercase()
        if (artistKey.isNotEmpty() && artistKey in blacklistedArtists) return false
        if (isAlreadyUsedInWave(track)) return false

        when (settings.languageFilter) {
            "russian" -> if (!track.isRussian) return false
            "not-russian" -> if (track.isRussian) return false
        }

        if (enforceCooldown && settings.character != WaveCharacter.FAVORITES) {
            val lastPlayed = trackStats[track.id]?.second ?: 0L
            if (lastPlayed > 0L && (nowMs - lastPlayed) < RECENT_COOLDOWN_MS) {
                return false
            }
        }

        return true
    }

    private fun markTrackReservedInternal(track: LocalTrack) {
        if (track.id.isNotBlank()) {
            waveSeenIds.add(track.id)
        }
        val key = canonicalKey(track)
        if (key.isNotBlank()) {
            waveSeenKeys.add(key)
        }
    }

    private fun pushRecent(queue: ArrayDeque<String>, value: String) {
        if (value.isBlank()) return
        queue.remove(value)
        queue.addLast(value)
        while (queue.size > RECENT_SESSION_LIMIT) {
            queue.removeFirst()
        }
    }

    companion object {
        private const val RECENT_COOLDOWN_MS = 3L * 3600L * 1000L
        private const val RECENT_SESSION_LIMIT = 60

        fun canonicalKey(track: LocalTrack): String {
            val a = normalizePart(track.artist)
            val t = normalizePart(track.title)
            return if (t.isBlank()) "" else "$a::$t"
        }

        private fun normalizePart(raw: String): String =
            raw.lowercase()
                .replace(Regex("""[^a-z\u0430-\u044f\u04510-9 ]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
    }
}
package com.maks.music.core.memory

import com.maks.music.core.TimeBucket
import com.maks.music.media.GenreSimilarity
import com.maks.music.media.LocalTrack
import kotlin.math.exp

/**
 * Локальный детерминированный построитель «Карты вкуса» (на чистых правилах, 100% оффлайн):
 * - рассчитывает affinity-скор каждого трека с учётом time-decay (полураспад 21 день);
 * - определяет «уставшие» треки (серия свежих скипов), чтобы дать им отдохнуть в общей волне;
 * - строит граф совместного прослушивания артистов (co-listening) по сессиям;
 * - выделяет предпочтительные жанровые бакеты для текущего TimeBucket.
 */
class TasteMapEngine(
    private val memoryDb: MusicMemoryDb,
) {
    data class TrackAffinity(
        val trackId: String,
        val trackKey: String,
        val score: Double,
        val finishes: Int,
        val recentSkips: Int,
        val isFatigued: Boolean,
        val lastPositiveTs: Long,
    )

    data class TasteMap(
        val generatedAtMs: Long,
        val bucket: TimeBucket,
        val trackAffinities: Map<String, TrackAffinity>,      // по trackId и по trackKey
        val fatiguedTrackIds: Set<String>,
        val coreFavoriteIds: Set<String>,                     // ядро «условно избранных» для буста в общей волне
        val risingGemIds: Set<String>,                        // недавно зашедшие треки без лайка
        val relatedArtists: Map<String, Map<String, Double>>, // artistKey -> (neighborArtistKey -> strength 0..1)
        val bucketGenreAffinity: Map<String, Double>,         // canonicalGenre -> multiplier (0.7..1.45)
    )

    @Volatile
    private var cachedMap: TasteMap? = null

    fun getOrBuild(
        allTracks: List<LocalTrack>,
        forceRefresh: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): TasteMap {
        val existing = cachedMap
        if (!forceRefresh && existing != null && (nowMs - existing.generatedAtMs) < CACHE_TTL_MS &&
            existing.bucket == TimeBucket.current(nowMs)
        ) {
            return existing
        }
        return buildInternal(allTracks, nowMs).also { cachedMap = it }
    }

    fun invalidate() {
        cachedMap = null
    }

    private fun buildInternal(allTracks: List<LocalTrack>, nowMs: Long): TasteMap {
        val bucket = TimeBucket.current(nowMs)
        val likedIds = runCatching { memoryDb.getLikedTrackIds() }.getOrDefault(emptySet())
        val likedKeys = runCatching { memoryDb.getLikedTrackKeys() }.getOrDefault(emptySet())

        val trackGenreById = HashMap<String, String>(allTracks.size)
        val trackGenreByKey = HashMap<String, String>(allTracks.size)
        for (t in allTracks) {
            val g = GenreSimilarity.resolveBucket(t.genre)?.canonical ?: t.genre.trim().lowercase()
            if (g.isNotEmpty()) {
                trackGenreById[t.id] = g
                val k = MusicMemoryDb.trackKey(t.artist, t.title)
                if (k.isNotEmpty()) trackGenreByKey[k] = g
            }
        }

        // Читаем события за последние 90 дней для расчёта затухающих весов и сессий
        val lookbackMs = 90L * 86_400_000L
        val sinceMs = nowMs - lookbackMs
        val tenDaysAgo = nowMs - 10L * 86_400_000L

        data class RawEvent(
            val ts: Long,
            val bucketId: String,
            val type: String,
            val trackId: String,
            val artist: String,
            val title: String,
        )

        val events = mutableListOf<RawEvent>()
        runCatching {
            memoryDb.readableDatabase.rawQuery(
                """
                SELECT ts, time_bucket, event_type, track_id, artist, title
                FROM events
                WHERE ts >= ?
                ORDER BY ts ASC
                """.trimIndent(),
                arrayOf(sinceMs.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    events += RawEvent(
                        ts = c.getLong(0),
                        bucketId = c.getString(1).orEmpty(),
                        type = c.getString(2).orEmpty(),
                        trackId = c.getString(3).orEmpty(),
                        artist = c.getString(4).orEmpty(),
                        title = c.getString(5).orEmpty(),
                    )
                }
            }
        }

        class Acc {
            var score = 0.0
            var finishes = 0
            var trailingSkips = 0
            var recentSkips = 0
            var lastPositiveTs = 0L
            var trackId = ""
            var trackKey = ""
        }

        val byKey = LinkedHashMap<String, Acc>()
        val bucketGenreRaw = HashMap<String, Double>()
        val pairCounts = HashMap<String, HashMap<String, Double>>()

        var prevFinishArtist = ""
        var prevFinishTs = 0L

        for (ev in events) {
            val ageDays = ((nowMs - ev.ts).coerceAtLeast(0L)) / 86_400_000.0
            val decay = exp(-ageDays / HALF_LIFE_DAYS)

            val tKey = MusicMemoryDb.trackKey(ev.artist, ev.title).ifBlank { ev.trackId }
            val aKey = ev.artist.trim().lowercase()

            if (tKey.isNotBlank()) {
                val acc = byKey.getOrPut(tKey) { Acc() }
                if (ev.trackId.isNotBlank()) acc.trackId = ev.trackId
                acc.trackKey = tKey

                when (ev.type) {
                    "play_start" -> {
                        acc.score += 0.35 * decay
                    }
                    "play_finish" -> {
                        acc.score += 1.25 * decay
                        acc.finishes += 1
                        acc.trailingSkips = 0
                        acc.lastPositiveTs = maxOf(acc.lastPositiveTs, ev.ts)
                    }
                    "like" -> {
                        acc.score += 4.0 * decay
                        acc.trailingSkips = 0
                        acc.lastPositiveTs = maxOf(acc.lastPositiveTs, ev.ts)
                    }
                    "skip" -> {
                        acc.score -= 1.40 * decay
                        acc.trailingSkips += 1
                        if (ev.ts >= tenDaysAgo) {
                            acc.recentSkips += 1
                        }
                    }
                    "dislike_track" -> {
                        acc.score -= 6.0
                        acc.trailingSkips += 3
                    }
                }
            }

            // Контекстный профиль жанров для текущего TimeBucket
            if (ev.bucketId == bucket.id) {
                val g = trackGenreById[ev.trackId] ?: trackGenreByKey[tKey]
                if (!g.isNullOrBlank()) {
                    val delta = when (ev.type) {
                        "like" -> 2.5 * decay
                        "play_finish" -> 1.2 * decay
                        "play_start" -> 0.3 * decay
                        "skip" -> -1.0 * decay
                        "dislike_track" -> -3.0 * decay
                        else -> 0.0
                    }
                    bucketGenreRaw[g] = (bucketGenreRaw[g] ?: 0.0) + delta
                }
            }

            // Граф совместного прослушивания артистов в одной сессии (окно <= 20 мин)
            if (ev.type == "play_finish" && aKey.isNotEmpty()) {
                if (prevFinishArtist.isNotEmpty() && prevFinishArtist != aKey && (ev.ts - prevFinishTs) <= SESSION_GAP_MS) {
                    val w = 1.0 * decay
                    val m1 = pairCounts.getOrPut(prevFinishArtist) { HashMap() }
                    m1[aKey] = (m1[aKey] ?: 0.0) + w
                    val m2 = pairCounts.getOrPut(aKey) { HashMap() }
                    m2[prevFinishArtist] = (m2[prevFinishArtist] ?: 0.0) + w
                }
                prevFinishArtist = aKey
                prevFinishTs = ev.ts
            }
        }

        // Подмешиваем явные лайки из базы, даже если событие like было старше 90 дней
        val affinityMap = HashMap<String, TrackAffinity>(byKey.size * 2)
        val fatiguedIds = HashSet<String>()
        val coreIds = HashSet<String>()
        val risingIds = HashSet<String>()

        for ((tKey, acc) in byKey) {
            val isExplicitLiked = acc.trackId in likedIds || "ya_${acc.trackId}" in likedIds || tKey in likedKeys
            val finalScore = if (isExplicitLiked) maxOf(acc.score, 3.2) else acc.score
            val fatigued = acc.trailingSkips >= 2 && acc.recentSkips >= 2

            val info = TrackAffinity(
                trackId = acc.trackId,
                trackKey = tKey,
                score = finalScore,
                finishes = acc.finishes,
                recentSkips = acc.recentSkips,
                isFatigued = fatigued,
                lastPositiveTs = acc.lastPositiveTs,
            )
            if (acc.trackId.isNotBlank()) affinityMap[acc.trackId] = info
            affinityMap[tKey] = info

            if (fatigued && acc.trackId.isNotBlank()) fatiguedIds += acc.trackId
            if (!fatigued && finalScore >= 2.2 && acc.trackId.isNotBlank()) coreIds += acc.trackId
            if (!isExplicitLiked && !fatigued && acc.finishes in 1..2 && acc.recentSkips == 0 && acc.trackId.isNotBlank()) {
                risingIds += acc.trackId
            }
        }

        // Нормализуем граф соседей артистов в диапазон 0.0..1.0
        val relatedNormalized = HashMap<String, Map<String, Double>>(pairCounts.size)
        for ((artist, neighbors) in pairCounts) {
            val maxVal = neighbors.values.maxOrNull() ?: continue
            if (maxVal <= 0.0) continue
            relatedNormalized[artist] = neighbors
                .mapValues { (_, v) -> (v / maxVal).coerceIn(0.0, 1.0) }
                .filterValues { it >= 0.25 }
        }

        // Нормализуем жанровый множитель текущего времени суток (0.75..1.40)
        val bucketGenreMultipliers = bucketGenreRaw.mapValues { (_, raw) ->
            (1.0 + (raw * 0.08)).coerceIn(0.75, 1.40)
        }

        return TasteMap(
            generatedAtMs = nowMs,
            bucket = bucket,
            trackAffinities = affinityMap,
            fatiguedTrackIds = fatiguedIds,
            coreFavoriteIds = coreIds,
            risingGemIds = risingIds,
            relatedArtists = relatedNormalized,
            bucketGenreAffinity = bucketGenreMultipliers,
        )
    }

    companion object {
        private const val HALF_LIFE_DAYS = 21.0
        private const val SESSION_GAP_MS = 20L * 60L * 1000L
        private const val CACHE_TTL_MS = 10L * 60L * 1000L
    }
}
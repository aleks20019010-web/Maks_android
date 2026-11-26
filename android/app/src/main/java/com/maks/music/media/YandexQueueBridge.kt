package com.maks.music.media

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class YandexQueueBridge(
    val client: YandexMusicClient,
) {
    private val bufferMutex = Mutex()
    private val waveBuffer = ArrayDeque<YandexTrackMeta>()
    private val seenYandexIds = LinkedHashSet<String>()
    private var prefetchJob: Job? = null

    fun resetWaveSession() {
        prefetchJob?.cancel()
        prefetchJob = null
        synchronized(seenYandexIds) {
            seenYandexIds.clear()
        }
    }

    /**
     * ???????????? 5 ??????? ??????? (????????? ??? ?????????) ?????? ?? ?????
     * ??????? ??????? ???????. ??? ?????????? ???? ?????? ?????? ?? ??????.
     */
    fun ensureWavePrefetch(
        scope: CoroutineScope,
        stationId: String = "user:onyourwave",
        languageFilter: String? = null,
        currentQueue: () -> List<LocalTrack>,
        currentIndex: () -> Int,
        localTracksProvider: () -> List<LocalTrack>,
        blacklistedIdsProvider: () -> Set<String>,
        onInsertNextTrack: (insertAt: Int, track: LocalTrack) -> Unit,
    ) {
        if (!client.isConfigured() || client.isTemporarilyDown()) return
        if (prefetchJob?.isActive == true) return

        prefetchJob = scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    if (!client.isConfigured() || client.isTemporarilyDown()) break

                    val q = currentQueue()
                    val idx = currentIndex()
                    if (q.isEmpty() || idx < 0) break

                    // ???????, ??????? ?? ????????? PREFETCH_AHEAD ?????? ??? ????????? ??????? ?? ?????
                    val targetAhead = runCatching {
                        val xml = File(client.cacheDir.parentFile?.parentFile, "shared_prefs/maks_settings.xml")
                        if (xml.exists()) {
                            Regex("""name="yandex_prefetch_count" value="(\d+)"""")
                                .find(xml.readText())?.groupValues?.get(1)?.toIntOrNull()
                        } else null
                    }.getOrNull()?.coerceIn(1, 15) ?: PREFETCH_AHEAD
                    val lookaheadEnd = (idx + targetAhead).coerceAtMost(q.lastIndex)
                    var readyAhead = 0
                    for (i in (idx + 1)..lookaheadEnd) {
                        if (q[i].id.startsWith(YA_PREFIX) || q[i].displayName.startsWith(YA_LOCAL_MARKER)) {
                            readyAhead++
                        } else {
                            break
                        }
                    }
                    if (readyAhead >= targetAhead) break

                    val nextTrack = fetchAndPrepareNextWaveTrack(
                        stationId = stationId,
                        languageFilter = languageFilter,
                        localTracks = localTracksProvider(),
                        blacklistedIds = blacklistedIdsProvider(),
                    ) ?: break

                    val insertAt = (currentIndex() + 1 + readyAhead).coerceAtMost(currentQueue().size)
                    onInsertNextTrack(insertAt, nextTrack)
                }
            } catch (e: Exception) {
                Log.w(TAG, "ensureWavePrefetch error: ${e.message}")
            }
        }
    }

    /**
     * ??????-????? ??????, ???? ???????? ?????? ?? ???????.
     * ????????? ?????? ???? ?????, ? ????????? (?? 5) ????? ????????? ? ????.
     */
    suspend fun searchAndDownloadTracks(
        query: String,
        localTracks: List<LocalTrack>,
        limit: Int = PREFETCH_AHEAD,
    ): List<LocalTrack> {
        if (!client.isConfigured() || client.isTemporarilyDown()) return emptyList()
        val metas = client.searchTracks(query, limit = limit)
        if (metas.isEmpty()) return emptyList()

        val result = mutableListOf<LocalTrack>()
        for (meta in metas) {
            val resolved = resolveMetaToLocalTrack(meta, localTracks, markAsWaveLocal = false)
            if (resolved != null) {
                result.add(resolved)
                // ?????? ???? ?????????? ??????, ????????? ????? ????????
                if (result.size >= 1) break
            }
        }
        return result
    }

    private fun matchesLanguage(track: LocalTrack, languageFilter: String?): Boolean = when (languageFilter) {
        "russian" -> track.isRussian
        "not-russian" -> !track.isRussian
        else -> true
    }

    private suspend fun fetchAndPrepareNextWaveTrack(
        stationId: String,
        languageFilter: String?,
        localTracks: List<LocalTrack>,
        blacklistedIds: Set<String>,
    ): LocalTrack? {
        val attempts = if (languageFilter != null) 14 else 6
        repeat(attempts) {
            val meta = popNextWaveMeta(stationId) ?: return null
            val yaId = "$YA_PREFIX${meta.id}"
            if (yaId in blacklistedIds) return@repeat

            val resolved = resolveMetaToLocalTrack(meta, localTracks, markAsWaveLocal = true)
            if (resolved != null && resolved.id !in blacklistedIds && matchesLanguage(resolved, languageFilter)) {
                return resolved
            }
        }
        return null
    }

    private suspend fun popNextWaveMeta(stationId: String): YandexTrackMeta? = bufferMutex.withLock {
        if (waveBuffer.isEmpty()) {
            val fetched = client.fetchWaveTracks(stationId)
            for (m in fetched) {
                val isNew = synchronized(seenYandexIds) { seenYandexIds.add(m.id) }
                if (isNew) waveBuffer.addLast(m)
            }
        }
        waveBuffer.removeFirstOrNull()
    }

    private suspend fun resolveMetaToLocalTrack(
        meta: YandexTrackMeta,
        localTracks: List<LocalTrack>,
        markAsWaveLocal: Boolean,
    ): LocalTrack? {
        // 1. ??????? ???? ????? ??? ????????? ????????? ??????
        val matchedLocal = matchLocalTrack(meta, localTracks)
        if (matchedLocal != null) {
            Log.d(TAG, "Wave matched local: ${matchedLocal.artist} - ${matchedLocal.title}")
            return if (markAsWaveLocal) {
                matchedLocal.copy(displayName = "$YA_LOCAL_MARKER${matchedLocal.displayName}")
            } else {
                matchedLocal
            }
        }

        // 2. ???? ???????? ??? ? ?????? ? yandex_cache/
        val file: File = client.downloadTrack(meta) ?: return null
        return LocalTrack(
            id = "$YA_PREFIX${meta.id}",
            contentUri = Uri.fromFile(file),
            artist = meta.artist.ifBlank { "?????? ??????" },
            title = meta.title.ifBlank { file.nameWithoutExtension },
            album = meta.album,
            genre = "yandex",
            durationMs = meta.durationMs,
            albumId = 0L,
            displayName = file.name,
        )
    }

    private fun matchLocalTrack(meta: YandexTrackMeta, localTracks: List<LocalTrack>): LocalTrack? {
        val targetTitle = normalizeKey(meta.title)
        val targetArtist = normalizeKey(meta.artist)
        if (targetTitle.isBlank()) return null

        return localTracks.firstOrNull { local ->
            val lTitle = normalizeKey(local.title)
            val lArtist = normalizeKey(local.artist)
            lTitle == targetTitle && (
                targetArtist.isBlank() ||
                    lArtist == targetArtist ||
                    lArtist.contains(targetArtist) ||
                    targetArtist.contains(lArtist)
            )
        }
    }

    private fun normalizeKey(s: String): String =
        s.lowercase()
            .replace(Regex("""[^a-z\u0430-\u044f\u04510-9 ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    companion object {
        private const val TAG = "YandexQueueBridge"
        const val PREFETCH_AHEAD = 5
        private const val YA_PREFIX = "ya_"
        private const val YA_LOCAL_MARKER = "[ya_local]"
    }
}

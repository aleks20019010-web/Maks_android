package com.maks.music.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.maks.music.core.AssetProvider
import com.maks.music.core.Phonetic
import com.maks.music.core.nlu.LocalCatalogLookup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Сканер локальной музыки:
 *   1. Android MediaStore.Audio.Media (находит всю музыку в памяти и на SD-карте, включая Download)
 *   2. Выбранные пользователем папки через Storage Access Framework (SAF / OpenDocumentTree)
 *   3. Авто-разбор имён файлов вида «Артист - Название.mp3», если ID3-теги не заполнены.
 */
class LocalLibraryScanner(
    private val context: Context,
    assets: AssetProvider,
) : LocalCatalogLookup {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var tracks: List<LocalTrack> = emptyList()

    @Volatile
    private var artistsMap: Map<String, String> = emptyMap()

    @Volatile
    private var tracksMap: Map<String, String> = emptyMap()

    private val genreTagsMap: Map<String, List<String>> = runCatching {
        val raw = assets.readText("config/genre_tags.json") ?: return@runCatching emptyMap()
        Json.parseToJsonElement(raw).jsonObject.mapValues { (_, v) ->
            v.jsonArray.map { it.jsonPrimitive.content.lowercase() }
        }.mapKeys { it.key.lowercase() }
    }.getOrDefault(emptyMap())

    /**
     * Группы синонимов артистов из config/aliases.json:
     * ключ (canonical lower или alias lower) → множество всех известных вариантов имени артиста.
     * Позволяет находить MP3 с тегом «Виктор Цой» при запросе «КИНО» и «КиШ» при запросе «Король и Шут».
     */
    private val artistSynonymGroups: Map<String, Set<String>> = runCatching {
        val raw = assets.readText("config/aliases.json") ?: return@runCatching emptyMap()
        val obj = Json.parseToJsonElement(raw).jsonObject
        val byCanonical = LinkedHashMap<String, MutableSet<String>>()
        for ((k, v) in obj) {
            val alias = k.lowercase().trim()
            val canonical = runCatching {
                v.jsonObject["artist_name"]?.jsonPrimitive?.content
            }.getOrNull() ?: runCatching {
                v.jsonPrimitive.content
            }.getOrNull() ?: continue
            val canonLow = canonical.lowercase().trim()
            if (canonLow.isEmpty()) continue
            val bucket = byCanonical.getOrPut(canonLow) { linkedSetOf(canonLow) }
            if (alias.length >= 3) bucket.add(alias)
        }
        buildMap<String, Set<String>> {
            for ((canonLow, group) in byCanonical) {
                put(canonLow, group)
                for (alias in group) {
                    putIfAbsent(alias, group)
                }
            }
        }
    }.getOrDefault(emptyMap())

    fun allTracks(): List<LocalTrack> = tracks

    override fun artistIndex(): Map<String, String> = artistsMap
    override fun trackIndex(): Map<String, String> = tracksMap

    fun savedFolderUris(): Set<String> =
        prefs.getStringSet(KEY_FOLDERS, emptySet())?.toSet() ?: emptySet()

    fun addMusicFolder(treeUri: Uri) {
        val current = savedFolderUris().toMutableSet()
        current.add(treeUri.toString())
        prefs.edit().putStringSet(KEY_FOLDERS, current).apply()
    }

    /** Полное пересканирование аудиофайлов на устройстве (вызывать в Dispatchers.IO). */
    fun scan(): List<LocalTrack> {
        val result = mutableListOf<LocalTrack>()
        val seenKeys = HashSet<String>()

        scanMediaStore(result, seenKeys)
        scanCustomFolders(result, seenKeys)

        result.sortWith(compareBy<LocalTrack>({ it.artist.lowercase() }, { it.title.lowercase() }))
        rebuildIndexes(result)
        return result
    }

    private fun scanMediaStore(result: MutableList<LocalTrack>, seenKeys: MutableSet<String>) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.IS_ALARM,
            MediaStore.Audio.Media.IS_NOTIFICATION,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.GENRE)
            }
        }.toTypedArray()

        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.ARTIST} ASC, ${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val isMusicCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
                val isRingtoneCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_RINGTONE)
                val isAlarmCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_ALARM)
                val isNotifCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_NOTIFICATION)
                val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else -1
                val genreCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
                } else -1

                if (idCol < 0) return@use

                while (cursor.moveToNext()) {
                    val isRingtone = isRingtoneCol >= 0 && cursor.getInt(isRingtoneCol) != 0
                    val isAlarm = isAlarmCol >= 0 && cursor.getInt(isAlarmCol) != 0
                    val isNotif = isNotifCol >= 0 && cursor.getInt(isNotifCol) != 0
                    if (isRingtone || isAlarm || isNotif) continue

                    val displayName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    if (displayName.contains("_tts", ignoreCase = true)) continue

                    val relPath = if (relPathCol >= 0) cursor.getString(relPathCol).orEmpty().lowercase() else ""
                    if (IGNORED_PATH_HINTS.any { relPath.contains(it) }) continue

                    val duration = if (durCol >= 0) cursor.getLong(durCol) else 0L
                    val isMusic = isMusicCol < 0 || cursor.getInt(isMusicCol) != 0
                    // Отсеиваем короткие звуки (< 15 сек), но пропускаем треки с duration=0 если это аудио-файл
                    if (duration in 1L until 15_000L) continue
                    if (!isMusic && !hasAudioExtension(displayName)) continue

                    val id = cursor.getLong(idCol)
                    val rawArtist = if (artistCol >= 0) cursor.getString(artistCol).orEmpty().trim() else ""
                    val rawTitle = if (titleCol >= 0) cursor.getString(titleCol).orEmpty().trim() else ""
                    val (artist, title) = resolveArtistAndTitle(rawArtist, rawTitle, displayName)

                    val album = if (albumCol >= 0) cursor.getString(albumCol).orEmpty().trim() else ""
                    val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else 0L
                    val genre = if (genreCol >= 0) cursor.getString(genreCol).orEmpty().trim().lowercase() else ""

                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val dedupeKey = "${displayName.lowercase()}|${duration / 1000L}"
                    if (displayName.isNotBlank() && !seenKeys.add(dedupeKey)) continue

                    result += LocalTrack(
                        id = id.toString(),
                        contentUri = uri,
                        artist = artist,
                        title = title,
                        album = album,
                        genre = genre,
                        durationMs = duration,
                        albumId = albumId,
                        displayName = displayName,
                    )
                }
            }
        }
    }

    private fun scanCustomFolders(result: MutableList<LocalTrack>, seenKeys: MutableSet<String>) {
        val folders = savedFolderUris()
        if (folders.isEmpty()) return
        for (treeUriStr in folders) {
            runCatching {
                val treeUri = Uri.parse(treeUriStr)
                val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                scanDocumentFolderRecursive(treeUri, rootDocId, depth = 0, result = result, seenKeys = seenKeys)
            }
        }
    }

    private fun scanDocumentFolderRecursive(
        treeUri: Uri,
        parentDocId: String,
        depth: Int,
        result: MutableList<LocalTrack>,
        seenKeys: MutableSet<String>,
    ) {
        if (depth > 5 || result.size >= 4000) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idCol < 0) return@use

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol) ?: continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) {
                            scanDocumentFolderRecursive(treeUri, docId, depth + 1, result, seenKeys)
                        }
                        continue
                    }

                    val isAudio = mime.startsWith("audio/") || hasAudioExtension(name)
                    if (!isAudio || name.contains("_tts", ignoreCase = true)) continue

                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    var rawArtist = ""
                    var rawTitle = ""
                    var album = ""
                    var genre = ""
                    var duration = 0L

                    val retriever = MediaMetadataRetriever()
                    runCatching {
                        retriever.setDataSource(context, docUri)
                        rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim()
                        rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
                        album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim()
                        genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty().trim().lowercase()
                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    }
                    runCatching { retriever.release() }

                    if (duration in 1L until 15_000L) continue
                    val dedupeKey = "${name.lowercase()}|${duration / 1000L}"
                    if (name.isNotBlank() && !seenKeys.add(dedupeKey)) continue

                    val (artist, title) = resolveArtistAndTitle(rawArtist, rawTitle, name)
                    result += LocalTrack(
                        id = "saf_${docUri.toString().hashCode()}",
                        contentUri = docUri,
                        artist = artist,
                        title = title,
                        album = album,
                        genre = genre,
                        durationMs = duration,
                        albumId = 0L,
                        displayName = name,
                    )
                }
            }
        }
    }

    private fun resolveArtistAndTitle(rawArtist: String, rawTitle: String, displayName: String): Pair<String, String> {
        var artist = if (rawArtist.equals("<unknown>", ignoreCase = true)) "" else rawArtist.trimStart('@').trim()
        var title = rawTitle.trim()

        val cleanedFile = cleanFileName(displayName)
        if (artist.isBlank() && cleanedFile.isNotBlank()) {
            val sep = listOf(" — ", " - ", " – ").firstOrNull { cleanedFile.contains(it) }
            if (sep != null) {
                val parts = cleanedFile.split(sep, limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    artist = parts[0].trim()
                    if (title.isBlank() || title.equals(cleanedFile, ignoreCase = true)) {
                        title = parts[1].trim()
                    }
                }
            }
        }
        if (title.isBlank()) title = cleanedFile.ifBlank { "Без названия" }
        return artist to title
    }

    private fun hasAudioExtension(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    private companion object {
        const val PREFS_NAME = "maks_music_library_prefs"
        const val KEY_FOLDERS = "saf_folder_uris"
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "ogg", "wav", "opus", "aac", "wma", "alac")
        val IGNORED_PATH_HINTS = listOf(
            "ringtones/", "notifications/", "alarms/",
            "whatsapp voice notes/", "telegram audio/", "call recordings/",
            "recordings/", "voice recorder/"
        )
    }

    private fun rebuildIndexes(list: List<LocalTrack>) {
        val aMap = LinkedHashMap<String, String>()
        val tMap = LinkedHashMap<String, String>()

        for (t in list) {
            if (t.artist.isNotBlank()) {
                val al = t.artist.lowercase()
                aMap.putIfAbsent(al, t.artist)
                if (t.phonArtist.isNotBlank()) aMap.putIfAbsent(t.phonArtist, t.artist)
            }
            if (t.title.length >= 3) {
                val tl = t.title.lowercase()
                val full = t.label
                tMap.putIfAbsent(tl, full)
                if (t.phonTitle.length >= 3) tMap.putIfAbsent(t.phonTitle, full)
            }
        }

        tracks = list
        artistsMap = aMap
        tracksMap = tMap
    }

    fun findByArtist(artistQuery: String, blacklistedTrackIds: Set<String>): List<LocalTrack> {
        val qLow = artistQuery.lowercase().trim()
        val qPhon = Phonetic.phon(qLow)
        val exact = tracks.filter {
            it.id !in blacklistedTrackIds && (
                it.artist.equals(qLow, ignoreCase = true) ||
                it.phonArtist == qPhon ||
                it.artist.lowercase().contains(qLow)
            )
        }
        if (exact.isNotEmpty()) return exact.shuffled()

        // Проверяем синонимы из вшитого config/aliases.json (например, «КИНО» ↔ «Виктор Цой», «Король и Шут» ↔ «КиШ»)
        val synonyms = artistSynonymGroups[qLow]
        if (!synonyms.isNullOrEmpty()) {
            val synPhons = synonyms.map { Phonetic.phon(it) }.toSet()
            val synMatches = tracks.filter { t ->
                t.id !in blacklistedTrackIds && t.artist.isNotBlank() && (
                    t.artist.lowercase() in synonyms ||
                    t.phonArtist in synPhons ||
                    synonyms.any { s -> s.length >= 4 && t.artist.lowercase().contains(s) }
                )
            }
            if (synMatches.isNotEmpty()) return synMatches.shuffled()
        }

        // Fuzzy fallback (аналог search_local_artist_tracks в workers/music/local.py)
        return tracks.filter { t ->
            t.id !in blacklistedTrackIds && t.artist.isNotBlank() &&
                maxOf(
                    Phonetic.tokenSetRatio(qLow, t.artist),
                    Phonetic.ratio(qPhon, t.phonArtist)
                ) >= 0.78
        }.shuffled()
    }

    fun findByGenre(genreIdOrName: String, blacklistedTrackIds: Set<String>): List<LocalTrack> {
        val clean = genreIdOrName.lowercase().removePrefix("genre:").trim()
        val tagHints = mutableSetOf(clean)
        genreTagsMap[clean]?.let { tagHints.addAll(it) }
        for ((alias, tags) in genreTagsMap) {
            if (clean in tags || alias == clean) {
                tagHints.addAll(tags)
            }
        }

        val matches = tracks.filter { t ->
            t.id !in blacklistedTrackIds && (
                tagHints.any { h -> t.genre.contains(h) || t.album.lowercase().contains(h) }
            )
        }
        if (matches.isNotEmpty()) return matches.shuffled()

        // Если теги жанров в MP3 не заполнены — фильтруем по русскоязычности для rusrock/rusestrada
        if (clean.startsWith("rus") || clean == "shanson") {
            return tracks.filter { it.id !in blacklistedTrackIds && it.isRussian }.shuffled()
        }
        return emptyList()
    }

    fun findByMood(moodName: String, blacklistedTrackIds: Set<String>): List<LocalTrack> {
        val m = moodName.lowercase()
        val genreHints = when {
            "энерг" in m || "бодр" in m || "агресс" in m -> listOf("rock", "metal", "punk", "dnb", "dance", "hard")
            "спокой" in m || "расслаб" in m || "мечтат" in m -> listOf("classical", "jazz", "ambient", "indie", "acoustic", "newage")
            "груст" in m || "мрач" in m || "сентим" in m -> listOf("blues", "post", "ballad", "indie", "rock")
            "радост" in m || "весел" in m -> listOf("pop", "dance", "ska", "funk", "disco")
            else -> emptyList()
        }
        if (genreHints.isEmpty()) return emptyList()
        return tracks.filter { t ->
            t.id !in blacklistedTrackIds && genreHints.any { h -> t.genre.contains(h) }
        }.shuffled()
    }

    /** Универсальный локальный поиск: точный → фонетика → fuzzy по artist + title. */
    fun searchLocal(query: String, blacklistedTrackIds: Set<String>): List<LocalTrack> {
        val qLow = Phonetic.cleanKey(query)
        if (qLow.isEmpty()) return emptyList()
        val qPhon = Phonetic.phon(qLow)

        val scored = tracks.asSequence()
            .filter { it.id !in blacklistedTrackIds }
            .mapNotNull { t ->
                val combined = Phonetic.cleanKey("${t.artist} ${t.title}")
                val exactBoost = when {
                    t.title.lowercase() == qLow || t.artist.lowercase() == qLow -> 1.0
                    combined.contains(qLow) || t.phonTitle == qPhon || t.phonArtist == qPhon -> 0.94
                    else -> maxOf(
                        Phonetic.tokenSetRatio(qLow, combined),
                        Phonetic.ratio(qPhon, t.phonArtist),
                        Phonetic.ratio(qPhon, t.phonTitle),
                    )
                }
                if (exactBoost >= 0.70) t to exactBoost else null
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()

        return scored
    }

    /** Извлечь встроенную обложку из локального MP3/FLAC/M4A. */
    fun loadCoverArt(track: LocalTrack): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, track.contentUri)
            val bytes = retriever.embeddedPicture
            if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun cleanFileName(name: String): String =
        name.substringBeforeLast('.').replace('_', ' ').trim()
}

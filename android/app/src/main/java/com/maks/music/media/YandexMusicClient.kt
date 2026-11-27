package com.maks.music.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class YandexTrackMeta(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val coverUri: String = "",
    val genre: String = "",
)

class YandexMusicClient(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val settingsPrefs = appContext.getSharedPreferences("maks_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val cacheDir: File = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "yandex_cache").apply {
        mkdirs()
    }

    @Volatile private var failCount = 0
    @Volatile private var blockedUntilMs = 0L

    fun getToken(): String = prefs.getString(KEY_TOKEN, "")?.trim().orEmpty()

    fun setToken(token: String) {
        val clean = token.trim()
        prefs.edit().putString(KEY_TOKEN, clean).apply()
        settingsPrefs.edit().putString("yandex_token", clean).apply()
        resetCircuitBreaker()
    }

    fun isNetworkAllowed(): Boolean {
        if (!settingsPrefs.getBoolean("yandex_wifi_only", false)) return true
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun getCacheStats(): Pair<Int, Long> {
        val files = cacheDir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp3") }.orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    fun clearCache(): Int {
        var deleted = 0
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.delete()) deleted++
        }
        return deleted
    }

    fun enforceCacheLimit() {
        val limitMb = settingsPrefs.getInt("yandex_cache_limit_mb", 0)
        if (limitMb <= 0) return
        val maxBytes = limitMb * 1024L * 1024L
        val files = cacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".mp3") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            val len = file.length()
            if (file.delete()) total -= len
        }
    }

    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(IllegalStateException("Токен не задан"))
        resetCircuitBreaker()
        val body = httpGet("$BASE_URL/account/status")
            ?: return@withContext Result.failure(IllegalStateException("Нет ответа от сервера Яндекса"))
        runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val account = root["result"]?.jsonObject?.get("account")?.jsonObject
            val login = account?.get("displayName")?.jsonPrimitive?.content
                ?: account?.get("login")?.jsonPrimitive?.content
                ?: "Неизвестный пользователь"
            login
        }
    }

    fun isConfigured(): Boolean = getToken().isNotEmpty()

    fun isTemporarilyDown(): Boolean = System.currentTimeMillis() < blockedUntilMs

    fun resetCircuitBreaker() {
        failCount = 0
        blockedUntilMs = 0L
    }

    private fun markFail(reason: String) {
        failCount++
        if (failCount >= MAX_FAILS) {
            blockedUntilMs = System.currentTimeMillis() + BLOCK_DURATION_MS
            Log.w(TAG, "Yandex API: $failCount fails ($reason) -> paused for 5 min")
        } else {
            Log.w(TAG, "Yandex API fail $failCount/$MAX_FAILS: $reason")
        }
    }

    private fun markOk() {
        if (failCount > 0 || blockedUntilMs > 0L) {
            Log.i(TAG, "Yandex API available again")
        }
        failCount = 0
        blockedUntilMs = 0L
    }

    fun cachedFileFor(meta: YandexTrackMeta): File {
        val safeArtist = sanitizeFileName(meta.artist.ifBlank { "Unknown" })
        val safeTitle = sanitizeFileName(meta.title.ifBlank { meta.id })
        return File(cacheDir, "$safeArtist - $safeTitle [${meta.id}].mp3")
    }

    data class LocalIndexEntry(
        val trackId: String,
        val artist: String,
        val title: String,
        val album: String,
        val fileName: String,
        val docUri: String = "",
        val sizeBytes: Long = 0L,
    )

    private val indexLock = Any()
    private val localIndexMap = LinkedHashMap<String, LocalIndexEntry>()

    private fun normalizeSearchKey(raw: String): String =
        raw.lowercase()
            .replace('\u0451', '\u0435')
            .replace(Regex("[^a-zA-Zа-яА-Я0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun internalIndexFile(): File = File(appContext.filesDir, "local_index.json")

    private fun fallbackPublicIndexFile(): File {
        val baseDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_MUSIC
        )
        return File(File(baseDir, "Maks"), "local_index.json")
    }

    private fun findSelectedFolderIndexUri(
        resolver: android.content.ContentResolver,
        treeUri: android.net.Uri,
        rootDocUri: android.net.Uri,
    ): android.net.Uri? {
        val rootDocId = android.provider.DocumentsContract.getDocumentId(rootDocUri)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        return runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    if (name == "local_index.json" && idIdx >= 0) {
                        val docId = cursor.getString(idIdx)
                        if (!docId.isNullOrBlank()) {
                            return@use android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        }
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun serializeIndexJson(entries: Collection<LocalIndexEntry>): String {
        val arr = org.json.JSONArray()
        for (e in entries) {
            val obj = org.json.JSONObject().apply {
                put("trackId", e.trackId)
                put("artist", e.artist)
                put("title", e.title)
                put("album", e.album)
                put("fileName", e.fileName)
                put("docUri", e.docUri)
                put("sizeBytes", e.sizeBytes)
            }
            arr.put(obj)
        }
        return arr.toString(2)
    }

    private fun parseIndexJson(raw: String): List<LocalIndexEntry> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            val result = ArrayList<LocalIndexEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("trackId").trim()
                if (id.isEmpty()) continue
                result += LocalIndexEntry(
                    trackId = id,
                    artist = obj.optString("artist"),
                    title = obj.optString("title"),
                    album = obj.optString("album"),
                    fileName = obj.optString("fileName"),
                    docUri = obj.optString("docUri"),
                    sizeBytes = obj.optLong("sizeBytes", 0L),
                )
            }
            result
        }.getOrDefault(emptyList())
    }

    fun reloadLocalIndexFromFile(): List<LocalIndexEntry> {
        synchronized(indexLock) {
            var loadedEntries: List<LocalIndexEntry> = emptyList()

            val internal = internalIndexFile()
            if (internal.exists() && internal.isFile) {
                loadedEntries = parseIndexJson(runCatching { internal.readText(Charsets.UTF_8) }.getOrDefault(""))
            }

            if (loadedEntries.isEmpty()) {
                val uriStr = settingsPrefs.getString("yandex_download_dir_uri", "").orEmpty().trim()
                if (uriStr.isNotBlank()) {
                    runCatching {
                        val treeUri = android.net.Uri.parse(uriStr)
                        val rootDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                        val rootDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
                        val resolver = appContext.contentResolver
                        val indexUri = findSelectedFolderIndexUri(resolver, treeUri, rootDocUri)
                        if (indexUri != null) {
                            val text = resolver.openInputStream(indexUri)?.use { inp ->
                                inp.bufferedReader(Charsets.UTF_8).readText()
                            }.orEmpty()
                            loadedEntries = parseIndexJson(text)
                        }
                    }
                }
            }

            if (loadedEntries.isEmpty()) {
                val fb = fallbackPublicIndexFile()
                if (fb.exists() && fb.isFile) {
                    loadedEntries = parseIndexJson(runCatching { fb.readText(Charsets.UTF_8) }.getOrDefault(""))
                }
            }

            localIndexMap.clear()
            for (entry in loadedEntries) {
                localIndexMap[entry.trackId] = entry
            }
            return loadedEntries
        }
    }

    fun isTrackInLocalIndex(meta: YandexTrackMeta): Boolean {
        reloadLocalIndexFromFile()
        val entries = synchronized(indexLock) { localIndexMap.values.toList() }
        if (entries.isEmpty()) return false

        if (entries.any { it.trackId == meta.id }) return true

        val normTitle = normalizeSearchKey(meta.title)
        val normArtist = normalizeSearchKey(meta.artist)
        if (normTitle.isBlank()) return false

        return entries.any { entry ->
            val eTitle = normalizeSearchKey(entry.title)
            val eArtist = normalizeSearchKey(entry.artist)
            val eFile = normalizeSearchKey(entry.fileName)
            val titleMatch = eTitle == normTitle || eTitle.contains(normTitle) || eFile.contains(normTitle)
            val artistMatch = normArtist.isBlank() || eArtist.contains(normArtist) || eFile.contains(normArtist)
            titleMatch && artistMatch
        }
    }

    private fun entryToLocalTrack(entry: LocalIndexEntry): LocalTrack? {
        val uri = when {
            entry.docUri.isNotBlank() -> android.net.Uri.parse(entry.docUri)
            else -> {
                val localFile = findCachedFile(entry.trackId) ?: return null
                android.net.Uri.fromFile(localFile)
            }
        }
        return LocalTrack(
            id = entry.trackId.hashCode().toLong().coerceAtLeast(1L),
            title = entry.title.ifBlank { entry.fileName.substringBeforeLast('.') },
            artist = entry.artist.ifBlank { "Неизвестный исполнитель" },
            album = entry.album,
            durationMs = 0L,
            contentUri = uri,
            dataPath = entry.fileName,
        )
    }

    fun findLocalTracksByArtist(artistQuery: String): List<LocalTrack> {
        val normArtist = normalizeSearchKey(artistQuery)
        if (normArtist.isBlank()) return emptyList()
        val entries = reloadLocalIndexFromFile()
        return entries.mapNotNull { entry ->
            val eArtist = normalizeSearchKey(entry.artist)
            val eFile = normalizeSearchKey(entry.fileName)
            if (eArtist == normArtist || eArtist.contains(normArtist) || eFile.contains(normArtist)) {
                entryToLocalTrack(entry)
            } else {
                null
            }
        }
    }

    fun findLocalTracksByQuery(query: String): List<LocalTrack> {
        val words = normalizeSearchKey(query).split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val entries = reloadLocalIndexFromFile()
        return entries.mapNotNull { entry ->
            val hay = normalizeSearchKey("${entry.artist} ${entry.title} ${entry.fileName}")
            if (words.all { w -> hay.contains(w) }) {
                entryToLocalTrack(entry)
            } else {
                null
            }
        }
    }

    private fun persistLocalIndexLocked() {
        val jsonText = serializeIndexJson(localIndexMap.values)
        runCatching {
            internalIndexFile().writeText(jsonText, Charsets.UTF_8)
        }
        val uriStr = settingsPrefs.getString("yandex_download_dir_uri", "").orEmpty().trim()
        if (uriStr.isNotBlank()) {
            runCatching {
                val treeUri = android.net.Uri.parse(uriStr)
                val rootDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                val rootDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
                val resolver = appContext.contentResolver
                val existingUri = findSelectedFolderIndexUri(resolver, treeUri, rootDocUri)
                    ?: android.provider.DocumentsContract.createDocument(
                        resolver,
                        rootDocUri,
                        "application/json",
                        "local_index.json",
                    )
                if (existingUri != null) {
                    resolver.openOutputStream(existingUri, "wt")?.use { out ->
                        out.write(jsonText.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
        runCatching {
            val fb = fallbackPublicIndexFile()
            fb.parentFile?.mkdirs()
            fb.writeText(jsonText, Charsets.UTF_8)
        }
    }

    fun recordTrackInLocalIndex(meta: YandexTrackMeta, fileName: String, docUri: String = "", sizeBytes: Long = 0L) {
        synchronized(indexLock) {
            localIndexMap[meta.id] = LocalIndexEntry(
                trackId = meta.id,
                artist = meta.artist,
                title = meta.title,
                album = meta.album,
                fileName = fileName,
                docUri = docUri,
                sizeBytes = sizeBytes,
            )
            persistLocalIndexLocked()
        }
    }

    fun findCachedFile(trackId: String): File? {
        val suffix = "[$trackId].mp3"
        cacheDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(suffix) && it.length() > 16_384 }?.let {
            return it
        }
        reloadLocalIndexFromFile()
        val indexed = synchronized(indexLock) { localIndexMap[trackId] }
        if (indexed != null && indexed.docUri.isNotBlank()) {
            val restored = restoreFromDocUri(trackId, android.net.Uri.parse(indexed.docUri), indexed.fileName)
            if (restored != null) return restored
        }
        return restoreFromSelectedFolder(trackId)
    }

    private fun restoreFromDocUri(trackId: String, docUri: android.net.Uri, preferredName: String): File? {
        val suffix = "[$trackId].mp3"
        return runCatching {
            val resolver = appContext.contentResolver
            val cleanName = preferredName.ifBlank {
                queryDisplayName(resolver, docUri)?.takeIf { it.isNotBlank() } ?: "yandex_track_$suffix"
            }.let { if (it.endsWith(".mp3", ignoreCase = true)) it else "$it.mp3" }
            val outFile = File(cacheDir, sanitizeFileName(cleanName).ifBlank { "track_$suffix" })
            val tmpFile = File(cacheDir, "${outFile.name}.restore_tmp")
            resolver.openInputStream(docUri)?.use { inp ->
                tmpFile.outputStream().use { out -> inp.copyTo(out, bufferSize = 16 * 1024) }
            } ?: return@runCatching null
            if (tmpFile.length() < 16_384L) {
                tmpFile.delete()
                return@runCatching null
            }
            if (outFile.exists()) outFile.delete()
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.copyTo(outFile, overwrite = true)
                tmpFile.delete()
            }
            outFile.takeIf { it.exists() && it.length() > 16_384L }
        }.getOrNull()
    }

    private fun restoreFromSelectedFolder(trackId: String): File? {
        val uriStr = settingsPrefs.getString("yandex_download_dir_uri", "").orEmpty().trim()
        if (uriStr.isBlank() || trackId.isBlank()) return null
        val suffix = "[$trackId].mp3"
        return runCatching {
            val treeUri = android.net.Uri.parse(uriStr)
            val rootDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            val resolver = appContext.contentResolver
            val matchedUri = findTrackUriRecursive(
                resolver = resolver,
                treeUri = treeUri,
                parentDocId = rootDocId,
                suffix = suffix,
                depth = 0,
                maxDepth = 3,
            ) ?: return@runCatching null

            val displayName = queryDisplayName(resolver, matchedUri)
                ?.takeIf { it.isNotBlank() }
                ?.let { if (it.endsWith(".mp3", ignoreCase = true)) it else "$it.mp3" }
                ?: "yandex_track_$suffix"

            val outFile = File(cacheDir, sanitizeFileName(displayName).ifBlank { "track_$suffix" })
            val tmpFile = File(cacheDir, "${outFile.name}.restore_tmp")
            resolver.openInputStream(matchedUri)?.use { inp ->
                tmpFile.outputStream().use { out ->
                    inp.copyTo(out, bufferSize = 16 * 1024)
                }
            } ?: return@runCatching null

            if (tmpFile.length() < 16_384L) {
                tmpFile.delete()
                return@runCatching null
            }
            if (outFile.exists()) outFile.delete()
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.copyTo(outFile, overwrite = true)
                tmpFile.delete()
            }
            outFile.takeIf { it.exists() && it.length() > 16_384L }
        }.getOrNull()
    }

    private fun findTrackUriRecursive(
        resolver: android.content.ContentResolver,
        treeUri: android.net.Uri,
        parentDocId: String,
        suffix: String,
        depth: Int,
        maxDepth: Int,
    ): android.net.Uri? {
        if (depth > maxDepth) return null
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val subDirs = mutableListOf<String>()
        resolver.query(
            childrenUri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                android.provider.DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val docId = if (idIdx >= 0) cursor.getString(idIdx) else null
                if (docId.isNullOrBlank()) continue
                val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < maxDepth) subDirs += docId
                } else {
                    val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 17_000L
                    if (name.endsWith(suffix, ignoreCase = true) && size > 16_384L) {
                        return android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    }
                }
            }
        }
        for (dirId in subDirs) {
            val found = findTrackUriRecursive(resolver, treeUri, dirId, suffix, depth + 1, maxDepth)
            if (found != null) return found
        }
        return null
    }

    private fun queryDisplayName(
        resolver: android.content.ContentResolver,
        docUri: android.net.Uri,
    ): String? = runCatching {
        resolver.query(
            docUri,
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        }
    }.getOrNull()

    suspend fun fetchGenreTracks(genreSlug: String): List<YandexTrackMeta> {
        val clean = genreSlug.trim().lowercase().removePrefix("genre:")
        if (clean.isBlank()) return emptyList()
        val stationTracks = fetchWaveTracks("genre:$clean")
        if (stationTracks.isNotEmpty()) {
            return stationTracks.map { if (it.genre.isBlank()) it.copy(genre = clean) else it }
        }
        return searchTracks(clean, limit = 12).map {
            if (it.genre.isBlank()) it.copy(genre = clean) else it
        }
    }

    suspend fun fetchWaveTracks(stationId: String = "user:onyourwave"): List<YandexTrackMeta> =
        withContext(Dispatchers.IO) {
            if (!isConfigured() || isTemporarilyDown() || !isNetworkAllowed()) return@withContext emptyList()
            val body = httpGet("$BASE_URL/rotor/station/$stationId/tracks") ?: return@withContext emptyList()
            runCatching {
                val root = json.parseToJsonElement(body).jsonObject
                val seq = root["result"]?.jsonObject?.get("sequence")?.jsonArray ?: return@runCatching emptyList()
                seq.mapNotNull { item ->
                    item.jsonObject["track"]?.jsonObject?.let { parseTrack(it) }
                }
            }.onFailure { Log.w(TAG, "fetchWaveTracks parse error: ${it.message}") }
                .getOrDefault(emptyList())
        }

    suspend fun searchTracks(query: String, limit: Int = 5): List<YandexTrackMeta> =
        withContext(Dispatchers.IO) {
            if (query.isBlank() || !isConfigured() || isTemporarilyDown()) return@withContext emptyList()
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val body = httpGet("$BASE_URL/search?text=$encoded&type=track&page=0") ?: return@withContext emptyList()
            runCatching {
                val root = json.parseToJsonElement(body).jsonObject
                val results = root["result"]?.jsonObject
                    ?.get("tracks")?.jsonObject
                    ?.get("results")?.jsonArray ?: return@runCatching emptyList()
                results.take(limit).mapNotNull { parseTrack(it.jsonObject) }
            }.onFailure { Log.w(TAG, "searchTracks parse error: ${it.message}") }
                .getOrDefault(emptyList())
        }

    suspend fun downloadTrack(meta: YandexTrackMeta): File? = withContext(Dispatchers.IO) {
        val existing = findCachedFile(meta.id) ?: cachedFileFor(meta).takeIf { it.exists() && it.length() > 16_384 }
        if (existing != null) return@withContext existing

        if (isTrackInLocalIndex(meta)) {
            Log.d(TAG, "Skip download, already in local_index.json: ${meta.artist} - ${meta.title}")
            return@withContext null
        }

        if (!isConfigured() || isTemporarilyDown() || !isNetworkAllowed()) return@withContext null

        val directUrl = resolveDirectDownloadUrl(meta.id) ?: return@withContext null
        val target = cachedFileFor(meta)
        val tmp = File(cacheDir, "${target.name}.tmp")

        return@withContext runCatching {
            val conn = (URL(directUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (conn.responseCode !in 200..299) {
                    markFail("download HTTP ${conn.responseCode}")
                    return@runCatching null
                }
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 16 * 1024)
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (tmp.length() < 16_384) {
                tmp.delete()
                markFail("downloaded file too small")
                return@runCatching null
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            exportToSelectedFolder(target, meta)
            markOk()
            enforceCacheLimit()
            Log.i(TAG, "Downloaded: ${target.name} (${target.length() / 1024} KB)")
            target
        }.onFailure {
            tmp.delete()
            markFail("download exception: ${it.message}")
        }.getOrNull()
    }

    private fun resolveDirectDownloadUrl(trackId: String): String? {
        val infoBody = httpGet("$BASE_URL/tracks/$trackId/download-info") ?: return null
        val infoUrl = runCatching {
            val items = json.parseToJsonElement(infoBody).jsonObject["result"]?.jsonArray
                ?: return@runCatching null
            val mp3Items = items.map { it.jsonObject }
                .filter { it["codec"]?.jsonPrimitive?.content == "mp3" }
            val chosen = if (settingsPrefs.getBoolean("yandex_high_quality", true)) {
                mp3Items.maxByOrNull { it["bitrateInKbps"]?.jsonPrimitive?.intOrNull ?: 0 }
            } else {
                mp3Items.filter { (it["bitrateInKbps"]?.jsonPrimitive?.intOrNull ?: 0) <= 192 }
                    .maxByOrNull { it["bitrateInKbps"]?.jsonPrimitive?.intOrNull ?: 0 }
                    ?: mp3Items.minByOrNull { it["bitrateInKbps"]?.jsonPrimitive?.intOrNull ?: 0 }
            }
            chosen?.get("downloadInfoUrl")?.jsonPrimitive?.content
        }.getOrNull() ?: return null

        val sep = if ("?" in infoUrl) "&" else "?"
        val signedBody = httpGet("$infoUrl${sep}format=json", withAuth = false) ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(signedBody).jsonObject
            val host = obj["host"]?.jsonPrimitive?.content ?: return@runCatching null
            val path = obj["path"]?.jsonPrimitive?.content ?: return@runCatching null
            val ts = obj["ts"]?.jsonPrimitive?.content ?: return@runCatching null
            val s = obj["s"]?.jsonPrimitive?.content ?: return@runCatching null
            val sign = md5(SIGN_SALT + path.removePrefix("/") + s)
            "https://$host/get-mp3/$sign/$ts$path"
        }.getOrNull()
    }

    private fun httpGet(urlStr: String, withAuth: Boolean = true): String? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("X-Yandex-Music-Client", CLIENT_HEADER)
                if (withAuth) {
                    setRequestProperty("Authorization", "OAuth ${getToken()}")
                }
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    markFail("HTTP $code for $urlStr")
                    return@runCatching null
                }
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                markOk()
                text
            } finally {
                conn.disconnect()
            }
        }.onFailure {
            markFail(it.message ?: it.javaClass.simpleName)
        }.getOrNull()
    }

    private fun parseTrack(obj: JsonObject): YandexTrackMeta? {
        val id = obj["id"]?.jsonPrimitive?.content ?: return null
        val title = obj["title"]?.jsonPrimitive?.content ?: return null
        val artistsArr = obj["artists"] as? JsonArray
        val artist = artistsArr
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
            ?.joinToString(", ")
            .orEmpty()
        val album = (obj["albums"] as? JsonArray)
            ?.firstOrNull()?.jsonObject?.get("title")?.jsonPrimitive?.content
            .orEmpty()
        val durationMs = obj["durationMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val coverUri = obj["coverUri"]?.jsonPrimitive?.content.orEmpty()
        val rawGenre = buildList {
            (obj["genres"] as? JsonArray)?.forEach { el ->
                (el as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.let { add(it) }
                    ?: runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()?.let { add(it) }
            }
            obj["genre"]?.jsonPrimitive?.contentOrNull?.let { add(it) }
            val firstAlbum = (obj["albums"] as? JsonArray)?.firstOrNull() as? JsonObject
            firstAlbum?.get("genre")?.jsonPrimitive?.contentOrNull?.let { add(it) }
            (firstAlbum?.get("genres") as? JsonArray)?.forEach { el ->
                (el as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.let { add(it) }
                    ?: runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()?.let { add(it) }
            }
        }.firstOrNull { it.isNotBlank() }?.trim()?.lowercase().orEmpty()

        return YandexTrackMeta(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            coverUri = coverUri,
            genre = rawGenre,
        )
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^" + "a-zA-Z0-9\u0410-\u044f\u0401\u0451 _.-]+"), "_")
            .trim()
            .take(80)

    companion object {
        private const val TAG = "YandexMusicClient"
        private const val BASE_URL = "https://api.music.yandex.net"
        private const val SIGN_SALT = "XGRlBW9FXlekgbPrRHuSiA"
        private const val CLIENT_HEADER = "YandexMusicAndroid/24023621"
        private const val USER_AGENT = "Yandex-Music-API"
        private const val PREFS_NAME = "yandex_music_prefs"
        private const val KEY_TOKEN = "oauth_token"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 6_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 20_000
        private const val MAX_FAILS = 3
        private const val BLOCK_DURATION_MS = 5 * 60 * 1000L
    }

    private fun normalizeDirKey(raw: String): String =
        raw.lowercase()
            .replace('\u0451', '\u0435')
            .replace(Regex("[^" + "a-zA-Z\u0410-\u044f0-9]+"), " ")
            .trim()

    private fun findExistingSafDir(
        resolver: android.content.ContentResolver,
        treeUri: android.net.Uri,
        parentDocUri: android.net.Uri,
        candidateNames: List<String>,
    ): android.net.Uri? {
        val cleanCandidates = candidateNames
            .map { sanitizeFileName(it) }
            .filter { it.isNotBlank() }
        if (cleanCandidates.isEmpty()) return null

        val normCandidates = cleanCandidates.map { normalizeDirKey(it) }.toSet()
        val parentDocId = android.provider.DocumentsContract.getDocumentId(parentDocUri)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

        val existingId = runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                var fuzzyMatchId: String? = null
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().trim() else ""
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                    if (mime != android.provider.DocumentsContract.Document.MIME_TYPE_DIR || idIdx < 0) continue
                    val docId = cursor.getString(idIdx) ?: continue
                    if (cleanCandidates.any { it.equals(name, ignoreCase = true) }) {
                        return@use docId
                    }
                    if (fuzzyMatchId == null && normalizeDirKey(name) in normCandidates) {
                        fuzzyMatchId = docId
                    }
                }
                fuzzyMatchId
            }
        }.getOrNull()

        return existingId?.takeIf { it.isNotBlank() }?.let {
            android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, it)
        }
    }

    private fun findOrCreateSafDir(
        resolver: android.content.ContentResolver,
        treeUri: android.net.Uri,
        parentDocUri: android.net.Uri,
        displayName: String,
        extraCandidates: List<String> = emptyList(),
    ): android.net.Uri? {
        val cleanName = sanitizeFileName(displayName).ifBlank { "Unknown" }
        val existing = findExistingSafDir(
            resolver = resolver,
            treeUri = treeUri,
            parentDocUri = parentDocUri,
            candidateNames = listOf(cleanName) + extraCandidates,
        )
        if (existing != null) return existing
        return android.provider.DocumentsContract.createDocument(
            resolver,
            parentDocUri,
            android.provider.DocumentsContract.Document.MIME_TYPE_DIR,
            cleanName,
        )
    }

    private fun findExistingSafFile(
        resolver: android.content.ContentResolver,
        treeUri: android.net.Uri,
        parentDocUri: android.net.Uri,
        displayName: String,
    ): android.net.Uri? {
        val parentDocId = android.provider.DocumentsContract.getDocumentId(parentDocUri)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val existingId = runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    if (name == displayName || name == "$displayName.mp3") {
                        if (idIdx >= 0) return@use cursor.getString(idIdx)
                    }
                }
                null
            }
        }.getOrNull()
        return existingId?.takeIf { it.isNotBlank() }?.let {
            android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, it)
        }
    }

    private fun exportToSelectedFolder(sourceFile: File, meta: YandexTrackMeta) {
        val uriStr = settingsPrefs.getString("yandex_download_dir_uri", "").orEmpty().trim()
        if (uriStr.isBlank() || !sourceFile.exists()) return
        runCatching {
            val treeUri = android.net.Uri.parse(uriStr)
            val rootDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            val rootDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            val resolver = appContext.contentResolver

            val rawArtist = meta.artist.ifBlank { "Неизвестный исполнитель" }
            val primaryArtist = rawArtist.split(",").firstOrNull()?.trim().orEmpty()
            val artistDir = findOrCreateSafDir(
                resolver = resolver,
                treeUri = treeUri,
                parentDocUri = rootDocUri,
                displayName = primaryArtist.ifBlank { rawArtist },
                extraCandidates = listOf(rawArtist),
            ) ?: rootDocUri

            val albumDir = findOrCreateSafDir(
                resolver = resolver,
                treeUri = treeUri,
                parentDocUri = artistDir,
                displayName = meta.album.ifBlank { "Без альбома" },
            ) ?: artistDir

            val baseName = sourceFile.nameWithoutExtension
            val outUri = findExistingSafFile(resolver, treeUri, albumDir, baseName)
                ?: android.provider.DocumentsContract.createDocument(
                    resolver,
                    albumDir,
                    "audio/mpeg",
                    baseName,
                ) ?: return

            resolver.openOutputStream(outUri, "wt")?.use { out ->
                sourceFile.inputStream().use { inp -> inp.copyTo(out) }
            }
            recordTrackInLocalIndex(
                meta = meta,
                fileName = sourceFile.name,
                docUri = outUri.toString(),
                sizeBytes = sourceFile.length(),
            )
        }.onFailure {
            android.util.Log.w(TAG, "exportToSelectedFolder failed: ${it.message}")
        }
    }
}
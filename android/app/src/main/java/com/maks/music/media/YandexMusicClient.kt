package com.maks.music.media

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
)

class YandexMusicClient(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val cacheDir: File = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "yandex_cache").apply {
        mkdirs()
    }

    @Volatile private var failCount = 0
    @Volatile private var blockedUntilMs = 0L

    fun getToken(): String = prefs.getString(KEY_TOKEN, "")?.trim().orEmpty()

    fun setToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token.trim()).apply()
        resetCircuitBreaker()
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

    fun findCachedFile(trackId: String): File? {
        val suffix = "[$trackId].mp3"
        return cacheDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(suffix) && it.length() > 16_384 }
    }

    suspend fun fetchWaveTracks(stationId: String = "user:onyourwave"): List<YandexTrackMeta> =
        withContext(Dispatchers.IO) {
            if (!isConfigured() || isTemporarilyDown()) return@withContext emptyList()
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

        if (!isConfigured() || isTemporarilyDown()) return@withContext null

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
            markOk()
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
            items.map { it.jsonObject }
                .filter { it["codec"]?.jsonPrimitive?.content == "mp3" }
                .maxByOrNull { it["bitrateInKbps"]?.jsonPrimitive?.intOrNull ?: 0 }
                ?.get("downloadInfoUrl")?.jsonPrimitive?.content
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
        return YandexTrackMeta(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            coverUri = coverUri,
        )
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\/:*?"<>|]"), "_").trim().take(80)

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
}

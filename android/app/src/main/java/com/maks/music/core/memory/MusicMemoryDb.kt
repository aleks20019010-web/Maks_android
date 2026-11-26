package com.maks.music.core.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.maks.music.core.TimeBucket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Локальная долговременная память Макса на Android SQLite (совместимая с memory/storage.py):
 *   - events (история воспроизведений, скипов, дослушиваний)
 *   - artist_stats (веса артистов для умной Волны и правило PLAY_ARTIST_3X / 5X)
 *   - track_stats (счётчики и blacklist треков)
 *   - liked_tracks (локальный плейлист лайков + seed-once из assets/config/liked_tracks.json)
 *   - habits (привычки time_bucket → artist)
 */
class MusicMemoryDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    data class BlacklistedEntry(
        val id: String,
        val artist: String,
        val title: String,
        val isArtist: Boolean,
    )

    data class ImportResult(
        val likedAdded: Int,
        val dislikedTracksAdded: Int,
        val dislikedArtistsAdded: Int,
    )

    private val appContext = context.applicationContext
    private val prettyJson = Json { prettyPrint = true }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                artist TEXT,
                track_id TEXT,
                track_title TEXT,
                time_bucket TEXT,
                duration INTEGER DEFAULT 0,
                skipped_at INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_ts ON events(timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_artist ON events(artist)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS artist_stats (
                artist TEXT PRIMARY KEY,
                display_name TEXT,
                play_count INTEGER DEFAULT 0,
                skip_count INTEGER DEFAULT 0,
                finish_count INTEGER DEFAULT 0,
                weight REAL DEFAULT 0.0,
                blacklisted INTEGER DEFAULT 0,
                last_played INTEGER DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS track_stats (
                track_id TEXT PRIMARY KEY,
                artist TEXT,
                title TEXT,
                play_count INTEGER DEFAULT 0,
                skip_count INTEGER DEFAULT 0,
                finish_count INTEGER DEFAULT 0,
                blacklisted INTEGER DEFAULT 0,
                last_played INTEGER DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS liked_tracks (
                track_key TEXT PRIMARY KEY,
                track_id TEXT,
                artist TEXT,
                title TEXT,
                added_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS habits (
                time_bucket TEXT NOT NULL,
                artist TEXT NOT NULL,
                score REAL DEFAULT 1.0,
                PRIMARY KEY (time_bucket, artist)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    /**
     * Однократная загрузка стартовых лайков из assets/config/liked_tracks.json.
     * После первого запуска флаг сохраняется в SharedPreferences — удалённые пользователем
     * треки больше не восстанавливаются.
     */
    fun seedInitialLikesIfNeeded(): Int {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INITIAL_LIKES_SEEDED, false)) return 0

        val raw = runCatching {
            appContext.assets.open(INITIAL_LIKES_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return 0

        val result = runCatching { importPreferencesJson(raw) }.getOrNull()
        prefs.edit().putBoolean(KEY_INITIAL_LIKES_SEEDED, true).apply()
        return result?.likedAdded ?: 0
    }

    fun exportPreferencesJson(): String {
        val db = readableDatabase

        val likedArray = buildJsonArray {
            db.rawQuery(
                "SELECT artist, title, track_id, added_at FROM liked_tracks ORDER BY added_at DESC",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    add(
                        buildJsonObject {
                            put("artist", c.getString(0).orEmpty())
                            put("title", c.getString(1).orEmpty())
                            put("track_id", c.getString(2).orEmpty())
                            put("ts", c.getLong(3) / 1000.0)
                            put("source", "android")
                        }
                    )
                }
            }
        }

        val dislikedTracksArray = buildJsonArray {
            db.rawQuery(
                "SELECT artist, title, track_id, last_played FROM track_stats WHERE blacklisted = 1 ORDER BY last_played DESC",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    add(
                        buildJsonObject {
                            put("artist", c.getString(0).orEmpty())
                            put("title", c.getString(1).orEmpty())
                            put("track_id", c.getString(2).orEmpty())
                            put("ts", c.getLong(3) / 1000.0)
                        }
                    )
                }
            }
        }

        val dislikedArtistsArray = buildJsonArray {
            db.rawQuery(
                "SELECT COALESCE(NULLIF(display_name, ''), artist) FROM artist_stats WHERE blacklisted = 1",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0).orEmpty()
                    if (name.isNotBlank()) add(kotlinx.serialization.json.JsonPrimitive(name))
                }
            }
        }

        val root = buildJsonObject {
            put("version", 1)
            put("exported_at", System.currentTimeMillis())
            put("liked", likedArray)
            put("disliked_tracks", dislikedTracksArray)
            put("disliked_artists", dislikedArtistsArray)
        }
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Поддерживает оба формата:
     *   1. Полный бэкап: { "liked": [...], "disliked_tracks": [...], "disliked_artists": [...] }
     *   2. Прямой массив лайков (совместим с десктопным liked_tracks.json): [ { "artist", "title", ... } ]
     */
    fun importPreferencesJson(rawJson: String): ImportResult {
        val root = Json.parseToJsonElement(rawJson)
        var likedAdded = 0
        var dislikedTracksAdded = 0
        var dislikedArtistsAdded = 0

        val db = writableDatabase
        db.beginTransaction()
        try {
            when (root) {
                is JsonArray -> {
                    likedAdded += importLikedArray(db, root)
                }
                is JsonObject -> {
                    root["liked"]?.jsonArray?.let { arr ->
                        likedAdded += importLikedArray(db, arr)
                    }
                    root["disliked_tracks"]?.jsonArray?.let { arr ->
                        for (el in arr) {
                            val obj = runCatching { el.jsonObject }.getOrNull() ?: continue
                            val artist = obj["artist"]?.jsonPrimitive?.content?.trim().orEmpty()
                            val title = obj["title"]?.jsonPrimitive?.content?.trim().orEmpty()
                            val rawId = obj["track_id"]?.jsonPrimitive?.content?.trim().orEmpty()
                            val trackId = rawId.ifBlank { trackKey(artist, title) }
                            if (artist.isBlank() && title.isBlank()) continue

                            db.delete("liked_tracks", "track_key = ?", arrayOf(trackKey(artist, title)))
                            db.execSQL(
                                """
                                INSERT INTO track_stats (track_id, artist, title, blacklisted, last_played)
                                VALUES (?, ?, ?, 1, ?)
                                ON CONFLICT(track_id) DO UPDATE SET blacklisted = 1
                                """.trimIndent(),
                                arrayOf(trackId, artist, title, System.currentTimeMillis())
                            )
                            dislikedTracksAdded++
                        }
                    }
                    root["disliked_artists"]?.jsonArray?.let { arr ->
                        for (el in arr) {
                            val name = runCatching { el.jsonPrimitive.content.trim() }.getOrNull().orEmpty()
                            if (name.isBlank()) continue
                            val artistKey = name.lowercase()
                            db.execSQL(
                                """
                                INSERT INTO artist_stats (artist, display_name, weight, blacklisted)
                                VALUES (?, ?, -5.0, 1)
                                ON CONFLICT(artist) DO UPDATE SET blacklisted = 1, weight = -5.0
                                """.trimIndent(),
                                arrayOf(artistKey, name)
                            )
                            dislikedArtistsAdded++
                        }
                    }
                }
                else -> Unit
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return ImportResult(likedAdded, dislikedTracksAdded, dislikedArtistsAdded)
    }

    private fun importLikedArray(db: SQLiteDatabase, array: JsonArray): Int {
        var added = 0
        val now = System.currentTimeMillis()
        for (el in array) {
            val obj = runCatching { el.jsonObject }.getOrNull() ?: continue
            val artist = obj["artist"]?.jsonPrimitive?.content?.trim().orEmpty()
            val title = obj["title"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (artist.isBlank() && title.isBlank()) continue

            val trackId = obj["track_id"]?.jsonPrimitive?.content?.trim().orEmpty()
            val tsSec = obj["ts"]?.jsonPrimitive?.doubleOrNull
            val addedAt = if (tsSec != null && tsSec > 0.0) (tsSec * 1000.0).toLong() else now
            val key = trackKey(artist, title)

            val rowId = db.insertWithOnConflict(
                "liked_tracks",
                null,
                ContentValues().apply {
                    put("track_key", key)
                    put("track_id", trackId)
                    put("artist", artist)
                    put("title", title)
                    put("added_at", addedAt)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
            if (rowId != -1L) {
                added++
                val artistKey = artist.lowercase()
                if (artistKey.isNotEmpty()) {
                    db.execSQL(
                        """
                        INSERT INTO artist_stats (artist, display_name, weight)
                        VALUES (?, ?, 1.5)
                        ON CONFLICT(artist) DO UPDATE SET weight = MIN(10.0, weight + 0.5), blacklisted = 0
                        """.trimIndent(),
                        arrayOf(artistKey, artist)
                    )
                }
            }
        }
        return added
    }

    fun recordPlay(trackId: String, artist: String, title: String) {
        val now = System.currentTimeMillis()
        val bucket = TimeBucket.current()
        val artistKey = artist.trim().lowercase()
        val db = writableDatabase

        db.insert("events", null, ContentValues().apply {
            put("timestamp", now)
            put("event_type", "play_start")
            put("artist", artistKey)
            put("track_id", trackId)
            put("track_title", title)
            put("time_bucket", bucket)
        })

        if (artistKey.isNotEmpty()) {
            db.execSQL(
                """
                INSERT INTO artist_stats (artist, display_name, play_count, weight, last_played)
                VALUES (?, ?, 1, 0.2, ?)
                ON CONFLICT(artist) DO UPDATE SET
                    display_name = excluded.display_name,
                    play_count = play_count + 1,
                    weight = MIN(10.0, weight + 0.15),
                    last_played = excluded.last_played
                """.trimIndent(),
                arrayOf(artistKey, artist.trim(), now)
            )

            db.execSQL(
                """
                INSERT INTO habits (time_bucket, artist, score)
                VALUES (?, ?, 1.0)
                ON CONFLICT(time_bucket, artist) DO UPDATE SET
                    score = score + 0.5
                """.trimIndent(),
                arrayOf(bucket, artistKey)
            )
        }

        db.execSQL(
            """
            INSERT INTO track_stats (track_id, artist, title, play_count, last_played)
            VALUES (?, ?, ?, 1, ?)
            ON CONFLICT(track_id) DO UPDATE SET
                play_count = play_count + 1,
                last_played = excluded.last_played
            """.trimIndent(),
            arrayOf(trackId, artist.trim(), title.trim(), now)
        )
    }

    fun recordFinish(trackId: String, artist: String, title: String, durationSec: Int) {
        val now = System.currentTimeMillis()
        val artistKey = artist.trim().lowercase()
        val db = writableDatabase

        db.insert("events", null, ContentValues().apply {
            put("timestamp", now)
            put("event_type", "finish")
            put("artist", artistKey)
            put("track_id", trackId)
            put("track_title", title)
            put("duration", durationSec)
        })

        if (artistKey.isNotEmpty()) {
            db.execSQL(
                """
                UPDATE artist_stats
                SET finish_count = finish_count + 1, weight = MIN(10.0, weight + 0.4)
                WHERE artist = ?
                """.trimIndent(),
                arrayOf(artistKey)
            )
        }
        db.execSQL(
            "UPDATE track_stats SET finish_count = finish_count + 1 WHERE track_id = ?",
            arrayOf(trackId)
        )
    }

    fun recordSkip(trackId: String, artist: String, title: String, skippedAtSec: Int) {
        val now = System.currentTimeMillis()
        val artistKey = artist.trim().lowercase()
        val db = writableDatabase

        db.insert("events", null, ContentValues().apply {
            put("timestamp", now)
            put("event_type", "skip")
            put("artist", artistKey)
            put("track_id", trackId)
            put("track_title", title)
            put("skipped_at", skippedAtSec)
        })

        val penalty = if (skippedAtSec < 20) 0.6 else 0.25
        if (artistKey.isNotEmpty()) {
            db.execSQL(
                """
                INSERT INTO artist_stats (artist, display_name, skip_count, weight, last_played)
                VALUES (?, ?, 1, ?, ?)
                ON CONFLICT(artist) DO UPDATE SET
                    skip_count = skip_count + 1,
                    weight = MAX(-5.0, weight - ?)
                """.trimIndent(),
                arrayOf(artistKey, artist.trim(), -penalty, now, penalty)
            )
        }
        db.execSQL(
            "UPDATE track_stats SET skip_count = skip_count + 1 WHERE track_id = ?",
            arrayOf(trackId)
        )
    }

    fun addLike(trackId: String, artist: String, title: String): Boolean {
        val key = trackKey(artist, title)
        val db = writableDatabase
        val inserted = db.insertWithOnConflict(
            "liked_tracks",
            null,
            ContentValues().apply {
                put("track_key", key)
                put("track_id", trackId)
                put("artist", artist.trim())
                put("title", title.trim())
                put("added_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        db.execSQL("UPDATE track_stats SET blacklisted = 0 WHERE track_id = ?", arrayOf(trackId))
        val artistKey = artist.trim().lowercase()
        if (artistKey.isNotEmpty()) {
            db.execSQL(
                """
                INSERT INTO artist_stats (artist, display_name, weight)
                VALUES (?, ?, 1.5)
                ON CONFLICT(artist) DO UPDATE SET weight = MIN(10.0, weight + 1.0), blacklisted = 0
                """.trimIndent(),
                arrayOf(artistKey, artist.trim())
            )
        }
        return inserted != -1L
    }

    fun removeLike(artist: String, title: String) {
        writableDatabase.delete("liked_tracks", "track_key = ?", arrayOf(trackKey(artist, title)))
    }

    fun isLiked(artist: String, title: String): Boolean {
        val key = trackKey(artist, title)
        readableDatabase.rawQuery("SELECT 1 FROM liked_tracks WHERE track_key = ? LIMIT 1", arrayOf(key)).use { c ->
            return c.moveToFirst()
        }
    }

    fun getLikedTrackIds(): Set<String> {
        val out = LinkedHashSet<String>()
        readableDatabase.rawQuery("SELECT track_id FROM liked_tracks ORDER BY added_at DESC", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                if (!id.isNullOrBlank()) out += id
            }
        }
        return out
    }

    fun getLikedTrackKeys(): Set<String> {
        val out = LinkedHashSet<String>()
        readableDatabase.rawQuery("SELECT track_key FROM liked_tracks ORDER BY added_at DESC", null).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0)
                if (!key.isNullOrBlank()) out += key
            }
        }
        return out
    }

    fun blacklistTrack(trackId: String, artist: String, title: String) {
        removeLike(artist, title)
        writableDatabase.execSQL(
            """
            INSERT INTO track_stats (track_id, artist, title, blacklisted, last_played)
            VALUES (?, ?, ?, 1, ?)
            ON CONFLICT(track_id) DO UPDATE SET blacklisted = 1
            """.trimIndent(),
            arrayOf(trackId, artist.trim(), title.trim(), System.currentTimeMillis())
        )
    }

    fun blacklistArtist(artist: String) {
        val artistKey = artist.trim().lowercase()
        if (artistKey.isEmpty()) return
        writableDatabase.execSQL(
            """
            INSERT INTO artist_stats (artist, display_name, weight, blacklisted)
            VALUES (?, ?, -5.0, 1)
            ON CONFLICT(artist) DO UPDATE SET blacklisted = 1, weight = -5.0
            """.trimIndent(),
            arrayOf(artistKey, artist.trim())
        )
    }

    fun getBlacklistedTrackIds(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT track_id FROM track_stats WHERE blacklisted = 1", null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun getBlacklistedArtists(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT artist FROM artist_stats WHERE blacklisted = 1", null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun getBlacklistedEntries(): List<BlacklistedEntry> {
        val list = mutableListOf<BlacklistedEntry>()
        readableDatabase.rawQuery(
            "SELECT artist, COALESCE(NULLIF(display_name, ''), artist) FROM artist_stats WHERE blacklisted = 1 ORDER BY display_name ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0).orEmpty()
                val name = c.getString(1).orEmpty()
                if (key.isNotBlank()) {
                    list += BlacklistedEntry(id = key, artist = name, title = "", isArtist = true)
                }
            }
        }
        readableDatabase.rawQuery(
            "SELECT track_id, artist, title FROM track_stats WHERE blacklisted = 1 ORDER BY last_played DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val tid = c.getString(0).orEmpty()
                val artist = c.getString(1).orEmpty()
                val title = c.getString(2).orEmpty()
                if (tid.isNotBlank()) {
                    list += BlacklistedEntry(id = tid, artist = artist, title = title, isArtist = false)
                }
            }
        }
        return list
    }

    fun unblacklistArtist(artistKey: String) {
        writableDatabase.execSQL(
            "UPDATE artist_stats SET blacklisted = 0, weight = 0.0 WHERE artist = ?",
            arrayOf(artistKey.trim().lowercase())
        )
    }

    fun unblacklistTrack(trackId: String) {
        writableDatabase.execSQL(
            "UPDATE track_stats SET blacklisted = 0 WHERE track_id = ?",
            arrayOf(trackId)
        )
    }

    fun getArtistWeights(): Map<String, Double> {
        val out = HashMap<String, Double>()
        readableDatabase.rawQuery("SELECT artist, weight FROM artist_stats", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getDouble(1)
        }
        return out
    }

    fun getHabitScores(bucket: String = TimeBucket.current()): Map<String, Double> {
        val out = HashMap<String, Double>()
        readableDatabase.rawQuery("SELECT artist, score FROM habits WHERE time_bucket = ?", arrayOf(bucket)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getDouble(1)
        }
        return out
    }

    fun playsArtistToday(artist: String): Int {
        val artistKey = artist.trim().lowercase()
        if (artistKey.isEmpty()) return 0
        val dayAgo = System.currentTimeMillis() - 24L * 3600L * 1000L
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE event_type = 'play_start' AND artist = ? AND timestamp >= ?",
            arrayOf(artistKey, dayAgo.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun getTrackPlayStats(): Map<String, Pair<Int, Long>> {
        val out = HashMap<String, Pair<Int, Long>>()
        readableDatabase.rawQuery(
            "SELECT track_id, play_count, last_played FROM track_stats",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                out[id] = c.getInt(1) to c.getLong(2)
            }
        }
        return out
    }

    companion object {
        private const val DB_NAME = "memory.db"
        private const val DB_VERSION = 1
        private const val PREFS_NAME = "maks_memory_prefs"
        private const val KEY_INITIAL_LIKES_SEEDED = "seeded_initial_likes_v1"
        private const val INITIAL_LIKES_ASSET = "config/liked_tracks.json"

        fun trackKey(artist: String, title: String): String =
            "${artist.trim().lowercase()}::${title.trim().lowercase()}"
    }
}
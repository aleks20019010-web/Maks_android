package com.maks.music.core.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import com.maks.music.core.TimeBucket
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MusicMemoryDb(private val context: Context) : SQLiteOpenHelper(
    context,
    resolveExternalDbPath(context),
    null,
    DB_VERSION,
) {
    init {
        migrateLegacyDbIfNeeded(context)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                time_bucket TEXT NOT NULL,
                event_type TEXT NOT NULL,
                track_id TEXT NOT NULL,
                artist TEXT NOT NULL,
                title TEXT NOT NULL,
                duration_sec INTEGER NOT NULL DEFAULT 0,
                extra TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_artist ON events(artist)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS artist_stats (
                artist_key TEXT PRIMARY KEY,
                artist_display TEXT NOT NULL,
                weight REAL NOT NULL DEFAULT 0.0,
                plays INTEGER NOT NULL DEFAULT 0,
                finishes INTEGER NOT NULL DEFAULT 0,
                skips INTEGER NOT NULL DEFAULT 0,
                likes INTEGER NOT NULL DEFAULT 0,
                dislikes INTEGER NOT NULL DEFAULT 0,
                last_ts INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS blacklist_tracks (
                track_id TEXT PRIMARY KEY,
                artist TEXT NOT NULL,
                title TEXT NOT NULL,
                ts INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS blacklist_artists (
                artist_key TEXT PRIMARY KEY,
                artist_display TEXT NOT NULL,
                ts INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS liked_tracks (
                track_key TEXT PRIMARY KEY,
                track_id TEXT NOT NULL,
                artist TEXT NOT NULL,
                title TEXT NOT NULL,
                ts INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS liked_tracks (
                    track_key TEXT PRIMARY KEY,
                    track_id TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    title TEXT NOT NULL,
                    ts INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    fun seedInitialLikesIfNeeded() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INITIAL_LIKES_SEEDED, false)) return

        val db = writableDatabase
        val existingCount = runCatching {
            db.rawQuery("SELECT COUNT(*) FROM liked_tracks", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        }.getOrDefault(0)

        if (existingCount == 0) {
            val now = System.currentTimeMillis()
            db.beginTransaction()
            try {
                for ((artist, title) in INITIAL_SEED_LIKES) {
                    val key = trackKey(artist, title)
                    if (key.isBlank()) continue
                    val cv = ContentValues().apply {
                        put("track_key", key)
                        put("track_id", "seed_$key")
                        put("artist", artist)
                        put("title", title)
                        put("ts", now)
                    }
                    db.insertWithOnConflict("liked_tracks", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        prefs.edit().putBoolean(KEY_INITIAL_LIKES_SEEDED, true).apply()
        writeExternalBackupSnapshot()
    }

    fun recordPlay(trackId: String, artist: String, title: String) {
        val now = System.currentTimeMillis()
        insertEvent(now, "play_start", trackId, artist, title, 0, "")
        updateArtist(artist, deltaWeight = +0.15, playInc = 1, ts = now)
    }

    fun recordFinish(trackId: String, artist: String, title: String, durationSec: Int) {
        val now = System.currentTimeMillis()
        insertEvent(now, "play_finish", trackId, artist, title, durationSec, "")
        updateArtist(artist, deltaWeight = +0.45, finishInc = 1, ts = now)
    }

    fun recordSkip(trackId: String, artist: String, title: String, secPlayed: Int) {
        val now = System.currentTimeMillis()
        insertEvent(now, "skip", trackId, artist, title, secPlayed, "")
        updateArtist(artist, deltaWeight = -0.60, skipInc = 1, ts = now)
    }

    fun addLike(trackId: String, artist: String, title: String) {
        val now = System.currentTimeMillis()
        insertEvent(now, "like", trackId, artist, title, 0, "")
        updateArtist(artist, deltaWeight = +1.50, likeInc = 1, ts = now)
        val key = trackKey(artist, title)
        if (key.isNotBlank()) {
            val cv = ContentValues().apply {
                put("track_key", key)
                put("track_id", trackId)
                put("artist", artist)
                put("title", title)
                put("ts", now)
            }
            writableDatabase.insertWithOnConflict("liked_tracks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            writeExternalBackupSnapshot()
        }
    }

    fun removeLike(artist: String, title: String) {
        val key = trackKey(artist, title)
        if (key.isBlank()) return
        writableDatabase.delete("liked_tracks", "track_key = ?", arrayOf(key))
        writeExternalBackupSnapshot()
    }

    fun isLiked(artist: String, title: String): Boolean {
        val key = trackKey(artist, title)
        if (key.isBlank()) return false
        readableDatabase.rawQuery(
            "SELECT 1 FROM liked_tracks WHERE track_key = ? LIMIT 1",
            arrayOf(key),
        ).use { c ->
            return c.moveToFirst()
        }
    }

    fun getLikedTrackIds(): Set<String> {
        val result = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT track_id FROM liked_tracks", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                if (!id.isNullOrBlank()) result += id
            }
        }
        return result
    }

    fun getLikedTrackKeys(): Set<String> {
        val result = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT track_key FROM liked_tracks", null).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0)
                if (!key.isNullOrBlank()) result += key
            }
        }
        return result
    }

    fun blacklistTrack(trackId: String, artist: String, title: String) {
        val now = System.currentTimeMillis()
        insertEvent(now, "dislike_track", trackId, artist, title, 0, "")
        updateArtist(artist, deltaWeight = -2.0, dislikeInc = 1, ts = now)
        val cv = ContentValues().apply {
            put("track_id", trackId)
            put("artist", artist)
            put("title", title)
            put("ts", now)
        }
        writableDatabase.insertWithOnConflict("blacklist_tracks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        writeExternalBackupSnapshot()
    }

    fun blacklistArtist(artist: String) {
        val clean = artist.trim()
        if (clean.isEmpty()) return
        val key = clean.lowercase()
        val now = System.currentTimeMillis()
        insertEvent(now, "dislike_artist", "", clean, "", 0, "")
        val cv = ContentValues().apply {
            put("artist_key", key)
            put("artist_display", clean)
            put("ts", now)
        }
        writableDatabase.insertWithOnConflict("blacklist_artists", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        writeExternalBackupSnapshot()
    }

    fun getBlacklistedTrackIds(): Set<String> {
        val set = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT track_id FROM blacklist_tracks", null).use { c ->
            while (c.moveToNext()) {
                set += c.getString(0)
            }
        }
        return set
    }

    fun getBlacklistedArtists(): Set<String> {
        val set = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT artist_key FROM blacklist_artists", null).use { c ->
            while (c.moveToNext()) {
                set += c.getString(0)
            }
        }
        return set
    }

    fun getArtistWeights(): Map<String, Double> {
        val map = mutableMapOf<String, Double>()
        readableDatabase.rawQuery("SELECT artist_key, weight FROM artist_stats", null).use { c ->
            while (c.moveToNext()) {
                map[c.getString(0)] = c.getDouble(1)
            }
        }
        return map
    }

    fun getHabitScores(bucket: TimeBucket, lookbackDays: Int = 21): Map<String, Double> {
        val since = System.currentTimeMillis() - lookbackDays * 86_400_000L
        val scores = mutableMapOf<String, Double>()
        readableDatabase.rawQuery(
            """
            SELECT artist, event_type, COUNT(*)
            FROM events
            WHERE ts >= ? AND time_bucket = ? AND artist != ''
            GROUP BY artist, event_type
            """.trimIndent(),
            arrayOf(since.toString(), bucket.id),
        ).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0).trim().lowercase()
                val type = c.getString(1)
                val cnt = c.getInt(2)
                val delta = when (type) {
                    "like" -> cnt * 2.0
                    "play_finish" -> cnt * 1.0
                    "play_start" -> cnt * 0.4
                    "skip" -> cnt * -0.8
                    "dislike_track" -> cnt * -2.5
                    else -> 0.0
                }
                scores[key] = (scores[key] ?: 0.0) + delta
            }
        }
        return scores
    }

    fun getTrackPlayStats(): Map<String, Pair<Int, Long>> {
        val map = mutableMapOf<String, Pair<Int, Long>>()
        readableDatabase.rawQuery(
            """
            SELECT track_id, COUNT(*), MAX(ts)
            FROM events
            WHERE event_type IN ('play_start', 'play_finish') AND track_id != ''
            GROUP BY track_id
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) {
                map[c.getString(0)] = c.getInt(1) to c.getLong(2)
            }
        }
        return map
    }

    fun getTopArtists(limit: Int = 5): List<ArtistSummary> {
        val list = mutableListOf<ArtistSummary>()
        readableDatabase.rawQuery(
            """
            SELECT artist_display, weight, plays, likes, skips
            FROM artist_stats
            ORDER BY weight DESC, plays DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                list += ArtistSummary(
                    artist = c.getString(0),
                    weight = c.getDouble(1),
                    plays = c.getInt(2),
                    likes = c.getInt(3),
                    skips = c.getInt(4),
                )
            }
        }
        return list
    }

    fun exportPreferencesJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAtMs", System.currentTimeMillis())

        val likesArr = JSONArray()
        readableDatabase.rawQuery(
            "SELECT track_key, track_id, artist, title, ts FROM liked_tracks ORDER BY ts DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                likesArr.put(
                    JSONObject().apply {
                        put("trackKey", c.getString(0))
                        put("trackId", c.getString(1))
                        put("artist", c.getString(2))
                        put("title", c.getString(3))
                        put("ts", c.getLong(4))
                    }
                )
            }
        }
        root.put("likedTracks", likesArr)

        val blTracksArr = JSONArray()
        readableDatabase.rawQuery(
            "SELECT track_id, artist, title, ts FROM blacklist_tracks ORDER BY ts DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                blTracksArr.put(
                    JSONObject().apply {
                        put("trackId", c.getString(0))
                        put("artist", c.getString(1))
                        put("title", c.getString(2))
                        put("ts", c.getLong(3))
                    }
                )
            }
        }
        root.put("blacklistedTracks", blTracksArr)

        val blArtistsArr = JSONArray()
        readableDatabase.rawQuery(
            "SELECT artist_key, artist_display, ts FROM blacklist_artists ORDER BY ts DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                blArtistsArr.put(
                    JSONObject().apply {
                        put("artistKey", c.getString(0))
                        put("artistDisplay", c.getString(1))
                        put("ts", c.getLong(2))
                    }
                )
            }
        }
        root.put("blacklistedArtists", blArtistsArr)

        val statsArr = JSONArray()
        readableDatabase.rawQuery(
            """
            SELECT artist_key, artist_display, weight, plays, finishes, skips, likes, dislikes, last_ts
            FROM artist_stats
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) {
                statsArr.put(
                    JSONObject().apply {
                        put("artistKey", c.getString(0))
                        put("artistDisplay", c.getString(1))
                        put("weight", c.getDouble(2))
                        put("plays", c.getInt(3))
                        put("finishes", c.getInt(4))
                        put("skips", c.getInt(5))
                        put("likes", c.getInt(6))
                        put("dislikes", c.getInt(7))
                        put("lastTs", c.getLong(8))
                    }
                )
            }
        }
        root.put("artistStats", statsArr)

        return root.toString(2)
    }

    fun importPreferencesJson(rawJson: String): ImportResult {
        val root = JSONObject(rawJson)
        val now = System.currentTimeMillis()
        var importedLikes = 0
        var importedBlTracks = 0
        var importedBlArtists = 0
        var importedStats = 0

        val db = writableDatabase
        db.beginTransaction()
        try {
            root.optJSONArray("likedTracks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val artist = obj.optString("artist")
                    val title = obj.optString("title")
                    val key = obj.optString("trackKey").ifBlank { trackKey(artist, title) }
                    if (key.isBlank()) continue
                    val cv = ContentValues().apply {
                        put("track_key", key)
                        put("track_id", obj.optString("trackId").ifBlank { "imp_$key" })
                        put("artist", artist)
                        put("title", title)
                        put("ts", obj.optLong("ts", now))
                    }
                    db.insertWithOnConflict("liked_tracks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    importedLikes++
                }
            }

            root.optJSONArray("blacklistedTracks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("trackId")
                    if (id.isBlank()) continue
                    val cv = ContentValues().apply {
                        put("track_id", id)
                        put("artist", obj.optString("artist"))
                        put("title", obj.optString("title"))
                        put("ts", obj.optLong("ts", now))
                    }
                    db.insertWithOnConflict("blacklist_tracks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    importedBlTracks++
                }
            }

            root.optJSONArray("blacklistedArtists")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val display = obj.optString("artistDisplay").trim()
                    val key = obj.optString("artistKey").trim().lowercase().ifBlank { display.lowercase() }
                    if (key.isBlank()) continue
                    val cv = ContentValues().apply {
                        put("artist_key", key)
                        put("artist_display", display.ifBlank { key })
                        put("ts", obj.optLong("ts", now))
                    }
                    db.insertWithOnConflict("blacklist_artists", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    importedBlArtists++
                }
            }

            root.optJSONArray("artistStats")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val display = obj.optString("artistDisplay").trim()
                    val key = obj.optString("artistKey").trim().lowercase().ifBlank { display.lowercase() }
                    if (key.isBlank()) continue
                    val cv = ContentValues().apply {
                        put("artist_key", key)
                        put("artist_display", display.ifBlank { key })
                        put("weight", obj.optDouble("weight", 0.0))
                        put("plays", obj.optInt("plays", 0))
                        put("finishes", obj.optInt("finishes", 0))
                        put("skips", obj.optInt("skips", 0))
                        put("likes", obj.optInt("likes", 0))
                        put("dislikes", obj.optInt("dislikes", 0))
                        put("last_ts", obj.optLong("lastTs", now))
                    }
                    db.insertWithOnConflict("artist_stats", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    importedStats++
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        writeExternalBackupSnapshot()

        return ImportResult(
            likes = importedLikes,
            blacklistedTracks = importedBlTracks,
            blacklistedArtists = importedBlArtists,
            artistStats = importedStats,
        )
    }

    private fun writeExternalBackupSnapshot() {
        runCatching {
            val dir = externalDirOrNull() ?: return
            dir.mkdirs()
            val backupFile = File(dir, BACKUP_JSON_NAME)
            backupFile.writeText(exportPreferencesJson(), Charsets.UTF_8)
        }
    }

    private fun insertEvent(
        ts: Long,
        type: String,
        trackId: String,
        artist: String,
        title: String,
        durationSec: Int,
        extra: String,
    ) {
        val cv = ContentValues().apply {
            put("ts", ts)
            put("time_bucket", TimeBucket.fromTimestamp(ts).id)
            put("event_type", type)
            put("track_id", trackId)
            put("artist", artist)
            put("title", title)
            put("duration_sec", durationSec)
            put("extra", extra)
        }
        writableDatabase.insert("events", null, cv)
    }

    private fun updateArtist(
        artist: String,
        deltaWeight: Double,
        playInc: Int = 0,
        finishInc: Int = 0,
        skipInc: Int = 0,
        likeInc: Int = 0,
        dislikeInc: Int = 0,
        ts: Long,
    ) {
        val clean = artist.trim()
        if (clean.isEmpty()) return
        val key = clean.lowercase()
        val db = writableDatabase

        db.beginTransaction()
        try {
            var curWeight = 0.0
            var curPlays = 0
            var curFinishes = 0
            var curSkips = 0
            var curLikes = 0
            var curDislikes = 0
            var exists = false

            db.rawQuery(
                "SELECT weight, plays, finishes, skips, likes, dislikes FROM artist_stats WHERE artist_key = ?",
                arrayOf(key),
            ).use { c ->
                if (c.moveToFirst()) {
                    exists = true
                    curWeight = c.getDouble(0)
                    curPlays = c.getInt(1)
                    curFinishes = c.getInt(2)
                    curSkips = c.getInt(3)
                    curLikes = c.getInt(4)
                    curDislikes = c.getInt(5)
                }
            }

            val nextWeight = (curWeight + deltaWeight).coerceIn(-15.0, 30.0)
            val cv = ContentValues().apply {
                put("artist_key", key)
                put("artist_display", clean)
                put("weight", nextWeight)
                put("plays", curPlays + playInc)
                put("finishes", curFinishes + finishInc)
                put("skips", curSkips + skipInc)
                put("likes", curLikes + likeInc)
                put("dislikes", curDislikes + dislikeInc)
                put("last_ts", ts)
            }
            if (exists) {
                db.update("artist_stats", cv, "artist_key = ?", arrayOf(key))
            } else {
                db.insert("artist_stats", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    data class ArtistSummary(
        val artist: String,
        val weight: Double,
        val plays: Int,
        val likes: Int,
        val skips: Int,
    )

    data class ImportResult(
        val likes: Int,
        val blacklistedTracks: Int,
        val blacklistedArtists: Int,
        val artistStats: Int,
    )

    companion object {
        const val DB_NAME = "maks_memory.db"
        const val DB_VERSION = 2
        private const val PREFS_NAME = "maks_memory_prefs"
        private const val KEY_INITIAL_LIKES_SEEDED = "initial_likes_seeded_v1"
        private const val BACKUP_JSON_NAME = "maks_memory_backup.json"

        private val INITIAL_SEED_LIKES = listOf(
            "Король и Шут" to "Кукла колдуна",
            "Король и Шут" to "Лесник",
            "Сектор Газа" to "Лирика",
            "Сектор Газа" to "Пора домой",
            "Кино" to "Группа крови",
            "Кино" to "Звезда по имени Солнце",
            "ДДТ" to "Осень",
            "ДДТ" to "Что такое осень",
            "Ария" to "Штиль",
            "Ария" to "Беспечный ангел",
            "Наутилус Помпилиус" to "Крылья",
            "Сплин" to "Выхода нет",
            "Би-2" to "Полковнику никто не пишет",
            "Linkin Park" to "Numb",
            "Linkin Park" to "In the End",
            "System of a Down" to "Chop Suey!",
            "Rammstein" to "Du hast",
            "The Prodigy" to "Smack My Bitch Up",
        )

        fun trackKey(artist: String, title: String): String {
            val a = artist.lowercase()
                .replace(Regex("""[^a-z\u0430-\u044f\u04510-9 ]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            val t = title.lowercase()
                .replace(Regex("""[^a-z\u0430-\u044f\u04510-9 ]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            return if (t.isBlank()) "" else "$a::$t"
        }

        private fun externalDirOrNull(): File? = runCatching {
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            File(base, "Maks")
        }.getOrNull()

        private fun resolveExternalDbPath(context: Context): String {
            val dir = externalDirOrNull()
            if (dir != null) {
                runCatching { dir.mkdirs() }
                val externalFile = File(dir, DB_NAME)
                if (dir.exists() && dir.canWrite()) {
                    return externalFile.absolutePath
                }
            }
            return context.getDatabasePath(DB_NAME).absolutePath
        }

        private fun migrateLegacyDbIfNeeded(context: Context) {
            val targetPath = resolveExternalDbPath(context)
            val targetFile = File(targetPath)
            val legacyFile = context.getDatabasePath(DB_NAME)
            if (legacyFile.exists() && legacyFile.absolutePath != targetFile.absolutePath && !targetFile.exists()) {
                runCatching {
                    targetFile.parentFile?.mkdirs()
                    legacyFile.copyTo(targetFile, overwrite = false)
                    File("${legacyFile.path}-wal").takeIf { it.exists() }?.copyTo(
                        File("${targetFile.path}-wal"),
                        overwrite = false,
                    )
                    File("${legacyFile.path}-shm").takeIf { it.exists() }?.copyTo(
                        File("${targetFile.path}-shm"),
                        overwrite = false,
                    )
                }
            }
        }
    }
}
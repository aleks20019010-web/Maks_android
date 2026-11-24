package com.maks.music.media

import android.net.Uri
import com.maks.music.core.Phonetic

data class LocalTrack(
    val id: String,
    val contentUri: Uri,
    val artist: String,
    val title: String,
    val album: String,
    val genre: String,
    val durationMs: Long,
    val albumId: Long = 0L,
    val displayName: String = "",
    val phonArtist: String = Phonetic.phon(artist),
    val phonTitle: String = Phonetic.phon(title),
    val isRussian: Boolean = detectRussian(artist, title, genre, displayName),
) {
    val label: String
        get() = if (artist.isNotBlank()) "$artist — $title" else title

    companion object {
        private val CYRILLIC_REGEX = Regex("""[а-яёА-ЯЁ]""")
        private val RUS_GENRE_HINTS = listOf("rus", "russian", "shanson", "bard", "рус", "шансон", "бард")
        private val RUS_ARTIST_LATIN_HINTS = setOf(
            "kino", "viktor tsoi", "tsoi", "korol i shut", "kish", "aria", "ariya",
            "splin", "splean", "bi-2", "bi 2", "zemfira", "ddt", "alisa", "nautilus pompilius",
            "agata kristi", "lumen", "louna", "slot", "animal jazz", "tantsy minus",
            "mumiy troll", "leningrad", "noize mc", "oxxxymiron", "basta", "noggano",
            "miyagi", "andy panda", "endshpil", "scriptonite", "skriptonit", "kasta",
            "guf", "ak-47", "centr", "macan", "morgenshtern", "instasamka", "mot",
            "egor creed", "egor krid", "jony", "hammali", "navai", "artik & asti",
            "zivert", "polina gagarina", "dima bilan", "sergey lazarev", "nyusha",
            "maks korzh", "max korzh", "nervy", "poshlaya molly", "molchat doma",
            "ic3peak", "little big", "ruki vverh", "ivanushki", "diskoteka avariya",
            "lyube", "lube", "grigoriy leps", "leps", "stas mikhailov", "mikhail krug",
            "krug", "vladimir vysotsky", "vysotsky", "okudzhava", "sektor gaza",
            "grazhdanskaya oborona", "egor letov", "piknik", "krematoriy", "chaif",
            "chizh", "mashina vremeni", "voskresenie", "akvarium", "grebenshchikov"
        )

        fun detectRussian(artist: String, title: String, genre: String, fileName: String): Boolean {
            if (CYRILLIC_REGEX.containsMatchIn(artist) ||
                CYRILLIC_REGEX.containsMatchIn(title) ||
                CYRILLIC_REGEX.containsMatchIn(fileName)
            ) {
                return true
            }
            val g = genre.lowercase()
            if (RUS_GENRE_HINTS.any { it in g }) return true

            val aLow = artist.lowercase().trim()
            val fLow = fileName.lowercase()
            return RUS_ARTIST_LATIN_HINTS.any { hint ->
                aLow == hint || aLow.contains(hint) || fLow.startsWith("$hint -") || fLow.startsWith("$hint —")
            }
        }
    }
}
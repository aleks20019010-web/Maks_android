package com.maks.music.core.router

import com.maks.music.core.nlu.DecisionAction
import com.maks.music.core.nlu.NluContext
import com.maks.music.core.nlu.NluPipeline
import com.maks.music.core.nlu.PendingCommand
import com.maks.music.core.nlu.VolumeParser
import com.maks.music.core.personality.MaksReply
import com.maks.music.core.personality.PersonalityEngine
import com.maks.music.media.LocalMusicController

/**
 * Порт brain/router.py, сфокусированный на музыкальном ядре:
 *   YES/NO (pending от appraisal 3x skip) → NLU Pipeline → LocalMusicController → PersonalityEngine.
 */
class MusicRouter(
    private val pipeline: NluPipeline,
    private val volumeParser: VolumeParser,
    private val nluContext: NluContext,
    private val personality: PersonalityEngine,
    private val music: LocalMusicController,
    private val onReply: (userText: String?, reply: MaksReply) -> Unit,
) {
    init {
        music.onAppraisalTriggered = { decision ->
            if (decision.shouldReact && decision.artistDisplay.isNotBlank()) {
                nluContext.setPending(
                    PendingCommand(
                        kind = PENDING_BLACKLIST_ARTIST,
                        payload = mapOf("artist" to decision.artistDisplay),
                        ttlMs = 60_000L,
                    )
                )
                val reply = personality.respond(
                    phraseSet = decision.phraseSet ?: "APPRAISAL_SKIP_3X",
                    params = mapOf("artist" to decision.artistDisplay),
                )
                onReply(null, reply)
            }
        }
    }

    fun handleCommand(rawText: String, silentControls: Boolean = false) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val lower = text.lowercase()

        // 1. Обработка YES / NO для pending-предложений Макса
        if (lower in YES_WORDS || lower in NO_WORDS) {
            val pending = nluContext.popPending() ?: return
            if (pending.kind == PENDING_BLACKLIST_ARTIST) {
                val artist = pending.payload["artist"].orEmpty()
                if (lower in YES_WORDS && artist.isNotBlank()) {
                    music.blacklistArtist(artist)
                    val reply = personality.respond(
                        "DISLIKE_ADDED",
                        fallbackText = "Хорошо, убрал $artist из волны",
                    )
                    onReply(text, reply)
                } else {
                    val reply = personality.respond("CANCELLED", fallbackText = "Хорошо, оставляю")
                    onReply(text, reply)
                }
                return
            }
        }

        // 2. Прогон через NLU Pipeline
        val decision = pipeline.process(text)
        if (decision.action != DecisionAction.EXECUTE) {
            val reply = personality.respond("NOT_UNDERSTOOD")
            onReply(text, reply)
            return
        }

        val cmd = decision.command
        val entity = cmd.entity

        val reply: MaksReply? = when (cmd.action) {
            "play_wave" -> {
                val lang = entity?.language ?: entity?.value?.takeIf { it == "russian" || it == "not-russian" }
                if (music.playWave(lang)) {
                    val fallback = when (lang) {
                        "russian" -> "Включаю русскую волну"
                        "not-russian" -> "Включаю зарубежную волну"
                        else -> "Включаю вашу локальную волну"
                    }
                    personality.respond("PLAY_WAVE", fallbackText = fallback)
                } else if (lang == "russian") {
                    personality.respond(
                        "PLAY_SEARCH_NOT_FOUND",
                        mapOf("query" to "русская музыка"),
                        fallbackText = "В локальной библиотеке не нашлось русских треков",
                    )
                } else if (lang == "not-russian") {
                    personality.respond(
                        "PLAY_SEARCH_NOT_FOUND",
                        mapOf("query" to "зарубежная музыка"),
                        fallbackText = "В локальной библиотеке не нашлось зарубежных треков",
                    )
                } else {
                    personality.respond("MUSIC_EMPTY_LIBRARY")
                }
            }

            "play_artist" -> {
                val artist = entity?.value.orEmpty()
                if (music.playArtist(artist)) {
                    personality.respond("PLAY_ARTIST", mapOf("artist" to artist))
                } else {
                    personality.respond("PLAY_SEARCH_NOT_FOUND", mapOf("query" to artist))
                }
            }

            "play_genre" -> {
                val genre = entity?.value.orEmpty()
                val label = genre.removePrefix("genre:")
                if (music.playGenre(genre)) {
                    personality.respond("PLAY_GENRE", mapOf("genre" to label), fallbackText = "Включаю $label")
                } else {
                    personality.respond("PLAY_SEARCH_NOT_FOUND", mapOf("query" to label))
                }
            }

            "play_mood" -> {
                val mood = entity?.value.orEmpty()
                if (music.playMood(mood)) {
                    personality.respond("PLAY_MOOD", mapOf("mood" to mood))
                } else {
                    personality.respond("MUSIC_EMPTY_LIBRARY")
                }
            }

            "play_track", "play_search" -> {
                val query = entity?.value.orEmpty()
                val found = music.playSearch(query)
                if (found != null) {
                    personality.respond(
                        "PLAY_SEARCH_FOUND",
                        mapOf("artist" to found.artist, "title" to found.title),
                        fallbackText = "Включаю ${found.label}",
                    )
                } else {
                    personality.respond("PLAY_SEARCH_NOT_FOUND", mapOf("query" to query))
                }
            }

            "play_local_liked" -> {
                if (music.playLocalLiked()) {
                    personality.respond("PLAY_PLAYLIST", mapOf("name" to "ваши любимые треки"))
                } else {
                    personality.respond(
                        "PLAY_SEARCH_NOT_FOUND",
                        mapOf("query" to "любимые треки"),
                        fallbackText = "Список любимых треков пока пуст",
                    )
                }
            }

            "like_current" -> {
                val t = music.likeCurrent()
                if (t != null) {
                    personality.respond("LIKE_ADDED", mapOf("title" to t.title, "artist" to t.artist))
                } else {
                    personality.respond("NO_TRACK_PLAYING")
                }
            }

            "dislike_current" -> {
                val t = music.dislikeCurrent()
                if (t != null) {
                    personality.respond("DISLIKE_ADDED", mapOf("title" to t.title, "artist" to t.artist))
                } else {
                    personality.respond("NO_TRACK_PLAYING")
                }
            }

            "next" -> {
                music.next(isUserSkip = true)
                if (silentControls) null else personality.respond("NEXT_TRACK")
            }

            "prev" -> {
                music.prev()
                if (silentControls) null else personality.respond("PREV_TRACK")
            }

            "pause" -> {
                music.pause()
                if (silentControls) null else personality.respond("PAUSE")
            }

            "resume" -> {
                music.resume()
                if (silentControls) null else personality.respond("RESUME")
            }

            "stop" -> {
                music.stop()
                if (silentControls) null else personality.respond("STOP")
            }

            "volume_up" -> {
                val lvl = music.volumeUp()
                if (silentControls) null else personality.respond("VOLUME_UP", mapOf("level" to "$lvl"))
            }

            "volume_down" -> {
                val lvl = music.volumeDown()
                if (silentControls) null else personality.respond("VOLUME_DOWN", mapOf("level" to "$lvl"))
            }

            "volume_set" -> {
                val parsed = volumeParser.parseVolume(cmd.originalText)
                if (parsed != null) {
                    val lvl = music.setSystemVolumePercent(parsed)
                    personality.respond("VOLUME_SET", mapOf("level" to "$lvl"), fallbackText = "Громкость $lvl")
                } else {
                    personality.respond("NOT_UNDERSTOOD")
                }
            }

            "now_playing" -> {
                val cur = music.currentTrack()
                if (cur != null) {
                    personality.respond(
                        "NOW_PLAYING",
                        mapOf("artist" to cur.artist, "title" to cur.title),
                        fallbackText = "Сейчас играет ${cur.label}",
                    )
                } else {
                    personality.respond("NO_TRACK_PLAYING")
                }
            }

            "smalltalk" -> {
                val phraseKey = when {
                    lower.contains("спасибо") || lower.contains("благодар") -> "THANKS"
                    lower.contains("привет") || lower.contains("здравств") || lower.contains("добр") -> "HELLO"
                    else -> "SMALLTALK"
                }
                personality.respond(phraseKey, fallbackText = "Я здесь, готов к музыке")
            }

            else -> personality.respond("NOT_UNDERSTOOD")
        }

        if (reply != null) {
            onReply(text, reply)
        }
    }

    companion object {
        private const val PENDING_BLACKLIST_ARTIST = "blacklist_artist"
        private val YES_WORDS = setOf("да", "давай", "конечно", "ага", "угу", "хорошо", "ок", "окей")
        private val NO_WORDS = setOf("нет", "не", "не надо", "неважно", "не важно", "не хочу", "отмена")
    }
}
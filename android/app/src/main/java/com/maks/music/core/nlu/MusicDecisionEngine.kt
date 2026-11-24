package com.maks.music.core.nlu

import com.maks.music.core.AssetProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Объединённый DecisionEngine для музыки и управления воспроизведением
 * (порт brain/nlu/decision/music.py + brain/nlu/decision/controls.py).
 */
class MusicDecisionEngine(assets: AssetProvider) {

    private val searchNoise: Set<String> = runCatching {
        val raw = assets.readText("config/search_noise.json") ?: return@runCatching emptySet()
        Json.parseToJsonElement(raw).jsonObject["noise_words"]?.jsonArray
            ?.map { it.jsonPrimitive.content.lowercase().trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())

    fun resolve(command: Command): Decision {
        // 1. Controls (громкость, пауза, стоп, дальше, назад, что играет)
        CONTROL_ACTIONS[command.intent]?.let { action ->
            command.action = action
            return Decision(
                command = command,
                action = DecisionAction.EXECUTE,
                reason = "${command.intent.name} → $action",
                confidence = command.confidence,
            )
        }

        // 2. Простые музыкальные команды без сущности
        when (command.intent) {
            MusicIntent.MUSIC_LIKE -> {
                command.action = "like_current"
                return Decision(command, DecisionAction.EXECUTE, "MUSIC_LIKE → like_current", command.confidence)
            }
            MusicIntent.MUSIC_DISLIKE -> {
                command.action = "dislike_current"
                return Decision(command, DecisionAction.EXECUTE, "MUSIC_DISLIKE → dislike_current", command.confidence)
            }
            MusicIntent.MUSIC_LOCAL_LIKED -> {
                command.action = "play_local_liked"
                return Decision(command, DecisionAction.EXECUTE, "MUSIC_LOCAL_LIKED → play_local_liked", command.confidence)
            }
            MusicIntent.MUSIC_WAVE -> {
                command.action = "play_wave"
                return Decision(command, DecisionAction.EXECUTE, "MUSIC_WAVE → play_wave", command.confidence)
            }
            MusicIntent.SMALLTALK -> {
                command.action = "smalltalk"
                return Decision(command, DecisionAction.EXECUTE, "SMALLTALK → smalltalk", command.confidence)
            }
            MusicIntent.MUSIC_PLAY -> return resolvePlay(command)
            else -> return Decision(command, DecisionAction.FALLBACK, "unsupported intent ${command.intent}", 0.0)
        }
    }

    private fun resolvePlay(command: Command): Decision {
        val entity = command.entity
        if (entity == null || entity.state == EntityState.ABSENT) {
            command.action = "play_wave"
            return Decision(command, DecisionAction.EXECUTE, "ABSENT → play_wave", command.confidence)
        }

        if (entity.state == EntityState.FOUND) {
            val action = TYPE_TO_ACTION[entity.type]
                ?: return Decision(command, DecisionAction.FALLBACK, "unknown entity type ${entity.type}", 0.0)
            command.action = action
            return Decision(
                command = command,
                action = DecisionAction.EXECUTE,
                reason = "FOUND ${entity.type}=${entity.value} → $action",
                confidence = minOf(command.confidence, entity.matchConfidence),
            )
        }

        // UNRESOLVED / AMBIGUOUS → play_search по локальной библиотеке (порт FIX v6.6 & v6.7)
        val cleanedQuery = cleanSearchQuery(command.normalizedText)
            .ifBlank { entity.value.orEmpty().trim() }
            .ifBlank { entity.candidates.firstOrNull()?.value.orEmpty().trim() }

        if (cleanedQuery.isBlank()) {
            command.action = "play_wave"
            return Decision(command, DecisionAction.EXECUTE, "empty search query → play_wave", command.confidence)
        }

        command.action = "play_search"
        command.entity = Entity(
            type = "search",
            value = cleanedQuery,
            state = EntityState.FOUND,
            matchConfidence = entity.matchConfidence,
            source = "${entity.state.name.lowercase()}_fallback",
            language = entity.language,
        )
        return Decision(
            command = command,
            action = DecisionAction.EXECUTE,
            reason = "${entity.state} → play_search('$cleanedQuery')",
            confidence = entity.matchConfidence,
        )
    }

    private fun cleanSearchQuery(text: String): String {
        if (text.isBlank()) return ""
        return text.lowercase()
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() && it !in searchNoise }
            .joinToString(" ")
            .trim()
    }

    companion object {
        private val CONTROL_ACTIONS = mapOf(
            MusicIntent.VOLUME_UP to "volume_up",
            MusicIntent.VOLUME_DOWN to "volume_down",
            MusicIntent.VOLUME_SET to "volume_set",
            MusicIntent.MUSIC_PAUSE to "pause",
            MusicIntent.MUSIC_STOP to "stop",
            MusicIntent.MUSIC_RESUME to "resume",
            MusicIntent.MUSIC_NEXT to "next",
            MusicIntent.MUSIC_PREV to "prev",
            MusicIntent.MUSIC_NOW_PLAYING to "now_playing",
        )

        private val TYPE_TO_ACTION = mapOf(
            "artist" to "play_artist",
            "genre" to "play_genre",
            "track" to "play_track",
            "mood" to "play_mood",
            "search" to "play_search",
            "local_liked" to "play_local_liked",
            "wave_lang" to "play_wave",
        )
    }
}

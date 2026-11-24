package com.maks.music.core.personality

import com.maks.music.core.AssetProvider
import com.maks.music.core.SpeechTextCleaner
import com.maks.music.core.TimeBucket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

data class PhraseEntry(
    val text: String,
    val style: String = "normal",
    val weight: Double = 1.0,
    val night: Boolean = false,
    val emotion: String = "neutral",
    val rate: String? = null,
    val pitch: String? = null,
)

data class MaksReply(
    val rawText: String,
    val displayText: String,
    val speechChunks: List<SpeechTextCleaner.SpeechChunk>,
    val emotion: String = "neutral",
    val speechRate: Float = 1.04f,
    val speechPitch: Float = 0.96f,
)

/**
 * Порт personality_core/engine.py + phrase.py + prosody.py под локальный Android TTS.
 * Загружает все музыкальные JSON-файлы из assets/responses, учитывает время суток (ночной стиль),
 * антиповтор последних 5 фраз и переводит rate/pitch в параметры Android TextToSpeech.
 */
class PersonalityEngine(
    assets: AssetProvider,
    val selfState: SelfState = SelfState(),
    private val random: Random = Random.Default,
) {
    private val phrasesByKey: Map<String, List<PhraseEntry>>
    private val recentTexts = ArrayDeque<String>()

    init {
        val loaded = LinkedHashMap<String, MutableList<PhraseEntry>>()
        for (fileName in assets.listFiles("responses")) {
            if (!fileName.endsWith(".json")) continue
            val raw = assets.readText("responses/$fileName") ?: continue
            runCatching {
                val root = Json.parseToJsonElement(raw).jsonObject
                for ((key, value) in root) {
                    if (value !is JsonArray) continue
                    val list = loaded.getOrPut(key) { mutableListOf() }
                    for (el in value) {
                        when (el) {
                            is JsonObject -> {
                                val text = el["text"]?.jsonPrimitive?.content ?: continue
                                val style = el["style"]?.jsonPrimitive?.content ?: "normal"
                                val isNightEntry = (el["night"]?.jsonPrimitive?.booleanOrNull ?: false) ||
                                    (el["drowsy"]?.jsonPrimitive?.booleanOrNull ?: false) ||
                                    style == "night"
                                list += PhraseEntry(
                                    text = text,
                                    style = style,
                                    weight = el["weight"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
                                    night = isNightEntry,
                                    emotion = el["emotion"]?.jsonPrimitive?.content ?: "neutral",
                                    rate = el["rate"]?.jsonPrimitive?.content,
                                    pitch = el["pitch"]?.jsonPrimitive?.content,
                                )
                            }
                            else -> {
                                val text = el.jsonPrimitive.content
                                if (text.isNotBlank()) list += PhraseEntry(text = text)
                            }
                        }
                    }
                }
            }
        }
        phrasesByKey = loaded
    }

    fun respond(
        phraseSet: String,
        params: Map<String, String> = emptyMap(),
        fallbackText: String? = null,
        hour: Int = TimeBucket.currentHour(),
    ): MaksReply {
        selfState.onCommand(phraseSet)
        val isNight = TimeBucket.isNight(hour)
        val targetStyle = selfState.pickStyle(hour)

        val effectiveSet = if (isNight && phrasesByKey.containsKey("${phraseSet}_NIGHT")) {
            "${phraseSet}_NIGHT"
        } else {
            phraseSet
        }

        val candidates = phrasesByKey[effectiveSet].orEmpty()
        val chosen = pickWeighted(candidates, targetStyle, isNight)

        val template = chosen?.text ?: fallbackText ?: DEFAULT_FALLBACKS[phraseSet] ?: ""
        val filled = substitute(template, params)

        if (chosen != null) {
            recentTexts.addLast(chosen.text)
            while (recentTexts.size > RECENT_LIMIT) recentTexts.removeFirst()
        }

        val rate = parseSpeechRate(chosen?.rate, isNight)
        val pitch = parseSpeechPitch(chosen?.pitch, isNight)
        val emotion = chosen?.emotion ?: selfState.mood

        return MaksReply(
            rawText = filled,
            displayText = SpeechTextCleaner.toDisplayText(filled),
            speechChunks = SpeechTextCleaner.toSpeechChunks(filled),
            emotion = emotion,
            speechRate = rate,
            speechPitch = pitch,
        )
    }

    private fun pickWeighted(entries: List<PhraseEntry>, style: String, isNight: Boolean): PhraseEntry? {
        if (entries.isEmpty()) return null
        val filteredByNight = if (isNight) {
            entries.filter { it.night || it.style == "night" }.ifEmpty { entries }
        } else {
            entries.filter { !it.night && it.style != "night" }.ifEmpty { entries }
        }

        val nonRecent = filteredByNight.filter { it.text !in recentTexts }.ifEmpty { filteredByNight }

        val weighted = nonRecent.map { entry ->
            var w = entry.weight.coerceAtLeast(0.1)
            if (entry.style == style) w *= 1.4
            entry to w
        }
        val total = weighted.sumOf { it.second }
        if (total <= 0.0) return nonRecent.first()

        var r = random.nextDouble() * total
        for ((entry, w) in weighted) {
            r -= w
            if (r <= 0.0) return entry
        }
        return weighted.last().first
    }

    private fun substitute(template: String, params: Map<String, String>): String {
        var result = template
        for ((k, v) in params) {
            result = result.replace("{$k}", v)
        }
        return UNFILLED_PLACEHOLDER.replace(result, "").trim()
    }

    private fun parseSpeechRate(rateStr: String?, isNight: Boolean): Float {
        val base = if (isNight) 0.92f else 1.04f
        if (rateStr.isNullOrBlank()) return base
        val pct = rateStr.replace("%", "").replace("+", "").trim().toFloatOrNull() ?: return base
        return (base * (1f + pct / 100f)).coerceIn(0.75f, 1.35f)
    }

    private fun parseSpeechPitch(pitchStr: String?, isNight: Boolean): Float {
        val base = if (isNight) 0.90f else 0.96f
        if (pitchStr.isNullOrBlank()) return base
        val hz = pitchStr.replace("Hz", "", ignoreCase = true).replace("+", "").trim().toFloatOrNull() ?: return base
        return (base + hz / 150f).coerceIn(0.75f, 1.25f)
    }

    companion object {
        private const val RECENT_LIMIT = 5
        private val UNFILLED_PLACEHOLDER = Regex("""\{[a-zA-Z0-9_]+\}""")

        private val DEFAULT_FALLBACKS = mapOf(
            "PLAY_WAVE" to "Включаю вашу волну",
            "PLAY_ARTIST" to "Включаю {artist}",
            "PLAY_GENRE" to "Включаю {genre}",
            "PLAY_MOOD" to "Включаю {mood}",
            "PLAY_TRACK" to "Включаю {title}",
            "PLAY_LOCAL_LIKED" to "Включаю ваши любимые треки",
            "PLAY_SEARCH_NOT_FOUND" to "Не нашёл в локальной библиотеке: {query}",
            "MUSIC_EMPTY_LIBRARY" to "На устройстве пока не найдено аудиофайлов",
            "LIKE_ADDED" to "Сохранил в любимые",
            "DISLIKE_ADDED" to "Убрал, больше не буду ставить",
            "NEXT_TRACK" to "Дальше",
            "PREV_TRACK" to "Назад",
            "PAUSE" to "Пауза",
            "RESUME" to "Продолжаю",
            "STOP" to "Остановил",
            "VOLUME_UP" to "Громче",
            "VOLUME_DOWN" to "Тише",
            "VOLUME_SET" to "Громкость {level}",
            "NOW_PLAYING" to "Сейчас играет {artist} — {title}",
            "NO_TRACK_PLAYING" to "Сейчас ничего не играет",
            "CANCELLED" to "Хорошо, отменил",
            "NOT_UNDERSTOOD" to "Не совсем понял, повторите музыкальную команду",
        )
    }
}
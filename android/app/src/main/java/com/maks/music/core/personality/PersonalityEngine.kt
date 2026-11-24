package com.maks.music.core.personality

import com.maks.music.core.AssetProvider
import com.maks.music.core.SpeechTextCleaner
import com.maks.music.core.TimeBucket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
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

private data class ComposerRule(
    val opening: String? = null,
    val core: String? = null,
    val closing: String? = null,
)

/**
 * Порт personality_core/engine.py + composer.py + state.py + prosody.py под локальный Android TTS.
 * Собирает живые ответы из слотов opening + core + closing по responses/composer.json,
 * ведёт анти-повтор по каждому phraseSet и адаптирует темп/тон под время суток.
 */
class PersonalityEngine(
    assets: AssetProvider,
    val selfState: SelfState = SelfState(),
    private val random: Random = Random.Default,
) {
    private val phrasesByKey: Map<String, List<PhraseEntry>>
    private val composerRules: Map<String, ComposerRule>
    private val lastPickBySet = HashMap<String, String>()
    private val recentTexts = ArrayDeque<String>()

    init {
        val loaded = LinkedHashMap<String, MutableList<PhraseEntry>>()
        val rules = LinkedHashMap<String, ComposerRule>()

        for (fileName in assets.listFiles("responses")) {
            if (!fileName.endsWith(".json")) continue
            val raw = assets.readText("responses/$fileName") ?: continue

            if (fileName == "composer.json") {
                runCatching {
                    val root = Json.parseToJsonElement(raw).jsonObject
                    for ((key, value) in root) {
                        val obj = value as? JsonObject ?: continue
                        rules[key] = ComposerRule(
                            opening = obj["opening"]?.jsonPrimitive?.content,
                            core = obj["core"]?.jsonPrimitive?.content,
                            closing = obj["closing"]?.jsonPrimitive?.content,
                        )
                    }
                }
                continue
            }

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
        composerRules = rules
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

        val rule = composerRules[effectiveSet]
        val allowFraming = !isNight && selfState.energy >= 0.35

        val parts = mutableListOf<String>()
        var coreChosen: PhraseEntry? = null

        // 1. Opening (например, «Сию минуту», «Один момент»)
        if (allowFraming && rule?.opening != null && random.nextFloat() < 0.70f) {
            pickFromSet(rule.opening, targetStyle, isNight)?.let { entry ->
                parts += substitute(entry.text, params)
            }
        }

        // 2. Core (основная фраза)
        val coreSetName = rule?.core ?: effectiveSet
        coreChosen = pickFromSet(coreSetName, targetStyle, isNight)
        val coreText = coreChosen?.text ?: fallbackText ?: DEFAULT_FALLBACKS[phraseSet] ?: ""
        val filledCore = substitute(coreText, params)
        if (filledCore.isNotBlank()) {
            parts += filledCore
        }

        // 3. Closing (например, «Поехали», «Наслаждайтесь»)
        if (allowFraming && rule?.closing != null && random.nextFloat() < 0.65f) {
            pickFromSet(rule.closing, targetStyle, isNight)?.let { entry ->
                parts += substitute(entry.text, params)
            }
        }

        val fullRaw = joinParts(parts)
        val rate = parseSpeechRate(coreChosen?.rate, isNight)
        val pitch = parseSpeechPitch(coreChosen?.pitch, isNight)
        val emotion = coreChosen?.emotion ?: selfState.mood

        return MaksReply(
            rawText = fullRaw,
            displayText = SpeechTextCleaner.toDisplayText(fullRaw),
            speechChunks = SpeechTextCleaner.toSpeechChunks(fullRaw),
            emotion = emotion,
            speechRate = rate,
            speechPitch = pitch,
        )
    }

    private fun pickFromSet(setName: String, style: String, isNight: Boolean): PhraseEntry? {
        val entries = phrasesByKey[setName].orEmpty()
        if (entries.isEmpty()) return null

        val filteredByNight = if (isNight) {
            entries.filter { it.night || it.style == "night" }.ifEmpty { entries }
        } else {
            entries.filter { !it.night && it.style != "night" }.ifEmpty { entries }
        }

        val lastForSet = lastPickBySet[setName]
        val nonRepeat = filteredByNight
            .filter { it.text != lastForSet && it.text !in recentTexts }
            .ifEmpty { filteredByNight.filter { it.text != lastForSet } }
            .ifEmpty { filteredByNight }

        val weighted = nonRepeat.map { entry ->
            var w = entry.weight.coerceAtLeast(0.1)
            if (entry.style == style) w *= 1.4
            entry to w
        }
        val total = weighted.sumOf { it.second }
        val chosen = if (total <= 0.0) {
            nonRepeat.first()
        } else {
            var r = random.nextDouble() * total
            var picked = weighted.last().first
            for ((entry, w) in weighted) {
                r -= w
                if (r <= 0.0) {
                    picked = entry
                    break
                }
            }
            picked
        }

        lastPickBySet[setName] = chosen.text
        recentTexts.addLast(chosen.text)
        while (recentTexts.size > RECENT_LIMIT) recentTexts.removeFirst()
        return chosen
    }

    private fun joinParts(parts: List<String>): String {
        val cleaned = parts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return ""
        if (cleaned.size == 1) return cleaned[0]
        val sb = StringBuilder()
        cleaned.forEachIndexed { idx, part ->
            sb.append(part)
            if (idx < cleaned.lastIndex) {
                val endsWithMark = part.endsWith(".") || part.endsWith("!") ||
                    part.endsWith("?") || part.endsWith("…") || part.endsWith(">")
                if (!endsWithMark) {
                    sb.append(" sil<[120]>")
                }
                sb.append(' ')
            }
        }
        return sb.toString().trim()
    }

    private fun substitute(template: String, params: Map<String, String>): String {
        var result = template
        for ((k, v) in params) {
            result = result.replace("{$k}", v)
        }
        return UNFILLED_PLACEHOLDER.replace(result, "").trim()
    }

    private fun parseSpeechRate(rateStr: String?, isNight: Boolean): Float {
        val base = if (isNight) 0.92f else 1.02f
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
        private const val RECENT_LIMIT = 8
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
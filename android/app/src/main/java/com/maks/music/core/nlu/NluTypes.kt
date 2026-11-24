package com.maks.music.core.nlu

/**
 * Музыкальное подмножество интентов Макса (без погоды, ПК, напоминаний и LLM).
 */
enum class MusicIntent {
    MUSIC_PLAY,
    MUSIC_WAVE,
    MUSIC_LIKE,
    MUSIC_DISLIKE,
    MUSIC_LOCAL_LIKED,
    MUSIC_PAUSE,
    MUSIC_STOP,
    MUSIC_RESUME,
    MUSIC_NEXT,
    MUSIC_PREV,
    MUSIC_NOW_PLAYING,
    VOLUME_UP,
    VOLUME_DOWN,
    VOLUME_SET,
    SMALLTALK,
    UNKNOWN;

    companion object {
        fun fromName(name: String): MusicIntent =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

enum class EntityState {
    FOUND,
    ABSENT,
    UNRESOLVED,
    AMBIGUOUS,
}

data class EntityCandidate(
    val type: String,
    val value: String,
    val score: Double,
    val source: String = "",
)

data class Entity(
    val type: String,
    val value: String? = null,
    val state: EntityState = EntityState.ABSENT,
    val matchConfidence: Double = 0.0,
    val candidates: List<EntityCandidate> = emptyList(),
    val source: String = "",
    val language: String? = null, // "russian" | "not-russian" | null
)

data class Command(
    val intent: MusicIntent,
    var entity: Entity? = null,
    var action: String? = null,
    val confidence: Double = 1.0,
    val normalizedText: String,
    val originalText: String,
    val context: MutableMap<String, Any> = mutableMapOf(),
)

enum class DecisionAction {
    EXECUTE,
    CLARIFY,
    FALLBACK,
}

data class Decision(
    val command: Command,
    val action: DecisionAction,
    val reason: String,
    val confidence: Double = 0.0,
    val phraseSet: String? = null,
)

data class PendingCommand(
    val kind: String,
    val payload: Map<String, String> = emptyMap(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val ttlMs: Long = 60_000L,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - createdAtMs > ttlMs
}

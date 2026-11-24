package com.maks.music.core.nlu

/**
 * Порт brain/nlu/pipeline.py: текст → Decision.
 * Не говорит и не запускает плеер — только преобразует фразу в типизированное решение.
 */
class NluPipeline(
    val normalizer: TextNormalizer,
    val intentResolver: MusicIntentResolver,
    val entityExtractor: MusicEntityExtractor,
    val decisionEngine: MusicDecisionEngine,
) {
    fun process(rawText: String): Decision {
        val normalized = normalizer.normalize(rawText)
        if (normalized.isEmpty()) {
            val emptyCmd = Command(
                intent = MusicIntent.UNKNOWN,
                normalizedText = "",
                originalText = rawText,
                confidence = 0.0,
            )
            return Decision(emptyCmd, DecisionAction.FALLBACK, "empty normalized text", 0.0)
        }

        val (intent, confidence) = intentResolver.resolve(normalized)
        val command = Command(
            intent = intent,
            confidence = confidence,
            normalizedText = normalized,
            originalText = rawText,
        )

        if (intent == MusicIntent.MUSIC_PLAY || intent == MusicIntent.MUSIC_WAVE || intent == MusicIntent.MUSIC_LOCAL_LIKED) {
            command.entity = entityExtractor.extract(normalized, intent)
        }

        return decisionEngine.resolve(command)
    }
}

package com.maks.music.core.memory

/**
 * Точный порт brain/appraisal/policy.py + history.py:
 *   1. Если пользователь пропускает одного артиста 3 раза (окно 30 мин) → APPRAISAL_SKIP_3X.
 *   2. Если пользователь делает 3 любых скипа за 1 минуту → NEXT_OFTEN.
 */
class AppraisalPolicy(
    private val skipThreshold: Int = 3,
    private val windowMs: Long = 30 * 60 * 1000L,
    private val cooldownMs: Long = 10 * 60 * 1000L,
    private val globalSkipThreshold: Int = 3,
    private val globalWindowMs: Long = 60 * 1000L,
    private val globalCooldownMs: Long = cooldownMs,
) {
    data class AppraisalDecision(
        val shouldReact: Boolean,
        val phraseSet: String? = null,
        val artistKey: String = "",
        val artistDisplay: String = "",
    )

    private val skipTimestamps = HashMap<String, ArrayDeque<Long>>()
    private val lastReactionAt = HashMap<String, Long>()

    private val globalSkipTimestamps = ArrayDeque<Long>()
    private var lastGlobalReactionAt: Long? = null

    @Synchronized
    fun onTrackFinishedOrLiked(artist: String) {
        val key = normalizeKey(artist)
        if (key.isNotEmpty()) skipTimestamps.remove(key)
        globalSkipTimestamps.clear()
    }

    @Synchronized
    fun onTrackDisliked(artist: String) {
        val key = normalizeKey(artist)
        if (key.isNotEmpty()) skipTimestamps.remove(key)
    }

    @Synchronized
    fun onTrackSkipped(artist: String, nowMs: Long = System.currentTimeMillis()): AppraisalDecision {
        val key = normalizeKey(artist)

        // Обновляем общий счётчик скипов (3 любых скипа за 60 сек)
        globalSkipTimestamps.addLast(nowMs)
        while (globalSkipTimestamps.isNotEmpty() && (nowMs - globalSkipTimestamps.first()) > globalWindowMs) {
            globalSkipTimestamps.removeFirst()
        }

        // 1. Проверка 3 скипов одного артиста (приоритетнее общей реакции)
        if (key.isNotEmpty()) {
            val queue = skipTimestamps.getOrPut(key) { ArrayDeque() }
            queue.addLast(nowMs)
            while (queue.isNotEmpty() && (nowMs - queue.first()) > windowMs) {
                queue.removeFirst()
            }

            if (queue.size >= skipThreshold) {
                val last = lastReactionAt[key]
                if (last == null || (nowMs - last) >= cooldownMs) {
                    lastReactionAt[key] = nowMs
                    lastGlobalReactionAt = nowMs
                    queue.clear()
                    globalSkipTimestamps.clear()
                    return AppraisalDecision(
                        shouldReact = true,
                        phraseSet = "APPRAISAL_SKIP_3X",
                        artistKey = key,
                        artistDisplay = artist.trim(),
                    )
                }
            }
        }

        // 2. Проверка 3 любых скипов за минуту → NEXT_OFTEN
        if (globalSkipTimestamps.size >= globalSkipThreshold) {
            val lastGlobal = lastGlobalReactionAt
            if (lastGlobal == null || (nowMs - lastGlobal) >= globalCooldownMs) {
                lastGlobalReactionAt = nowMs
                globalSkipTimestamps.clear()
                return AppraisalDecision(
                    shouldReact = true,
                    phraseSet = "NEXT_OFTEN",
                    artistKey = key,
                    artistDisplay = artist.trim(),
                )
            }
        }

        return AppraisalDecision(shouldReact = false, artistKey = key, artistDisplay = artist.trim())
    }

    private fun normalizeKey(artist: String): String = artist.trim().lowercase()
}
package com.maks.music.core.memory

/**
 * Точный порт brain/appraisal/policy.py + history.py:
 * если пользователь пропускает одного артиста 3 раза подряд (за окно 30 мин),
 * Макс реагирует (APPRAISAL_SKIP_3X) и предлагает убрать артиста из волны (cooldown 10 мин).
 */
class AppraisalPolicy(
    private val skipThreshold: Int = 3,
    private val windowMs: Long = 30 * 60 * 1000L,
    private val cooldownMs: Long = 10 * 60 * 1000L,
) {
    data class AppraisalDecision(
        val shouldReact: Boolean,
        val phraseSet: String? = null,
        val artistKey: String = "",
        val artistDisplay: String = "",
    )

    private val skipTimestamps = HashMap<String, ArrayDeque<Long>>()
    private val lastReactionAt = HashMap<String, Long>()

    @Synchronized
    fun onTrackFinishedOrLiked(artist: String) {
        val key = normalizeKey(artist)
        if (key.isNotEmpty()) skipTimestamps.remove(key)
    }

    @Synchronized
    fun onTrackDisliked(artist: String) {
        val key = normalizeKey(artist)
        if (key.isNotEmpty()) skipTimestamps.remove(key)
    }

    @Synchronized
    fun onTrackSkipped(artist: String, nowMs: Long = System.currentTimeMillis()): AppraisalDecision {
        val key = normalizeKey(artist)
        if (key.isEmpty()) return AppraisalDecision(shouldReact = false)

        val queue = skipTimestamps.getOrPut(key) { ArrayDeque() }
        queue.addLast(nowMs)
        while (queue.isNotEmpty() && (nowMs - queue.first()) > windowMs) {
            queue.removeFirst()
        }

        if (queue.size < skipThreshold) {
            return AppraisalDecision(shouldReact = false, artistKey = key, artistDisplay = artist.trim())
        }

        val last = lastReactionAt[key]
        if (last != null && (nowMs - last) < cooldownMs) {
            return AppraisalDecision(shouldReact = false, artistKey = key, artistDisplay = artist.trim())
        }

        lastReactionAt[key] = nowMs
        queue.clear()
        return AppraisalDecision(
            shouldReact = true,
            phraseSet = "APPRAISAL_SKIP_3X",
            artistKey = key,
            artistDisplay = artist.trim(),
        )
    }

    private fun normalizeKey(artist: String): String = artist.trim().lowercase()
}

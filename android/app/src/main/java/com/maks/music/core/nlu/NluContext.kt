package com.maks.music.core.nlu

/**
 * Потокобезопасное хранилище pending-предложений Макса с TTL (60 сек).
 * Пример: после 3 скипов подряд Макс спрашивает «Убрать артиста из волны?» —
 * здесь хранится ожидание ответа «Да / Нет».
 */
class NluContext {
    @Volatile
    private var pending: PendingCommand? = null

    @Synchronized
    fun setPending(command: PendingCommand) {
        pending = command
    }

    @Synchronized
    fun getPending(nowMs: Long = System.currentTimeMillis()): PendingCommand? {
        val current = pending ?: return null
        if (current.isExpired(nowMs)) {
            pending = null
            return null
        }
        return current
    }

    @Synchronized
    fun hasPending(nowMs: Long = System.currentTimeMillis()): Boolean = getPending(nowMs) != null

    @Synchronized
    fun popPending(nowMs: Long = System.currentTimeMillis()): PendingCommand? {
        val current = getPending(nowMs)
        pending = null
        return current
    }

    @Synchronized
    fun clear() {
        pending = null
    }
}

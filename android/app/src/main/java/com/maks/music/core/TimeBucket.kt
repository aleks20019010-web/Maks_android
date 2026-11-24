package com.maks.music.core

import java.util.Calendar

/**
 * Порт shared/time_bucket.py: временные корзины для привычек и ночного стиля Макса.
 */
object TimeBucket {
    fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    fun bucketForHour(hour: Int): String = when (hour) {
        in 6..11 -> "morning"
        in 12..17 -> "day"
        in 18..22 -> "evening"
        else -> "night"
    }

    fun current(): String = bucketForHour(currentHour())

    fun isNight(hour: Int = currentHour()): Boolean = hour >= 23 || hour < 8
}

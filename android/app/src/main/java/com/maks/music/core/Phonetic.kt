package com.maks.music.core

import kotlin.math.max
import kotlin.math.min

/**
 * Порт workers/music/phon.py + лёгкий fuzzy-матчинг (замена rapidfuzz без нативных зависимостей).
 */
object Phonetic {
    private const val VOWELS = "аеёиоуыэюя"

    private val SOFT_SIGN_VOWEL = Regex("""[ьъ]([яеюи])""")
    private val I_VOWEL = Regex("""и([яеюё])""")
    private val VOWEL_I_VOWEL = Regex("""([$VOWELS])и([$VOWELS])""")
    private val START_OR_VOWEL_YA = Regex("""(^|[$VOWELS])я""")
    private val START_OR_VOWEL_YU = Regex("""(^|[$VOWELS])ю""")
    private val SOFT_HARD_SIGN = Regex("""[ьъ]""")
    private val MULTI_J = Regex("""й+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}\s]+""")
    private val SPACES = Regex("""\s+""")

    private val VOWEL_MAP = mapOf(
        "я" to "а",
        "е" to "э",
        "ю" to "у",
        "и" to "и",
    )

    /** Точный порт workers/music/phon.py */
    fun phon(input: String): String {
        var w = input.lowercase().replace('ё', 'е')
        w = SOFT_SIGN_VOWEL.replace(w) { m -> "й" + (VOWEL_MAP[m.groupValues[1]] ?: m.groupValues[1]) }
        w = I_VOWEL.replace(w) { m -> "й" + (VOWEL_MAP[m.groupValues[1]] ?: m.groupValues[1]) }
        w = VOWEL_I_VOWEL.replace(w, "$1й$2")
        w = START_OR_VOWEL_YA.replace(w, "$1йа")
        w = START_OR_VOWEL_YU.replace(w, "$1йу")
        w = SOFT_HARD_SIGN.replace(w, "")
        return MULTI_J.replace(w, "й")
    }

    /** Нормализация для сравнения строк */
    fun cleanKey(input: String): String {
        return SPACES.replace(
            NON_WORD.replace(input.lowercase().replace('ё', 'е'), " "),
            " "
        ).trim()
    }

    /** Сходство двух строк в диапазоне 0.0..1.0 (аналог fuzz.ratio / SequenceMatcher) */
    fun ratio(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dist = levenshtein(a, b)
        val total = a.length + b.length
        return max(0.0, 1.0 - (2.0 * dist) / total)
    }

    /**
     * Аналог rapidfuzz token_set_ratio (0.0..1.0):
     * устойчив к перестановке слов и лишним словам в запросе.
     */
    fun tokenSetRatio(a: String, b: String): Double {
        val tokensA = cleanKey(a).split(' ').filter { it.isNotEmpty() }.toSet()
        val tokensB = cleanKey(b).split(' ').filter { it.isNotEmpty() }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0

        val intersection = tokensA.intersect(tokensB).sorted().joinToString(" ")
        val diffA = (tokensA - tokensB).sorted().joinToString(" ")
        val diffB = (tokensB - tokensA).sorted().joinToString(" ")

        val combinedA = listOf(intersection, diffA).filter { it.isNotEmpty() }.joinToString(" ")
        val combinedB = listOf(intersection, diffB).filter { it.isNotEmpty() }.joinToString(" ")

        val scores = listOf(
            ratio(intersection, combinedA),
            ratio(intersection, combinedB),
            ratio(combinedA, combinedB),
        )
        return scores.maxOrNull() ?: 0.0
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            val c1 = s1[i - 1]
            for (j in 1..n) {
                val cost = if (c1 == s2[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }
}

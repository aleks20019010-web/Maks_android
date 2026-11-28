package com.maks.music.media

/**
 * Граф близости жанров для плавного перехода на смежные стили,
 * когда основной жанр заканчивается в библиотеке или Яндекс отдаёт мало треков.
 */
object GenreSimilarity {

    data class GenreBucket(
        val canonical: String,
        val aliases: List<String>,
        val neighbors: List<String>,
        val yandexSlugs: List<String>,
    )

    private val buckets: List<GenreBucket> = listOf(
        GenreBucket(
            canonical = "pop",
            aliases = listOf("pop", "поп", "synthpop", "синти-поп", "eurodance", "эстрада"),
            neighbors = listOf("dance", "indie", "rnb", "electronic"),
            yandexSlugs = listOf("pop", "ruspop", "dance", "indie"),
        ),
        GenreBucket(
            canonical = "dance",
            aliases = listOf("dance", "disco", "club", "танцевальная", "диско"),
            neighbors = listOf("pop", "electronic", "rnb"),
            yandexSlugs = listOf("dance", "pop", "electronic"),
        ),
        GenreBucket(
            canonical = "electronic",
            aliases = listOf("electronic", "electro", "synth", "dnb", "drum", "house", "techno", "trance", "idm", "электроника"),
            neighbors = listOf("dance", "pop", "ambient", "indie"),
            yandexSlugs = listOf("electronic", "dance", "ambient", "pop"),
        ),
        GenreBucket(
            canonical = "indie",
            aliases = listOf("indie", "alternative", "folk", "britpop", "инди", "альтернатива", "фолк"),
            neighbors = listOf("rock", "pop", "electronic", "jazz"),
            yandexSlugs = listOf("indie", "alternative", "rock", "pop"),
        ),
        GenreBucket(
            canonical = "rock",
            aliases = listOf("rock", "punk", "grunge", "alt", "рок", "панк", "гранж", "русский рок"),
            neighbors = listOf("indie", "metal", "pop"),
            yandexSlugs = listOf("rock", "rusrock", "alternative", "indie", "metal"),
        ),
        GenreBucket(
            canonical = "metal",
            aliases = listOf("metal", "heavy", "thrash", "core", "death", "doom", "industrial", "метал", "хард"),
            neighbors = listOf("rock", "indie"),
            yandexSlugs = listOf("metal", "rock", "hardrock"),
        ),
        GenreBucket(
            canonical = "rap",
            aliases = listOf("rap", "hip-hop", "hip hop", "trap", "рэп", "хип-хоп", "трэп"),
            neighbors = listOf("rnb", "electronic", "pop"),
            yandexSlugs = listOf("rap", "rusrap", "rnb", "electronic"),
        ),
        GenreBucket(
            canonical = "rnb",
            aliases = listOf("r&b", "rnb", "soul", "funk", "соул", "фанк"),
            neighbors = listOf("pop", "rap", "jazz", "dance"),
            yandexSlugs = listOf("rnb", "soul", "pop", "jazz"),
        ),
        GenreBucket(
            canonical = "jazz",
            aliases = listOf("jazz", "blues", "lofi", "lo-fi", "джаз", "блюз"),
            neighbors = listOf("rnb", "ambient", "indie"),
            yandexSlugs = listOf("jazz", "blues", "lofi", "ambient"),
        ),
        GenreBucket(
            canonical = "ambient",
            aliases = listOf("classical", "ambient", "neoclassical", "soundtrack", "chill", "instrumental", "классика", "эмбиент"),
            neighbors = listOf("jazz", "electronic", "indie"),
            yandexSlugs = listOf("ambient", "classical", "lofi", "electronic"),
        ),
    )

    fun resolveBucket(rawGenre: String): GenreBucket? {
        val clean = rawGenre.trim().lowercase().removePrefix("genre:")
        if (clean.isBlank()) return null
        return buckets.firstOrNull { b ->
            b.canonical == clean || b.aliases.any { alias -> clean.contains(alias) }
        }
    }

    /**
     * Множитель совместимости жанра трека с целевым жанром:
     * - 1.0  : точное попадание в жанр
     * - 0.65 : близкий соседний жанр (подключается при нехватке основного)
     * - 0.0  : контрастный жанр (не подмешивается)
     */
    fun matchWeight(track: LocalTrack, targetGenreRaw: String): Double {
        val targetClean = targetGenreRaw.trim().lowercase().removePrefix("genre:")
        if (targetClean.isBlank()) return 1.0

        val hay = "${track.genre} ${track.album}".lowercase()
        val targetBucket = resolveBucket(targetClean)

        if (targetBucket == null) {
            return if (hay.contains(targetClean)) 1.0 else 0.0
        }

        if (targetBucket.aliases.any { hay.contains(it) }) {
            return 1.0
        }

        val neighborAliases = targetBucket.neighbors
            .mapNotNull { n -> buckets.firstOrNull { it.canonical == n } }
            .flatMap { it.aliases }

        if (neighborAliases.any { hay.contains(it) }) {
            return 0.65
        }

        return 0.0
    }

    fun yandexStationChain(targetGenreRaw: String?): List<String> {
        val clean = targetGenreRaw?.trim()?.lowercase()?.removePrefix("genre:").orEmpty()
        if (clean.isBlank()) return listOf("user:onyourwave")
        val bucket = resolveBucket(clean)
        val slugs = bucket?.yandexSlugs ?: listOf(clean)
        return slugs.distinct().map { "genre:$it" }
    }
}
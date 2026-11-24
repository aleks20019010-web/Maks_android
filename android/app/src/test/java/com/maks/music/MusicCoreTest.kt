package com.maks.music

import com.maks.music.core.FileSystemAssetProvider
import com.maks.music.core.Phonetic
import com.maks.music.core.SpeechTextCleaner
import com.maks.music.core.memory.AppraisalPolicy
import com.maks.music.core.nlu.DecisionAction
import com.maks.music.core.nlu.EntityState
import com.maks.music.core.nlu.LocalCatalogLookup
import com.maks.music.core.nlu.MusicDecisionEngine
import com.maks.music.core.nlu.MusicEntityExtractor
import com.maks.music.core.nlu.MusicIntent
import com.maks.music.core.nlu.MusicIntentResolver
import com.maks.music.core.nlu.NluPipeline
import com.maks.music.core.nlu.TextNormalizer
import com.maks.music.core.nlu.VolumeParser
import com.maks.music.core.personality.PersonalityEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Юнит-тесты музыкального ядра Макса (порт ключевых проверок из Python pytest).
 * Работают на чистой JVM без эмулятора Android.
 */
class MusicCoreTest {

    private lateinit var pipeline: NluPipeline
    private lateinit var volumeParser: VolumeParser
    private lateinit var personality: PersonalityEngine

    private val fakeCatalog = object : LocalCatalogLookup {
        override fun artistIndex(): Map<String, String> = mapOf(
            "ария" to "Ария",
            Phonetic.phon("ария") to "Ария",
            "король и шут" to "Король и Шут",
            "киш" to "Король и Шут",
            "цой" to "Виктор Цой",
        )

        override fun trackIndex(): Map<String, String> = mapOf(
            "рок" to "НАИВ — Рок", // Проверка FIX 2026-11-20: жанр «рок» важнее одноимённого трека!
            "шторм" to "Ария — Шторм",
        )
    }

    @Before
    fun setUp() {
        val assetsRoot = listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
        ).first { it.exists() }

        val assets = FileSystemAssetProvider(assetsRoot)
        volumeParser = VolumeParser(assets)
        pipeline = NluPipeline(
            normalizer = TextNormalizer(assets),
            intentResolver = MusicIntentResolver(assets, volumeParser),
            entityExtractor = MusicEntityExtractor(assets) { fakeCatalog },
            decisionEngine = MusicDecisionEngine(assets),
        )
        personality = PersonalityEngine(assets)
    }

    @Test
    fun `normalizer strips wake prefixes and fillers`() {
        assertEquals("включи арию", pipeline.normalizer.normalize("Макс, пожалуйста, включи Арию!"))
        assertEquals("включи рок", pipeline.normalizer.normalize("  Слушаю вас, давай включи рок  "))
    }

    @Test
    fun `speech text cleaner strips yandex markup`() {
        val raw = "sil<[150]> Ария уж+е тр+етий раз подряд. sil<[200]> М+ожет sil<[50]> убр+ать из волн+ы?"
        val display = SpeechTextCleaner.toDisplayText(raw)
        assertFalse(display.contains("+"))
        assertFalse(display.contains("sil<"))
        assertTrue(display.contains("Ария уже третий раз подряд"))

        val chunks = SpeechTextCleaner.toSpeechChunks(raw)
        assertTrue(chunks.size >= 2)
    }

    @Test
    fun `music play resolves wave, russian wave, artist with russian case, genre over track, mood, and search`() {
        val wave = pipeline.process("Макс, включи музыку")
        assertEquals(DecisionAction.EXECUTE, wave.action)
        assertEquals("play_wave", wave.command.action)

        val rusWave = pipeline.process("включи русскую музыку")
        assertEquals("play_wave", rusWave.command.action)
        assertEquals("russian", rusWave.command.entity?.language)

        // «арию» → падежная нормализация → «Ария»
        val artist = pipeline.process("включи Арию")
        assertEquals("play_artist", artist.command.action)
        assertEquals("Ария", artist.command.entity?.value)

        // FIX 2026-11-20: жанр важнее одноимённого локального трека «НАИВ — Рок»
        val genre = pipeline.process("включи рок")
        assertEquals("play_genre", genre.command.action)
        assertEquals("genre:rusrock", genre.command.entity?.value)

        val mood = pipeline.process("включи что-нибудь бодрое")
        assertEquals("play_mood", mood.command.action)
        assertEquals("Энергичное", mood.command.entity?.value)

        val search = pipeline.process("включи группу Неизвестный Горизонт")
        assertEquals("play_search", search.command.action)
        assertEquals(EntityState.FOUND, search.command.entity?.state)
        assertEquals("неизвестный горизонт", search.command.entity?.value)
    }

    @Test
    fun `bundled artist aliases resolve nicknames and foreign names offline`() {
        val gorshok = pipeline.process("включи Горшка")
        assertEquals("play_artist", gorshok.command.action)
        assertEquals("Король и Шут", gorshok.command.entity?.value)

        val tapok = pipeline.process("включи радио тапок")
        assertEquals("play_artist", tapok.command.action)
        assertEquals("RADIO TAPOK", tapok.command.entity?.value)

        val metla = pipeline.process("включи метлу")
        assertEquals("play_artist", metla.command.action)
        assertEquals("Metallica", metla.command.entity?.value)

        val metallicaAcc = pipeline.process("включи металлику")
        assertEquals("play_artist", metallicaAcc.command.action)
        assertEquals("Metallica", metallicaAcc.command.entity?.value)

        val planka = pipeline.process("включи группу Планка")
        assertEquals("play_artist", planka.command.action)
        assertEquals("Планка", planka.command.entity?.value)
    }

    @Test
    fun `controls and volume parsing work offline`() {
        assertEquals("next", pipeline.process("дальше").command.action)
        assertEquals("prev", pipeline.process("назад").command.action)
        assertEquals("pause", pipeline.process("поставь на паузу").command.action)
        assertEquals("like_current", pipeline.process("мне нравится").command.action)
        assertEquals("like_current", pipeline.process("лайк").command.action)
        assertEquals("dislike_current", pipeline.process("не нравится").command.action)
        assertEquals("play_local_liked", pipeline.process("включи мои лайки").command.action)
        assertEquals("now_playing", pipeline.process("что сейчас играет").command.action)

        val vol = pipeline.process("громкость пятьдесят")
        assertEquals(MusicIntent.VOLUME_SET, vol.command.intent)
        assertEquals(50, volumeParser.parseVolume(vol.command.originalText))
    }

    @Test
    fun `appraisal triggers on 3 skips and respects cooldown`() {
        val policy = AppraisalPolicy(skipThreshold = 3, cooldownMs = 600_000L)
        assertFalse(policy.onTrackSkipped("Ария", nowMs = 1_000L).shouldReact)
        assertFalse(policy.onTrackSkipped("Ария", nowMs = 2_000L).shouldReact)

        val third = policy.onTrackSkipped("Ария", nowMs = 3_000L)
        assertTrue(third.shouldReact)
        assertEquals("APPRAISAL_SKIP_3X", third.phraseSet)

        // Cooldown: следующие 3 скипа сразу не должны спамить
        assertFalse(policy.onTrackSkipped("Ария", nowMs = 4_000L).shouldReact)
        assertFalse(policy.onTrackSkipped("Ария", nowMs = 5_000L).shouldReact)
        assertFalse(policy.onTrackSkipped("Ария", nowMs = 6_000L).shouldReact)
    }

    @Test
    fun `personality engine loads real response assets and cleans output`() {
        val reply = personality.respond("PLAY_ARTIST", mapOf("artist" to "Ария"), hour = 14)
        assertTrue(reply.displayText.isNotBlank())
        assertFalse(reply.displayText.contains("+"))
        assertFalse(reply.displayText.contains("sil<"))
    }
}

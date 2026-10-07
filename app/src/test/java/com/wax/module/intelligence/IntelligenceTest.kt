package com.wax.module.intelligence

import com.wax.module.platform.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IntelligenceTest {
    private lateinit var store: InMemoryKeyValueStore
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        now = 1_700_000_000_000L
    }

    private fun gate() = CloudPrivacyGate(store, { now })

    // --- translation (T106-T109) ------------------------------------------------------

    @Test
    fun aChatWithoutALanguageProfileNeverTranslatesInline() {
        val coordinator = coordinator()
        val decision = coordinator.decide("chat", detectedLanguage = "de")
        assertFalse(decision.shouldTranslate)
        assertTrue(decision.reason.contains("no language profile"))
    }

    @Test
    fun onDemandDoesNotTranslateUntilAsked() {
        val profiles = ChatLanguageProfileStore(store)
        profiles.setTarget("chat", "en")
        profiles.setMode("chat", TranslationMode.ON_DEMAND)
        assertFalse(coordinator(profiles = profiles).decide("chat", "de").shouldTranslate)
    }

    @Test
    fun alwaysChatTranslatesEverythingAndAlwaysDetectedSkipsTheTargetLanguage() {
        val profiles = ChatLanguageProfileStore(store)
        profiles.setTarget("chat", "en")
        profiles.setMode("chat", TranslationMode.ALWAYS_CHAT)
        assertTrue(coordinator(profiles = profiles).decide("chat", "en").shouldTranslate)

        profiles.setMode("chat", TranslationMode.ALWAYS_DETECTED)
        assertFalse(coordinator(profiles = profiles).decide("chat", "en").shouldTranslate)
        assertTrue(coordinator(profiles = profiles).decide("chat", "de").shouldTranslate)
        assertFalse(coordinator(profiles = profiles).decide("chat", null).shouldTranslate)
    }

    @Test
    fun theLocalProviderIsPreferredAndItsResultIsCached() {
        val registry = TranslationProviderRegistry()
        val local = FakeTranslationProvider(TranslationEngine.LOCAL, isCloud = false)
        registry.register(local)
        val profiles = ChatLanguageProfileStore(store)
        profiles.setTarget("chat", "en")
        val coordinator = coordinator(registry = registry, profiles = profiles)

        val first = coordinator.translate("chat", "hello") as TranslationOutcome.Translated
        assertEquals(TranslationEngine.LOCAL, first.result.engine)
        assertFalse(first.result.fromCache)
        assertEquals(1, local.calls)

        val second = coordinator.translate("chat", "hello") as TranslationOutcome.Translated
        assertTrue(second.result.fromCache)
        assertEquals("a cache hit must not call the provider again", 1, local.calls)
    }

    @Test
    fun aCloudProviderIsBlockedUntilExplicitlyEnabled() {
        val registry = TranslationProviderRegistry()
        registry.register(FakeTranslationProvider(TranslationEngine.GOOGLE, isCloud = true))
        val profiles = ChatLanguageProfileStore(store)
        profiles.setTarget("chat", "en")
        val coordinator = coordinator(registry = registry, profiles = profiles)

        val blocked = coordinator.translate("chat", "hallo") as TranslationOutcome.Unavailable
        assertTrue(blocked.cloudBlocked)
        assertTrue(blocked.reason.contains("not enabled"))

        gate().setEnabled(CloudService.TRANSLATION_GOOGLE, true)
        val allowed = coordinator.translate("chat", "hallo") as TranslationOutcome.Translated
        assertEquals(TranslationEngine.GOOGLE, allowed.result.engine)
    }

    @Test
    fun aFailingLocalProviderFallsBackToAnAllowedCloudProvider() {
        val registry = TranslationProviderRegistry()
        registry.register(
            FakeTranslationProvider(
                TranslationEngine.LOCAL,
                isCloud = false,
                outcome = TranslationOutcome.Failed("model missing"),
            ),
        )
        registry.register(FakeTranslationProvider(TranslationEngine.DEEPL, isCloud = true))
        gate().setEnabled(CloudService.TRANSLATION_DEEPL, true)
        val profiles = ChatLanguageProfileStore(store)
        profiles.setTarget("chat", "en")

        val result = coordinator(registry = registry, profiles = profiles).translate("chat", "hallo")
        assertEquals(TranslationEngine.DEEPL, (result as TranslationOutcome.Translated).result.engine)
    }

    @Test
    fun unknownLanguagesAreNotClaimed() {
        val profiles = ChatLanguageProfileStore(store)
        assertFalse(profiles.setTarget("chat", "  "))
        assertNull(profiles.profileFor("chat"))
    }

    @Test
    fun theOutgoingPreviewLetsTheUserChooseWhatIsSent() {
        val profiles = ChatLanguageProfileStore(store)
        profiles.setTarget("chat", "en")
        val registry = TranslationProviderRegistry()
        registry.register(FakeTranslationProvider(TranslationEngine.LOCAL, isCloud = false, translated = "Hello"))
        val coordinator = coordinator(registry = registry, profiles = profiles)

        val preview = coordinator.previewOutgoing("chat", "Hallo")
        assertEquals("Hallo", preview.original)
        assertEquals("Hello", preview.translatedText)
        assertEquals("Hallo", coordinator.resolveOutgoing(OutgoingTranslationChoice.ORIGINAL, preview))
        assertEquals("Hello", coordinator.resolveOutgoing(OutgoingTranslationChoice.TRANSLATED, preview))
    }

    // --- transcription (T110-T113) ----------------------------------------------------

    @Test
    fun aTranscriptIsCachedAndInvalidatedWhenTheAudioChanges() {
        val registry = TranscriptionProviderRegistry()
        val provider = FakeTranscriptionProvider()
        registry.register(provider)
        val cache = TranscriptCache(store, maxAgeMillis = 30L * 24 * 60 * 60 * 1000, now = { now })
        val coordinator = TranscriptionCoordinator(registry, cache, gate()) { now }

        val first = coordinator.transcribe("voice-1", "fingerprint-a") as TranscriptionOutcome.Transcribed
        assertEquals(1, provider.calls)
        val second = coordinator.transcribe("voice-1", "fingerprint-a") as TranscriptionOutcome.Transcribed
        assertEquals("a cache hit must not re-run the provider", 1, provider.calls)
        assertEquals(first.transcript.fullText, second.transcript.fullText)

        coordinator.transcribe("voice-1", "fingerprint-b")
        assertEquals("changed audio must invalidate the transcript", 2, provider.calls)
        assertEquals(1, cache.size())
    }

    @Test
    fun transcriptSearchAndTimestampRenderingWork() {
        val transcript =
            Transcript(
                mediaId = "voice-1",
                mediaFingerprint = "f",
                language = "en",
                engine = TranscriptionEngine.LOCAL,
                segments =
                    listOf(
                        TranscriptSegment(0, 1_000, "hello there"),
                        TranscriptSegment(65_000, 66_000, "meeting tomorrow"),
                    ),
                createdAtMillis = now,
            )
        assertEquals(1, transcript.search("tomorrow").size)
        assertTrue(transcript.search("TOMORROW").isNotEmpty())
        assertTrue(transcript.renderWithTimestamps().contains("[01:05] meeting tomorrow"))
    }

    @Test
    fun cloudTranscriptionIsBlockedWithAnAudioSpecificExplanation() {
        val registry = TranscriptionProviderRegistry()
        registry.register(FakeTranscriptionProvider(engine = TranscriptionEngine.CLOUD, isCloud = true))
        val coordinator = TranscriptionCoordinator(registry, TranscriptCache(store), gate()) { now }
        val outcome = coordinator.transcribe("voice-1", "f") as TranscriptionOutcome.Unavailable
        assertTrue(outcome.cloudBlocked)
        assertTrue(outcome.reason.contains("voice message audio"))
        assertTrue(coordinator.cloudNotice().contains("voice message audio"))
    }

    @Test
    fun theCacheCanBeEmptiedManually() {
        val cache = TranscriptCache(store, now = { now })
        cache.put(
            Transcript("voice-1", "f", null, TranscriptionEngine.LOCAL, listOf(TranscriptSegment(0, 1, "x")), now),
        )
        assertEquals(1, cache.size())
        assertTrue(cache.remove("voice-1"))
        assertEquals(0, cache.size())
    }

    // --- summaries (T114) -------------------------------------------------------------

    @Test
    fun aSummaryNeedsSelectedMessages() {
        val summarizer = ConversationSummarizer(FakeSummaryProvider(isCloud = false), gate())
        assertTrue(summarizer.summarize(SummaryRequest("chat", emptyList())) is SummaryOutcome.Failed)
    }

    @Test
    fun aCloudSummaryIsBlockedUntilOptedIn() {
        val summarizer = ConversationSummarizer(FakeSummaryProvider(isCloud = true), gate())
        val request = SummaryRequest("chat", listOf("a", "b"))
        val blocked = summarizer.summarize(request) as SummaryOutcome.Unavailable
        assertTrue(blocked.cloudBlocked)
        assertTrue(blocked.reason.contains("selected messages"))

        gate().setEnabled(CloudService.SUMMARY_CLOUD, true)
        assertTrue(summarizer.summarize(request) is SummaryOutcome.Summarized)
    }

    @Test
    fun theGateDefaultsToEverythingOffAndStatesThePolicy() {
        val gate = gate()
        assertEquals(emptySet<CloudService>(), gate.enabledServices())
        assertTrue(gate.policyStatement.contains("on this device"))
        gate.setEnabled(CloudService.TRANSLATION_GOOGLE, true)
        assertTrue(gate.allows(CloudService.TRANSLATION_GOOGLE, CloudDataKind.MESSAGE_TEXT))
        gate.setEnabled(CloudService.TRANSLATION_GOOGLE, false)
        assertFalse(gate.allows(CloudService.TRANSLATION_GOOGLE, CloudDataKind.MESSAGE_TEXT))
    }

    // --- helpers ----------------------------------------------------------------------

    private fun coordinator(
        registry: TranslationProviderRegistry = TranslationProviderRegistry(),
        profiles: ChatLanguageProfileStore = ChatLanguageProfileStore(store),
        cache: TranslationCache = TranslationCache(store),
    ): TranslationCoordinator = TranslationCoordinator(registry, profiles, cache, gate())

    private class FakeTranslationProvider(
        override val engine: TranslationEngine,
        override val isCloud: Boolean,
        private val translated: String = "translated",
        private val outcome: TranslationOutcome? = null,
    ) : TranslationProvider {
        override val displayName: String = engine.name
        var calls = 0
            private set

        override fun translate(request: TranslationRequest): TranslationOutcome {
            calls++
            return outcome ?: TranslationOutcome.Translated(
                TranslationResult(translated, "de", engine),
            )
        }
    }

    private inner class FakeTranscriptionProvider(
        override val engine: TranscriptionEngine = TranscriptionEngine.LOCAL,
        override val isCloud: Boolean = false,
    ) : TranscriptionProvider {
        override val displayName: String = engine.name
        var calls = 0
            private set

        override fun transcribe(request: TranscriptionRequest): TranscriptionOutcome {
            calls++
            return TranscriptionOutcome.Transcribed(
                Transcript(
                    mediaId = request.mediaId,
                    mediaFingerprint = request.mediaFingerprint,
                    language = request.languageHint,
                    engine = engine,
                    segments = listOf(TranscriptSegment(0, 1_000, "transcribed")),
                    createdAtMillis = now,
                ),
            )
        }
    }

    private class FakeSummaryProvider(
        override val isCloud: Boolean,
    ) : SummaryProvider {
        override val displayName: String = "fake"

        override fun summarize(request: SummaryRequest): SummaryOutcome =
            SummaryOutcome.Summarized("summary of ${request.messageCount}", isCloud)
    }
}

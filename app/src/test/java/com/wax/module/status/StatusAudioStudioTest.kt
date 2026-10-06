package com.wax.module.status

import com.wax.module.platform.InMemoryKeyValueStore
import com.wax.module.platform.TargetApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Status Audio Studio.
 *
 * The cases are grouped the way the feature can fail: getting the plan shape wrong (post, trim,
 * split, ask, reject), getting the limit wrong, and losing the editor's state when the process
 * is killed. The planner is pure, so every boundary is asserted directly instead of by posting
 * to a device.
 */
class StatusAudioStudioTest {
    private lateinit var keyValue: InMemoryKeyValueStore
    private lateinit var drafts: StatusAudioDraftStore

    @Before
    fun setUp() {
        keyValue = InMemoryKeyValueStore()
        drafts = StatusAudioDraftStore(keyValue)
    }

    private fun source(
        container: AudioContainer? = AudioContainer.M4A,
        durationMillis: Long = 30_000L,
        fileName: String = "clip.m4a",
        hasLocationTag: Boolean = false,
    ): StatusAudioSource =
        StatusAudioSource(
            fileName = fileName,
            container = container,
            durationMillis = durationMillis,
            hasLocationTag = hasLocationTag,
        )

    private fun capability(limitMillis: Long = 60_000L): StatusAudioCapability = StatusAudioCapability.resolved(limitMillis)

    // --- plan shape ---------------------------------------------------------------------

    @Test
    fun aClipInsideTheLimitIsPostedAsItIs() {
        val plan = StatusAudioStudio.plan(source(), StatusAudioOptions(), capability())
        assertEquals(StatusAudioPlanKind.POST_AS_IS, plan.kind)
        assertTrue(plan.accepted)
        assertEquals(1, plan.segments.size)
        assertEquals(0L, plan.segments[0].startMillis)
        assertEquals(30_000L, plan.segments[0].endMillis)
        assertEquals(30_000L, plan.totalMillis)
        assertEquals("Whole clip", plan.segments[0].label)
    }

    @Test
    fun aSelectionShorterThanTheSourceIsATrim() {
        val plan =
            StatusAudioStudio.plan(
                source(durationMillis = 30_000L),
                StatusAudioOptions(trimStartMillis = 5_000L, trimEndMillis = 15_000L),
                capability(),
            )
        assertEquals(StatusAudioPlanKind.TRIM, plan.kind)
        assertEquals(5_000L, plan.segments[0].startMillis)
        assertEquals(15_000L, plan.segments[0].endMillis)
    }

    @Test
    fun anOverlongSelectionAsksBeforeDoingAnything() {
        val plan = StatusAudioStudio.plan(source(durationMillis = 120_000L), StatusAudioOptions(), capability())
        assertEquals(StatusAudioPlanKind.REQUIRES_DECISION, plan.kind)
        assertTrue(plan.accepted)
        assertTrue(plan.requiresDecision)
        assertTrue(plan.segments.isEmpty())
        assertTrue(plan.warnings.any { it.contains("Choose") })
    }

    @Test
    fun trimmingKeepsTheFirstPartUpToTheLimit() {
        val plan =
            StatusAudioStudio.plan(
                source(durationMillis = 120_000L),
                StatusAudioOptions(overflowAction = StatusAudioOverflowAction.TRIM),
                capability(),
            )
        assertEquals(StatusAudioPlanKind.TRIM, plan.kind)
        assertEquals(1, plan.segments.size)
        assertEquals(60_000L, plan.segments[0].endMillis)
        assertTrue(plan.warnings.any { it.contains("left out") })
    }

    @Test
    fun splittingProducesOrderedNumberedParts() {
        val plan =
            StatusAudioStudio.plan(
                source(durationMillis = 150_000L),
                StatusAudioOptions(overflowAction = StatusAudioOverflowAction.AUTO_SPLIT),
                capability(),
            )
        assertEquals(StatusAudioPlanKind.SPLIT, plan.kind)
        assertEquals(3, plan.segments.size)
        assertEquals(listOf(0L, 60_000L, 120_000L), plan.segments.map { it.startMillis })
        assertEquals(listOf(60_000L, 120_000L, 150_000L), plan.segments.map { it.endMillis })
        assertEquals(150_000L, plan.totalMillis)
        assertEquals("Part 1 of 3", plan.segments.first().label)
        assertEquals(3, plan.segments.last().total)
        assertEquals(listOf(1, 2, 3), plan.segments.map { it.index })
    }

    @Test
    fun splittingHonoursATrimRange() {
        val plan =
            StatusAudioStudio.plan(
                source(durationMillis = 200_000L),
                StatusAudioOptions(
                    trimStartMillis = 10_000L,
                    trimEndMillis = 130_000L,
                    overflowAction = StatusAudioOverflowAction.AUTO_SPLIT,
                ),
                capability(),
            )
        assertEquals(StatusAudioPlanKind.SPLIT, plan.kind)
        assertEquals(2, plan.segments.size)
        assertEquals(10_000L, plan.segments.first().startMillis)
        assertEquals(130_000L, plan.segments.last().endMillis)
    }

    @Test
    fun splittingRefusesMorePartsThanTheCap() {
        val plan =
            StatusAudioStudio.plan(
                source(durationMillis = 660_000L),
                StatusAudioOptions(overflowAction = StatusAudioOverflowAction.AUTO_SPLIT),
                capability(),
            )
        assertEquals(StatusAudioPlanKind.REJECT, plan.kind)
        assertEquals("too_many_segments", plan.rejection?.code)
        assertFalse(plan.accepted)
    }

    @Test
    fun cancellingRejects() {
        val plan =
            StatusAudioStudio.plan(
                source(durationMillis = 120_000L),
                StatusAudioOptions(overflowAction = StatusAudioOverflowAction.CANCEL),
                capability(),
            )
        assertEquals("cancelled", plan.rejection?.code)
        assertTrue(plan.segments.isEmpty())
    }

    // --- the limit ----------------------------------------------------------------------

    @Test
    fun theClientsLimitIsReadAndNotHardcoded() {
        val options = StatusAudioOptions(overflowAction = StatusAudioOverflowAction.AUTO_SPLIT)
        val short = StatusAudioStudio.plan(source(durationMillis = 45_000L), options, capability(30_000L))
        assertEquals(StatusAudioPlanKind.SPLIT, short.kind)
        assertEquals(2, short.segments.size)
        assertEquals(30_000L, short.limitMillis)

        val long = StatusAudioStudio.plan(source(durationMillis = 45_000L), options, capability(60_000L))
        assertEquals(StatusAudioPlanKind.POST_AS_IS, long.kind)
    }

    @Test
    fun theCompatibilityFallbackIsUsedAndExplained() {
        val plan = StatusAudioStudio.plan(source(durationMillis = 45_000L), StatusAudioOptions(), StatusAudioCapability.Unknown)
        assertEquals(StatusAudioPlanKind.POST_AS_IS, plan.kind)
        assertEquals(StatusAudioCapability.FALLBACK_MAX_MILLIS, plan.limitMillis)
        assertFalse(StatusAudioCapability.Unknown.limitResolved)
        assertTrue(plan.warnings.any { it.contains("fallback") })
    }

    // --- rejections ---------------------------------------------------------------------

    @Test
    fun anUnsupportedContainerIsRejected() {
        val plan =
            StatusAudioStudio.plan(
                source(container = AudioContainer.FLAC),
                StatusAudioOptions(),
                StatusAudioCapability.resolved(60_000L, setOf(AudioContainer.M4A)),
            )
        assertEquals(StatusAudioPlanKind.REJECT, plan.kind)
        assertEquals("unsupported_container", plan.rejection?.code)
    }

    @Test
    fun anUnknownFormatIsRejected() {
        val plan = StatusAudioStudio.plan(source(container = null), StatusAudioOptions(), capability())
        assertEquals("unknown_container", plan.rejection?.code)
        assertFalse(plan.accepted)
        assertEquals(0L, plan.limitMillis)
    }

    @Test
    fun anUnreadableDurationIsRejected() {
        val plan = StatusAudioStudio.plan(source(durationMillis = 0L), StatusAudioOptions(), capability())
        assertEquals("unknown_duration", plan.rejection?.code)
    }

    @Test
    fun aTooShortSelectionIsRejected() {
        val plan = StatusAudioStudio.plan(source(), StatusAudioOptions(trimEndMillis = 500L), capability())
        assertEquals("too_short", plan.rejection?.code)
    }

    // --- preparation --------------------------------------------------------------------

    @Test
    fun metadataIsStrippedByDefault() {
        val plan = StatusAudioStudio.plan(source(hasLocationTag = true), StatusAudioOptions(), capability())
        assertTrue(plan.strippedFields.contains("filesystem_path"))
        assertTrue(plan.strippedFields.contains("location"))
        assertFalse(plan.warnings.any { it.contains("Metadata will be posted") })
    }

    @Test
    fun metadataCanBeKeptExplicitly() {
        val plan = StatusAudioStudio.plan(source(hasLocationTag = true), StatusAudioOptions(stripMetadata = false), capability())
        assertTrue(plan.strippedFields.isEmpty())
        assertTrue(plan.warnings.any { it.contains("location tag") })
    }

    @Test
    fun aNonNativeContainerIsConverted() {
        val plan = StatusAudioStudio.plan(source(container = AudioContainer.MP3, fileName = "song.mp3"), StatusAudioOptions(), capability())
        assertTrue(plan.needsTranscode)
        assertEquals(AudioContainer.MP3, plan.transcodedFrom)
        assertTrue(plan.warnings.any { it.contains("converted") })
    }

    @Test
    fun overlappingFadesAreWarnedAbout() {
        val plan =
            StatusAudioStudio.plan(
                source(durationMillis = 30_000L),
                StatusAudioOptions(fadeInMillis = 20_000L, fadeOutMillis = 20_000L),
                capability(),
            )
        assertTrue(plan.warnings.any { it.contains("fades") })
    }

    @Test
    fun anOutOfRangeVolumeIsWarnedAbout() {
        val plan = StatusAudioStudio.plan(source(), StatusAudioOptions(volumePercent = 400), capability())
        assertTrue(plan.warnings.any { it.contains("volume") })
    }

    // --- drafts -------------------------------------------------------------------------

    @Test
    fun draftsRoundTrip() {
        val draft =
            StatusAudioDraft(
                id = "draft-1",
                target = TargetApp.WHATSAPP_BUSINESS,
                sourceUri = "content://media/external/audio/media/42",
                container = AudioContainer.OGG,
                durationMillis = 12_345L,
                options =
                    StatusAudioOptions(
                        trimStartMillis = 1_000L,
                        trimEndMillis = 9_000L,
                        fadeInMillis = 500L,
                        normalize = true,
                        volumePercent = 130,
                        overflowAction = StatusAudioOverflowAction.AUTO_SPLIT,
                    ),
                state = StatusAudioDraftState.FAILED,
                failureCode = "publish_failed",
                createdAtMillis = 1_700_000_000_000L,
            )
        val encoded = StatusAudioDraftCodec.encode(draft)
        assertEquals(draft, StatusAudioDraftCodec.decode(encoded))
        assertNull(StatusAudioDraftCodec.decode("{ not a draft"))
        assertNull(StatusAudioDraftCodec.decode(StatusAudioDraftCodec.encode(draft.copy(id = ""))))
    }

    @Test
    fun unreadableDraftsAreSkippedAndDraftsAreOrderedNewestFirst() {
        val first =
            StatusAudioDraft("a", TargetApp.WHATSAPP, "content://1", AudioContainer.M4A, 1_000L, StatusAudioOptions(), createdAtMillis = 1L)
        val second = first.copy(id = "b", createdAtMillis = 2L)
        drafts.save(first)
        drafts.save(second)
        keyValue.putString(StatusAudioDraftStore.KEY_PREFIX + "junk", "{ nope")
        assertEquals(listOf("b", "a"), drafts.all().map { it.id })
        assertNotNull(drafts.get("a"))
        assertTrue(drafts.remove("a"))
        assertFalse(drafts.remove("a"))
        drafts.clear()
        assertTrue(drafts.all().isEmpty())
    }

    @Test
    fun aDraftNeverStoresTheOriginalPath() {
        val draft = StatusAudioDraft("a", TargetApp.WHATSAPP, "content://media/1", AudioContainer.M4A, 1_000L, StatusAudioOptions())
        val encoded = StatusAudioDraftCodec.encode(draft)
        assertFalse(encoded.contains("/storage/emulated"))
        assertFalse(encoded.contains("DCIM"))
    }

    // --- container recognition ----------------------------------------------------------

    @Test
    fun containersAreRecognisedFromNamesAndTypes() {
        assertEquals(AudioContainer.MP3, AudioContainer.fromFileName("Song.MP3"))
        assertEquals(AudioContainer.OPUS, AudioContainer.fromFileName("note.opus"))
        assertNull(AudioContainer.fromFileName("notes.txt"))
        assertEquals(AudioContainer.OGG, AudioContainer.fromMimeType("audio/ogg; codecs=opus"))
        assertNull(AudioContainer.fromMimeType("video/mp4"))
    }

    @Test
    fun thePlanDescribesItselfWithoutUserData() {
        val rejected = StatusAudioStudio.plan(source(container = null), StatusAudioOptions(), capability())
        assertTrue(rejected.toDisplayLine().startsWith("rejected:"))
        val accepted = StatusAudioStudio.plan(source(), StatusAudioOptions(), capability())
        assertTrue(accepted.toDisplayLine().contains("post_as_is"))
        assertFalse(accepted.toDisplayLine().contains("clip.m4a"))
    }
}

package com.wax.module.xposed.features.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of HD Status that can be tested without a WhatsApp dex: how an ordered
 * candidate list is walked, and how the limits are clamped.
 *
 * These are exactly the decisions that used to be inline in the hooks, where a
 * regression was only observable on a device.
 */
class HdStatusResolutionTest {
    // --- candidate walking ---------------------------------------------------------

    @Test
    fun `the first candidate that matches wins`() {
        val anchor = HdStatusAnchor("target", listOf("first", "second", "third"))
        val resolution = anchor.resolve { candidate -> if (candidate == "second") "hit" else null }

        assertTrue(resolution.isResolved)
        assertEquals("hit", resolution.valueOrNull())
        assertEquals("second", (resolution as HdStatusResolution.Resolved).matchedCandidate)
    }

    @Test
    fun `candidates are tried in order and stop at the first hit`() {
        val tried = mutableListOf<String>()
        val anchor = HdStatusAnchor("target", listOf("a", "b", "c"))

        anchor.resolve { candidate ->
            tried += candidate
            if (candidate == "b") "hit" else null
        }

        assertEquals(listOf("a", "b"), tried)
    }

    @Test
    fun `a missing anchor reports every candidate it tried`() {
        val anchor = HdStatusAnchor("ProcessVideoQuality", listOf("alpha", "beta"))

        val resolution = anchor.resolve { null }

        assertFalse(resolution.isResolved)
        assertNull(resolution.valueOrNull())
        val missing = resolution as HdStatusResolution.Missing
        assertEquals("ProcessVideoQuality", missing.anchor.name)
        assertTrue(missing.reason.contains("alpha"))
        assertTrue(missing.reason.contains("beta"))
    }

    @Test
    fun `a probe that throws is a miss and does not hide a later candidate`() {
        val anchor = HdStatusAnchor("target", listOf("explodes", "works"))

        val resolution =
            anchor.resolve { candidate ->
                if (candidate == "explodes") throw NoSuchMethodException("stale anchor") else "ok"
            }

        assertTrue(resolution.isResolved)
        assertEquals("ok", resolution.valueOrNull())
    }

    @Test
    fun `an empty candidate list is missing rather than a crash`() {
        val resolution = HdStatusAnchor("target", emptyList()).resolve { "never" }

        assertFalse(resolution.isResolved)
    }

    // --- alias resolution ----------------------------------------------------------

    @Test
    fun `the newest alias wins when it exists`() {
        val present = setOf("videoMaxBitrate", "maxVideoBitrate")
        assertEquals("videoMaxBitrate", resolveFieldName(HdStatusFields.VIDEO_MAX_BITRATE) { it in present })
    }

    @Test
    fun `an older alias is used when the newest is gone`() {
        val present = setOf("maxVideoBitrate")
        assertEquals("maxVideoBitrate", resolveFieldName(HdStatusFields.VIDEO_MAX_BITRATE) { it in present })
    }

    @Test
    fun `no alias present resolves to null rather than guessing`() {
        assertNull(resolveFieldName(HdStatusFields.VIDEO_MAX_BITRATE) { false })
    }

    // --- limits --------------------------------------------------------------------

    @Test
    fun `the video size preference is honoured across the seek bar range`() {
        assertEquals(30, HdStatusLimits.videoLimitMb(30))
        assertEquals(45, HdStatusLimits.videoLimitMb(45))
        assertEquals(60, HdStatusLimits.videoLimitMb(60))
        assertEquals(90, HdStatusLimits.videoLimitMb(90))
    }

    @Test
    fun `the regression that pinned the size to 90 cannot come back`() {
        // The old code was max(preference, 90). With a seek bar topping out at 90 that
        // made every value 90, so the slider could not change anything.
        for (preference in listOf(30, 40, 60, 89, 90)) {
            assertTrue(
                "preference $preference was overridden",
                preference == HdStatusLimits.videoLimitMb(preference),
            )
        }
    }

    @Test
    fun `a corrupt stored value is clamped into the supported range`() {
        assertEquals(HdStatusLimits.MIN_VIDEO_LIMIT_MB, HdStatusLimits.videoLimitMb(0))
        assertEquals(HdStatusLimits.MIN_VIDEO_LIMIT_MB, HdStatusLimits.videoLimitMb(-5))
        assertEquals(HdStatusLimits.MAX_VIDEO_LIMIT_MB, HdStatusLimits.videoLimitMb(1000))
        assertEquals(HdStatusLimits.MAX_VIDEO_LIMIT_MB, HdStatusLimits.videoLimitMb(Int.MAX_VALUE))
    }

    @Test
    fun `the restored video ceilings are the 4K values, not the halved ones`() {
        assertEquals(3840, HdStatusLimits.MAX_VIDEO_EDGE)
        assertEquals(24_000_000, HdStatusLimits.MAX_VIDEO_BITRATE_BPS)
    }

    @Test
    fun `the image ceilings are unchanged by the video fixes`() {
        assertEquals(50 * 1024, HdStatusLimits.MAX_IMAGE_KB)
        assertEquals(100, HdStatusLimits.MAX_IMAGE_QUALITY)
        assertEquals(6000, HdStatusLimits.MAX_IMAGE_EDGE)
    }

    // --- anchors -------------------------------------------------------------------

    @Test
    fun `every anchor has at least one candidate and no blank ones`() {
        val anchors =
            listOf(
                HdStatusFields.VIDEO_LIMITS,
                HdStatusFields.VIDEO_CONFIGURATION,
                HdStatusFields.VIDEO_TRANSCODER_START,
                HdStatusFields.MEDIA_TRANSCODER_START,
                HdStatusFields.IMAGE_LIMITS,
                HdStatusFields.QUALITY_SELECTION_GATE,
                HdStatusFields.BOTTOM_BAR_CONFIG,
                HdStatusFields.RESOLUTION_CORRECTION,
            )
        for (anchor in anchors) {
            assertTrue("${anchor.name} has no candidates", anchor.candidates.isNotEmpty())
            assertTrue("${anchor.name} has a blank candidate", anchor.candidates.none { it.isBlank() })
            assertEquals("${anchor.name} has duplicate candidates", anchor.candidates.size, anchor.candidates.distinct().size)
        }
    }

    @Test
    fun `every alias list is non-empty and free of duplicates`() {
        val aliasLists =
            listOf(
                HdStatusFields.VIDEO_LIMIT_MB,
                HdStatusFields.VIDEO_MAX_EDGE,
                HdStatusFields.VIDEO_MAX_BITRATE,
                HdStatusFields.VIDEO_HIGH_BITRATE_URL,
                HdStatusFields.VIDEO_BITRATE_MODE,
                HdStatusFields.FORCE_SINGLE_TRANSCODING,
                HdStatusFields.IMAGE_MAX_KB,
                HdStatusFields.IMAGE_QUALITY,
                HdStatusFields.IMAGE_MAX_EDGE,
                HdStatusFields.SUPPORTS_HD_QUALITY,
                HdStatusFields.TARGET_WIDTH,
                HdStatusFields.TARGET_HEIGHT,
                HdStatusFields.FRAME_RATE,
                HdStatusFields.SOURCE_WIDTH,
                HdStatusFields.SOURCE_HEIGHT,
                HdStatusFields.ROTATION_ANGLE,
            )
        for (aliases in aliasLists) {
            assertTrue("empty alias list", aliases.isNotEmpty())
            assertEquals("duplicate aliases in $aliases", aliases.size, aliases.distinct().size)
        }
    }

    @Test
    fun `the image and video target sets stay distinct`() {
        // Image and video were one block, so a video failure could disable images.
        assertFalse(HdStatusFields.IMAGE_LIMITS.name == HdStatusFields.VIDEO_LIMITS.name)
        assertFalse(HdStatusFields.IMAGE_LIMITS.candidates.containsAll(HdStatusFields.VIDEO_LIMITS.candidates))
        assertFalse(HdStatusFields.VIDEO_LIMITS.candidates.containsAll(HdStatusFields.IMAGE_LIMITS.candidates))
    }
}

package com.wmods.wppenhacer.xposed.features.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The output-size clamp for the real-resolution option.
 *
 * Forcing a size the encoder rejects makes the transcoder throw and fails the whole
 * upload, so the size is reduced to an even dimension under the ceiling instead of
 * being passed through as the user framed it.
 */
class HdStatusVideoSizeTest {
    @Test
    fun `a size already under the ceiling is passed through`() {
        assertEquals(1280 to 720, HdStatusVideoSize.sanitise(1280, 720))
    }

    @Test
    fun `dimensions are made even because encoders require it`() {
        val (width, height) = HdStatusVideoSize.sanitise(1921, 1081)
        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
    }

    @Test
    fun `a 4K source is kept`() {
        val (width, height) = HdStatusVideoSize.sanitise(3840, 2160)
        assertEquals(3840, width)
        assertEquals(2160, height)
    }

    @Test
    fun `a source beyond 4K is scaled down to the ceiling keeping the aspect ratio`() {
        val (width, height) = HdStatusVideoSize.sanitise(7680, 4320)
        assertEquals(HdStatusVideoSize.MAX_EDGE, width)
        assertEquals(2160, height)
    }

    @Test
    fun `portrait video is scaled on its long edge too`() {
        val (width, height) = HdStatusVideoSize.sanitise(4320, 7680)
        assertEquals(2160, width)
        assertEquals(HdStatusVideoSize.MAX_EDGE, height)
    }

    @Test
    fun `the aspect ratio survives scaling`() {
        val (width, height) = HdStatusVideoSize.sanitise(10000, 5625)
        val sourceRatio = 10000.0 / 5625.0
        val scaledRatio = width.toDouble() / height.toDouble()
        assertTrue(
            "aspect ratio drifted: $scaledRatio vs $sourceRatio",
            kotlin.math.abs(scaledRatio - sourceRatio) < 0.01,
        )
    }

    @Test
    fun `a degenerate size falls back instead of being emitted`() {
        assertEquals(1280 to 720, HdStatusVideoSize.sanitise(0, 100))
        assertEquals(1280 to 720, HdStatusVideoSize.sanitise(100, -1))
    }

    @Test
    fun `a tiny size is lifted to the encoder minimum`() {
        val (width, height) = HdStatusVideoSize.sanitise(1, 1)
        assertTrue(width >= HdStatusVideoSize.MIN_EDGE)
        assertTrue(height >= HdStatusVideoSize.MIN_EDGE)
    }

    @Test
    fun `the clamp ceiling matches the prop ceiling`() {
        assertEquals(HdStatusLimits.MAX_VIDEO_EDGE, HdStatusVideoSize.MAX_EDGE)
    }
}

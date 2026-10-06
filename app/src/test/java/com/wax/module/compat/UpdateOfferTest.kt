package com.wax.module.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateOfferTest {
    @Test
    fun tagWithVPrefixIsNormalised() {
        assertEquals("1.6.3", UpdateOffer.normaliseTag("v1.6.3"))
    }

    @Test
    fun tagWithoutVPrefixIsUnchanged() {
        assertEquals("1.6.3", UpdateOffer.normaliseTag("1.6.3"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmedBeforePrefixRemoval() {
        assertEquals("1.6.3", UpdateOffer.normaliseTag("  v1.6.3  "))
    }

    @Test
    fun uppercaseVPrefixIsNormalised() {
        assertEquals("1.6.3", UpdateOffer.normaliseTag("V1.6.3"))
    }

    @Test
    fun nullTagNormalisesToEmpty() {
        assertEquals("", UpdateOffer.normaliseTag(null))
    }

    @Test
    fun debugBuildSuffixIsStripped() {
        assertEquals("1.6.2", UpdateOffer.normaliseModuleVersion("1.6.2-dev+544991A8"))
    }

    @Test
    fun releaseBuildVersionIsUnchanged() {
        assertEquals("1.6.2", UpdateOffer.normaliseModuleVersion("1.6.2"))
    }

    @Test
    fun nullModuleVersionNormalisesToEmpty() {
        assertEquals("", UpdateOffer.normaliseModuleVersion(null))
    }

    @Test
    fun newerReleaseIsOffered() {
        assertTrue(UpdateOffer.shouldOffer("1.6.3", "1.6.2", ""))
    }

    @Test
    fun sameVersionIsNotOffered() {
        assertFalse(UpdateOffer.shouldOffer("1.6.2", "1.6.2", ""))
    }

    @Test
    fun olderReleaseIsNotOffered() {
        assertFalse(UpdateOffer.shouldOffer("1.6.1", "1.6.2", ""))
    }

    @Test
    fun ignoredVersionIsNotOffered() {
        assertFalse(UpdateOffer.shouldOffer("1.6.3", "1.6.2", "v1.6.3"))
    }

    @Test
    fun blankReleaseIsNeverOffered() {
        assertFalse(UpdateOffer.shouldOffer("", "1.6.2", ""))
        assertFalse(UpdateOffer.shouldOffer("   ", "1.6.2", ""))
        assertFalse(UpdateOffer.shouldOffer(null, "1.6.2", ""))
    }

    @Test
    fun ignoringADifferentVersionStillOffersTheNewOne() {
        assertTrue(UpdateOffer.shouldOffer("1.6.3", "1.6.2", "1.6.1"))
    }

    @Test
    fun numericComparisonHandlesTwoDigitMinorVersions() {
        assertTrue(UpdateOffer.isUpdateAvailable("1.10.0", "1.9.9"))
        assertFalse(UpdateOffer.isUpdateAvailable("1.9.9", "1.10.0"))
    }

    @Test
    fun missingTrailingCorePartsCompareAsZero() {
        assertEquals(0, UpdateOffer.compareVersions("1.6", "1.6.0"))
    }

    @Test
    fun stableReleaseSortsAfterPrerelease() {
        assertTrue((UpdateOffer.compareVersions("1.6.0", "1.6.0-rc.1") ?: 0) > 0)
        assertTrue((UpdateOffer.compareVersions("1.6.0-rc.2", "1.6.0-rc.1") ?: 0) > 0)
    }

    @Test
    fun buildMetadataDoesNotChangePrecedence() {
        assertEquals(0, UpdateOffer.compareVersions("1.6.0+release", "1.6.0+local"))
    }

    @Test
    fun debugBuildOfSameBaseVersionIsCurrent() {
        assertFalse(UpdateOffer.isUpdateAvailable("v1.6.2", "1.6.2-dev+544991A8"))
    }

    @Test
    fun malformedVersionsFailClosed() {
        assertNull(UpdateOffer.compareVersions("release-1.6.3", "1.6.2"))
        assertFalse(UpdateOffer.isUpdateAvailable("release-1.6.3", "1.6.2"))
        assertFalse(UpdateOffer.shouldOffer("release-1.6.3", "1.6.2", ""))
    }

    @Test
    fun currentWaxReleaseIsDetectedAsUpToDate() {
        assertEquals(0, UpdateOffer.compareVersions("v1.1.0", "1.1.0"))
        assertFalse(UpdateOffer.isUpdateAvailable("v1.1.0", "1.1.0"))
    }
}

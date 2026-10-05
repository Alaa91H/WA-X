package com.wax.module.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun trailingWhitespaceIsTrimmed() {
        assertEquals("1.6.3", UpdateOffer.normaliseTag("v1.6.3  "))
    }

    @Test
    fun leadingWhitespaceDefeatsTheVPrefixStrip() {
        // Faithful to the original inline behaviour: removePrefix only fires when "v" is
        // the very first character, so a padded tag keeps its prefix after trimming.
        assertEquals("v1.6.3", UpdateOffer.normaliseTag("  v1.6.3"))
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
    fun ignoredVersionIsNotOffered() {
        assertFalse(UpdateOffer.shouldOffer("1.6.3", "1.6.2", "1.6.3"))
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
    fun comparisonIgnoresSurroundingWhitespace() {
        assertFalse(UpdateOffer.shouldOffer(" 1.6.2 ", " 1.6.2 ", ""))
    }

    @Test
    fun comparisonIsStringInequalityNotSemanticOrdering() {
        // Documents existing behaviour: 1.6.10 sorts below 1.6.9 alphabetically but is
        // still offered because the rule is "differs from installed", not "greater than".
        assertTrue(UpdateOffer.shouldOffer("1.6.10", "1.6.9", ""))
    }

    @Test
    fun bothSidesBlankIsNotAnUpdate() {
        assertFalse(UpdateOffer.shouldOffer("", "", ""))
    }
}

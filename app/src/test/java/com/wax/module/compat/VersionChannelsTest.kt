package com.wax.module.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The channel-aware build assessment.
 *
 * The cases are grouped the way the rule can hurt: refusing a build the module would work on
 * (every beta and every new patch), loading one it cannot work on (an older series), and the
 * numeric-versus-lexical family comparison that makes `2.10` look older than `2.9`.
 */
class VersionChannelsTest {
    private val declared =
        listOf(
            "2.26.32.xx",
            "2.26.34.xx",
            "2.26.35.xx",
            "2.26.36.xx",
            "2.26.37.xx",
            "2.26.38.xx",
            "2.26.39.xx",
            "2.26.40.xx",
        )

    private fun assess(
        version: String?,
        versions: List<String> = declared,
        channel: VersionChannel = VersionChannel.UNKNOWN,
        policy: VersionTolerancePolicy = VersionTolerancePolicy(),
    ): VersionAssessment = TargetVersions.assess(version, versions, channel, policy)

    // --- parsing ------------------------------------------------------------------------

    @Test
    fun buildNumbersParseTheFormsWhatsAppUses() {
        assertEquals(BuildNumber(2, 26, 40, 75), BuildNumber.parse("2.26.40.75"))
        assertEquals(BuildNumber(2, 26, 40, 0), BuildNumber.parse("2.26.40"))
        assertEquals(BuildNumber(2, 26, 0, 0), BuildNumber.parse("2.26"))
        assertEquals(BuildNumber(2, 26, 40, 75), BuildNumber.parse("2.26.40.75-beta"))
        assertEquals(BuildNumber(2, 26, 40, 75), BuildNumber.parse("  2.26.40.75  "))
        assertNull(BuildNumber.parse("abc"))
        assertNull(BuildNumber.parse("2"))
        assertNull(BuildNumber.parse(""))
        assertNull(BuildNumber.parse(null))
    }

    @Test
    fun buildNumbersOrderNumericallyNotLexically() {
        val older = BuildNumber.parse("2.9.0.0")!!
        val newer = BuildNumber.parse("2.10.0.0")!!
        assertTrue(newer > older)
        assertTrue(older < BuildNumber.parse("2.10.1.0")!!)
        assertEquals("2.26.40", BuildNumber.parse("2.26.40.75")!!.release)
    }

    // --- the declared case --------------------------------------------------------------

    @Test
    fun aDeclaredBuildIsAcceptedAndReportedAsDeclared() {
        val assessment = assess("2.26.40.75")
        assertEquals(VersionVerdict.DECLARED, assessment.verdict)
        assertTrue(assessment.accepted)
        assertTrue(assessment.isDeclared)
        assertFalse(assessment.isExperimental)
        assertTrue(assessment.explanation.contains("declared compatibility range"))
    }

    // --- tolerated builds ---------------------------------------------------------------

    @Test
    fun anUndeclaredBuildInsideTheRangeIsLoadedAsUnverified() {
        val assessment = assess("2.26.33.9")
        assertEquals(VersionVerdict.UNVERIFIED_INSIDE_RANGE, assessment.verdict)
        assertTrue(assessment.accepted)
        assertTrue(assessment.isExperimental)
        assertFalse(assessment.isDeclared)
        assertTrue(assessment.explanation.contains("unverified"))
    }

    @Test
    fun aNewerPatchOfADeclaredFamilyIsTolerated() {
        // The common beta case: the same series, a patch the declaration does not list.
        val assessment = assess("2.26.41.5", channel = VersionChannel.BETA)
        assertEquals(VersionVerdict.UNVERIFIED_INSIDE_RANGE, assessment.verdict)
        assertTrue(assessment.accepted)
        assertTrue(assessment.explanation.contains("beta build"))
    }

    @Test
    fun aNewerFamilyIsToleratedByDefault() {
        val assessment = assess("2.27.1.5")
        assertEquals(VersionVerdict.UNVERIFIED_NEWER_FAMILY, assessment.verdict)
        assertTrue(assessment.accepted)
        assertTrue(assessment.explanation.contains("unverified"))
    }

    @Test
    fun theToleranceCanBeTurnedOffForADeploymentThatPrefersRefusal() {
        val strict = VersionTolerancePolicy(tolerateUnverifiedInsideRange = false, tolerateUnverifiedNewerFamily = false)
        assertFalse(assess("2.26.33.9", policy = strict).accepted)
        assertFalse(assess("2.27.1.5", policy = strict).accepted)
        // A declared build is still accepted under the strict policy: it is what the declaration means.
        assertTrue(assess("2.26.40.75", policy = strict).accepted)
    }

    // --- refusals -----------------------------------------------------------------------

    @Test
    fun anOlderFamilyIsRefused() {
        val assessment = assess("2.25.10.1")
        assertEquals(VersionVerdict.UNSUPPORTED_OLDER_FAMILY, assessment.verdict)
        assertFalse(assessment.accepted)
    }

    @Test
    fun anUnreadableBuildNumberIsRefused() {
        listOf(null, "", "   ", "unknown", "2").forEach { raw ->
            val assessment = assess(raw)
            assertEquals("$raw must not be accepted", VersionVerdict.UNREADABLE, assessment.verdict)
            assertFalse(assessment.accepted)
        }
    }

    @Test
    fun anUnreadableDeclaredListRefusesEverything() {
        val assessment = assess("2.26.40.75", versions = listOf("", "   "))
        assertEquals(VersionVerdict.UNREADABLE, assessment.verdict)
        assertFalse(assessment.accepted)
    }

    // --- the channel --------------------------------------------------------------------

    @Test
    fun theChannelChangesTheWordingAndNotTheDecision() {
        val stable = assess("2.26.41.5", channel = VersionChannel.STABLE)
        val beta = assess("2.26.41.5", channel = VersionChannel.BETA)
        val alpha = assess("2.26.41.5", channel = VersionChannel.ALPHA)
        assertEquals(stable.verdict, beta.verdict)
        assertEquals(stable.accepted, beta.accepted)
        assertEquals(stable.accepted, alpha.accepted)
        assertNotEquals(stable.explanation, beta.explanation)
        assertTrue(alpha.explanation.contains("alpha build"))
        assertFalse(stable.explanation.contains("build)"))
    }

    @Test
    fun aToleratedBuildIsNeverDescribedAsVerified() {
        listOf(assess("2.26.33.9"), assess("2.26.41.5"), assess("2.27.1.5")).forEach { assessment ->
            assertTrue(assessment.isExperimental)
            assertTrue(assessment.explanation.contains("unverified"))
            assertFalse(assessment.explanation.contains("is inside the declared"))
        }
    }

    // --- what the status cards show -----------------------------------------------------

    @Test
    fun theStatusToneSeparatesDeclaredFromMerelyTolerated() {
        assertEquals(VersionStatusTone.SUPPORTED, assess("2.26.40.75").tone)
        // Loaded but undeclared: neither "supported" nor "unsupported" is the truth.
        assertEquals(VersionStatusTone.UNVERIFIED, assess("2.26.41.5").tone)
        assertEquals(VersionStatusTone.UNVERIFIED, assess("2.27.1.5").tone)
        assertEquals(VersionStatusTone.UNSUPPORTED, assess("2.25.1.1").tone)
        assertEquals(VersionStatusTone.UNSUPPORTED, assess("nonsense").tone)
    }

    @Test
    fun theToneNeverCallsAToleratedBuildDeclared() {
        listOf("2.26.33.9", "2.26.41.5", "2.27.1.5").forEach { version ->
            val assessment = assess(version)
            assertEquals("$version is loaded, so it cannot be unsupported", VersionStatusTone.UNVERIFIED, assessment.tone)
            assertTrue(assessment.isExperimental)
        }
    }

    // --- the family comparison ----------------------------------------------------------

    @Test
    fun familiesOrderNumericallyAndNotLexically() {
        // The family, not the build, is what decides "new series"; it must order numerically.
        assertTrue(VersionFamily(2, 10) > VersionFamily(2, 9))
        assertEquals(VersionFamily(2, 26), BuildNumber.parse("2.26.41.5")!!.family)
        assertEquals(VersionFamily(2, 26), BuildNumber(2, 26, 40, 0).family)
        assertNotEquals(VersionFamily(2, 26), VersionFamily(2, 27))
    }

    @Test
    fun familyComparisonIsNumericAndNotLexical() {
        // A declaration in the 2.9 series must not make the 2.10 series look older.
        val versions = listOf("2.9.1.xx")
        assertEquals(VersionVerdict.UNVERIFIED_NEWER_FAMILY, assess("2.10.1.5", versions = versions).verdict)
        assertTrue(assess("2.10.1.5", versions = versions).accepted)
        assertEquals(VersionVerdict.UNSUPPORTED_OLDER_FAMILY, assess("2.8.1.1", versions = versions).verdict)
    }

    // --- the strict match is still the definition of "declared" --------------------------

    @Test
    fun theStrictMatchStaysStrict() {
        assertFalse(TargetVersions.isSupported("2.26.33.9", declared))
        assertTrue(TargetVersions.accepts("2.26.33.9", declared))
        assertTrue(TargetVersions.isSupported("2.26.40.75", declared))
        assertTrue(TargetVersions.accepts("2.26.40.75", declared))
        assertFalse(TargetVersions.accepts("2.25.1.1", declared))
    }

    @Test
    fun theDisplayLineCarriesTheVerdictAndTheChannel() {
        val line = assess("2.26.41.5", channel = VersionChannel.BETA).toDisplayLine()
        assertTrue(line.startsWith("version unverified_inside_range (beta):"))
    }

    @Test
    fun theAssessmentKeepsTheParsedBuild() {
        val assessment = assess("2.26.40.75")
        assertEquals(BuildNumber(2, 26, 40, 75), assessment.installed)
        assertNull(assess("nonsense").installed)
    }
}

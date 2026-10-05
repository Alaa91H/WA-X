package com.wax.module.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetVersionsTest {
    private val whatsapp = listOf("2.26.32.xx", "2.26.39.xx", "2.26.40.xx")

    @Test
    fun wildcardEntryBecomesItsPrefix() {
        assertEquals("2.26.40", TargetVersions.prefixOf("2.26.40.xx"))
    }

    @Test
    fun entryWithoutWildcardIsUnchanged() {
        assertEquals("2.26.40", TargetVersions.prefixOf("2.26.40"))
    }

    @Test
    fun exactPrefixMatchesRealBuildNumber() {
        assertTrue(TargetVersions.isSupported("2.26.40.21", whatsapp))
    }

    @Test
    fun unlistedVersionIsRejected() {
        assertFalse(TargetVersions.isSupported("2.26.41.1", whatsapp))
    }

    @Test
    fun olderListedVersionStillMatches() {
        assertTrue(TargetVersions.isSupported("2.26.32.5", whatsapp))
    }

    @Test
    fun nullVersionNeverMatches() {
        assertFalse(TargetVersions.isSupported(null, whatsapp))
    }

    @Test
    fun emptyVersionNeverMatches() {
        assertFalse(TargetVersions.isSupported("", whatsapp))
    }

    @Test
    fun emptyVersionListRejectsEverything() {
        assertFalse(TargetVersions.isSupported("2.26.40.21", emptyList()))
    }

    @Test
    fun blankDeclarationDoesNotMatchEverything() {
        // A whitespace-only entry would otherwise normalise to "" and match any version.
        assertFalse(TargetVersions.isSupported("2.26.40.21", listOf("   ")))
    }

    @Test
    fun shorterVersionThanThePrefixIsRejected() {
        assertFalse(TargetVersions.isSupported("2.26.4", listOf("2.26.40.xx")))
    }

    @Test
    fun wildcardIsAPrefixMatchWithNoComponentBoundary() {
        // Documents the real rule: startsWith, not a component-wise compare, so a build
        // numbered 2.26.401 is accepted by a 2.26.40.xx declaration.
        assertTrue(TargetVersions.isSupported("2.26.401", listOf("2.26.40.xx")))
    }

    @Test
    fun normaliseDropsBlanksAndDuplicatesKeepingOrder() {
        val result =
            TargetVersions.normalise(
                listOf(" 2.26.40.xx ", "", "2.26.39.xx", "2.26.40.xx", "   "),
            )
        assertEquals(listOf("2.26.40.xx", "2.26.39.xx"), result)
    }

    @Test
    fun resolvePrefersTheResourceList() {
        val resolved = TargetVersions.resolve(listOf("2.27.0.xx"), whatsapp)
        assertEquals(listOf("2.27.0.xx"), resolved)
    }

    @Test
    fun resolveFallsBackWhenResourcesAreUnusable() {
        assertEquals(whatsapp, TargetVersions.resolve(emptyList(), whatsapp))
    }

    @Test
    fun resolveFallsBackWhenResourcesAreOnlyBlank() {
        assertEquals(whatsapp, TargetVersions.resolve(listOf("", "  "), whatsapp))
    }
}

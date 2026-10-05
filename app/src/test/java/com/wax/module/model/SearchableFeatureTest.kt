package com.wax.module.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchableFeatureTest {
    private val antiRevoke =
        SearchableFeature(
            key = "antirevoke",
            title = "Anti Revoke",
            summary = "Prevent messages from being revoked",
            category = SearchableFeature.Category.GENERAL_CONVERSATION,
            fragmentType = SearchableFeature.FragmentType.GENERAL,
            searchTags = listOf("revoke", "delete"),
        )

    @Test
    fun aTitleMatchIsFound() {
        assertTrue(antiRevoke.matches("revoke"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(antiRevoke.matches("REVOKE"))
        assertTrue(antiRevoke.matches("rEvOkE"))
    }

    @Test
    fun aSummaryMatchIsFound() {
        assertTrue(antiRevoke.matches("prevent"))
    }

    @Test
    fun aTagMatchIsFound() {
        assertTrue(antiRevoke.matches("delete"))
    }

    @Test
    fun theCategoryDisplayNameIsSearchable() {
        assertTrue(antiRevoke.matches("general"))
    }

    @Test
    fun anAbsentKeywordDoesNotMatch() {
        assertFalse(antiRevoke.matches("telegram"))
    }

    @Test
    fun aBlankQueryMatchesNothing() {
        assertFalse(antiRevoke.matches(""))
        assertFalse(antiRevoke.matches("   "))
    }

    @Test
    fun aNullQueryMatchesNothing() {
        assertFalse(antiRevoke.matches(null))
    }

    @Test
    fun surroundingWhitespaceInTheQueryIsIgnored() {
        assertTrue(antiRevoke.matches("  revoke  "))
    }

    @Test
    fun aSubstringOfTheTitleMatches() {
        assertTrue(antiRevoke.matches("anti"))
    }

    @Test
    fun thePreferenceKeyIsNotSearchable() {
        // Documented gap: users see titles, so the key is not part of the surface.
        assertFalse(antiRevoke.matches("antirevoke"))
    }

    @Test
    fun aFeatureWithoutASummaryStillMatchesItsTitle() {
        val noSummary =
            SearchableFeature(
                key = "ghostmode",
                title = "Ghost Mode",
                summary = null,
                category = SearchableFeature.Category.PRIVACY,
                fragmentType = SearchableFeature.FragmentType.PRIVACY,
            )
        assertTrue(noSummary.matches("ghost"))
        assertFalse(noSummary.matches("nothing"))
    }

    @Test
    fun absentTagsBecomeAnEmptyList() {
        val noTags =
            SearchableFeature(
                key = "k",
                title = "T",
                summary = null,
                category = SearchableFeature.Category.MEDIA,
                fragmentType = SearchableFeature.FragmentType.MEDIA,
            )
        assertTrue(noTags.searchTags.isEmpty())
        assertFalse(noTags.matches("anything"))
    }

    @Test
    fun toStringMentionsTheKeyAndCategory() {
        val text = antiRevoke.toString()
        assertTrue(text.contains("antirevoke"))
        assertTrue(text.contains("GENERAL_CONVERSATION"))
    }

    @Test
    fun everyFragmentTypeHasADistinctPosition() {
        val positions = SearchableFeature.FragmentType.entries.map { it.position }
        assertEquals(positions.size, positions.toSet().size)
    }

    @Test
    fun theGeneralSubCategoriesShareOneDisplayName() {
        // Intentional: the four general screens present as a single "General" group.
        val general =
            listOf(
                SearchableFeature.Category.GENERAL,
                SearchableFeature.Category.GENERAL_HOME,
                SearchableFeature.Category.GENERAL_HOMESCREEN,
                SearchableFeature.Category.GENERAL_CONVERSATION,
            )
        assertEquals(setOf("General"), general.map { it.displayName }.toSet())
    }

    @Test
    fun categoriesAreDistinguishedByNameEvenWhenDisplayNamesCollide() {
        assertNotEquals(
            SearchableFeature.Category.GENERAL,
            SearchableFeature.Category.GENERAL_CONVERSATION,
        )
    }
}

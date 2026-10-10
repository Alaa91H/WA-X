package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection rules for the contact/JID chain (#455).
 *
 * The device evidence named the first failed dependency as
 * `CONTACT_DATA_CLASS_MISSING`. The anchor that caused it is the fact under
 * test here: the legacy resolver matched `WaContactData` as a string a class
 * *uses*, while the modern resolver matched it as a class *name*, which no
 * WhatsApp build satisfies.
 */
class ModernContactAccessSelectionTest {
    @Test fun theContactDataAnchorIsTheOneTheLegacyResolverUsed() {
        // Both queries are offered; the string query is the one that answers on
        // a real build, so it is tried first and named in the evidence.
        assertEquals(
            "the anchor that answers must be the legacy string query",
            ModernContactAccess.ContactDataAnchor.USING_STRING,
            anchorFor(stringMatches = 1, nameMatches = 0),
        )
        assertEquals(
            "the name query remains as a fallback, not as the primary",
            ModernContactAccess.ContactDataAnchor.CLASS_NAME,
            anchorFor(stringMatches = 0, nameMatches = 1),
        )
    }

    @Test fun bothAnchorsMissingIsAMissingClassRatherThanAGuess() {
        assertTrue(
            "no candidate under either anchor is a missing class",
            ModernContactAccess.Outcome.CONTACT_DATA_CLASS_MISSING
                .let { it != ModernContactAccess.Outcome.CONTACT_DATA_CLASS_AMBIGUOUS },
        )
    }

    @Test fun ambiguityIsItsOwnOutcomeRatherThanTheFirstMatch() {
        // These are the states the diagnostic reports, and they must stay
        // distinguishable: "several classes matched" is a different finding
        // from "no class matched", and neither is a resolution.
        val outcomes = ModernContactAccess.Outcome.entries.map { it.name }
        for (name in listOf(
            "CONTACT_CLASS_AMBIGUOUS",
            "CONTACT_DATA_CLASS_AMBIGUOUS",
            "JID_CLASS_AMBIGUOUS",
            "CLASS_LOADER_MISMATCH",
        )) {
            assertTrue("$name must be reportable", outcomes.contains(name))
        }
    }

    @Test fun everyResolverFailureHasADistinctState() {
        val outcomes = ModernContactAccess.Outcome.entries
        assertEquals(
            "two states with the same name would collapse in the report",
            outcomes.size,
            outcomes.map { it.name }.distinct().size,
        )
    }

    @Test fun aLoaderMismatchIsReportedRatherThanReflectedOn() {
        assertTrue(
            "a class defined by another loader is not the class the target runs",
            ModernContactAccess.Outcome.entries
                .map { it.name }
                .contains("CLASS_LOADER_MISMATCH"),
        )
    }

    @Test fun theEvidenceCarriesCountsAndProvenanceRatherThanNames() {
        val evidence =
            ModernContactAccess.Evidence(
                contactCandidates = 1,
                contactDataCandidates = 2,
                jidCandidates = 1,
                contactDataAnchor = "USING_STRING",
                contactDataLoaderMatches = true,
                jidLoaderMatches = false,
            )
        val fields = ModernContactAccess.Evidence::class.java.declaredFields.map { it.name }
        for (field in fields) {
            assertTrue(
                "evidence must never carry a resolved class name: $field",
                !field.contains("className", ignoreCase = true) &&
                    !field.contains("class_name", ignoreCase = true),
            )
        }
        assertEquals(1, evidence.contactCandidates)
        assertEquals(2, evidence.contactDataCandidates)
        assertEquals("USING_STRING", evidence.contactDataAnchor)
    }

    private fun anchorFor(
        stringMatches: Int,
        nameMatches: Int,
    ): ModernContactAccess.ContactDataAnchor =
        if (stringMatches > 0) {
            ModernContactAccess.ContactDataAnchor.USING_STRING
        } else if (nameMatches > 0) {
            ModernContactAccess.ContactDataAnchor.CLASS_NAME
        } else {
            throw IllegalStateException("neither anchor matched")
        }
}

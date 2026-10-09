package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Control Center must never present an unverified feature as working. */
class ControlCenterModelTest {
    private fun entry(
        id: String,
        category: ControlCategory,
        effective: ControlEffective,
        key: String? = "pref.$id",
        pending: Boolean = false,
    ) = ControlEntry(
        id = id,
        title = id,
        description = "desc $id",
        category = if (pending) ControlCategory.PENDING else category,
        preferenceKey = key,
        requested = ControlRequested.DISABLED,
        effective = effective,
        writable = ControlPolicy.isWritable(key, effective),
        restartRequired = false,
    )

    @Test fun pendingMigrationIsNeverWritable() {
        assertFalse(
            ControlPolicy.isWritable("modern.feature.custom_time.enabled",
                ControlEffective.PENDING_MIGRATION),
        )
    }

    @Test fun failedRuntimeStatesAreNeverWritable() {
        for (state in listOf(
            ControlEffective.RESOLVER_FAILED,
            ControlEffective.UNSAFE_SIGNATURE,
            ControlEffective.ERROR,
        )) {
            assertFalse("state $state must not be writable", ControlPolicy.isWritable("k", state))
        }
    }

    @Test fun workingStatesAreWritable() {
        for (state in listOf(
            ControlEffective.NOT_OBSERVED,
            ControlEffective.INSTALLED,
            ControlEffective.DISABLED,
            ControlEffective.WORKING,
            ControlEffective.RESTART_REQUIRED,
        )) {
            assertTrue("state $state must be writable", ControlPolicy.isWritable("k", state))
        }
    }

    @Test fun aRowWithoutPreferenceKeyIsNotWritable() {
        assertFalse(ControlPolicy.isWritable(null, ControlEffective.INSTALLED))
        // An empty key is how always-on infrastructure is declared; treating it
        // as writable would put a live switch on something that cannot be set.
        assertFalse(ControlPolicy.isWritable("", ControlEffective.INSTALLED))
        assertFalse(ControlPolicy.isWritable("   ", ControlEffective.WORKING))
    }

    @Test fun reportedResolverFailureIsNotHiddenAsWorking() {
        assertEquals(
            ControlEffective.RESOLVER_FAILED,
            ControlPolicy.effectiveFrom("RESOLVER_MISSING", false, ControlRequested.ENABLED),
        )
        assertEquals(
            ControlEffective.RESOLVER_FAILED,
            ControlPolicy.effectiveFrom("RESOLVER_AMBIGUOUS", false, ControlRequested.ENABLED),
        )
        assertEquals(
            ControlEffective.UNSAFE_SIGNATURE,
            ControlPolicy.effectiveFrom("UNSAFE_SIGNATURE", false, ControlRequested.ENABLED),
        )
        assertEquals(
            ControlEffective.ERROR,
            ControlPolicy.effectiveFrom("ERROR_NoSuchMethod", false, ControlRequested.ENABLED),
        )
    }

    @Test fun enabledButReportedDisabledMeansRestartRequired() {
        assertEquals(
            ControlEffective.RESTART_REQUIRED,
            ControlPolicy.effectiveFrom("DISABLED", false, ControlRequested.ENABLED),
        )
    }

    @Test fun installedEvidenceMapsToInstalled() {
        assertEquals(
            ControlEffective.INSTALLED,
            ControlPolicy.effectiveFrom("INSTALLED", false, ControlRequested.ENABLED),
        )
        assertEquals(
            ControlEffective.INSTALLED,
            ControlPolicy.effectiveFrom("ALREADY_INSTALLED", false, ControlRequested.ENABLED),
        )
    }

    @Test fun noEvidenceIsNeverClaimedAsWorking() {
        assertEquals(
            ControlEffective.NOT_OBSERVED,
            ControlPolicy.effectiveFrom(null, false, ControlRequested.ENABLED),
        )
    }

    @Test fun searchFiltersByTitleAndDescription() {
        val rows = listOf(
            entry("CustomTime", ControlCategory.APPEARANCE, ControlEffective.INSTALLED),
            entry("ShareLimit", ControlCategory.CHATS, ControlEffective.INSTALLED),
        )
        assertEquals(2, ControlPolicy.group(rows).size)
        assertEquals(
            listOf(ControlCategory.APPEARANCE),
            ControlPolicy.group(rows, "custom").map { it.first },
        )
        assertEquals(
            listOf(ControlCategory.CHATS),
            ControlPolicy.group(rows, "limit").map { it.first },
        )
        assertTrue(ControlPolicy.group(rows, "zzz").isEmpty())
    }

    @Test fun pendingCategoryIsAlwaysRenderedLast() {
        val rows = listOf(
            entry("MinorFixes", ControlCategory.ADVANCED, ControlEffective.PENDING_MIGRATION, pending = true),
            entry("FreezeLastSeen", ControlCategory.PRIVACY, ControlEffective.INSTALLED),
        )
        val grouped = ControlPolicy.group(rows)
        assertEquals(ControlCategory.PENDING, grouped.last().first)
    }

    @Test fun statusTextNeverClaimsWorkingForFailures() {
        assertEquals("Resolver could not confirm", ControlStatusText.status(ControlEffective.RESOLVER_FAILED))
        assertEquals("Pending migration", ControlStatusText.status(ControlEffective.PENDING_MIGRATION))
        assertEquals("Not observed yet", ControlStatusText.status(ControlEffective.NOT_OBSERVED))
    }
}
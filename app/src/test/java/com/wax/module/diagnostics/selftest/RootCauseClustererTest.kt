package com.wax.module.diagnostics.selftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The observed device failure chain must collapse into ONE root cause. */
class RootCauseClustererTest {

    private fun result(
        id: String,
        status: DiagnosticStatus,
        dependsOn: List<String> = emptyList(),
        failureClass: FailureClass = FailureClass.DEPENDENCY_MISSING,
        remediation: String = "fix $id",
    ) = AtomicCheckResult(
        id = id, title = "title $id", scope = "resolver", status = status,
        evidenceLevel = EvidenceLevel.L2_RESOLVER, expected = "e",
        observedEvidence = if (status == DiagnosticStatus.FAIL) "broken" else "",
        verification = VerificationState.NOT_OBSERVED, timestampMillis = 0,
        whatsappBuild = "2.26.39.74", severity = "high", confidence = 1.0,
        failureClass = failureClass, remediation = remediation, dependsOn = dependsOn,
    )

    @Test fun theObservedChainCollapsesToOneCluster() {
        val results = listOf(
            result(AtomicCheckInventory.CONTACT_DATA_CLASS, DiagnosticStatus.FAIL),
            result(
                AtomicCheckInventory.JID_CLASS,
                DiagnosticStatus.FAIL,
                listOf(AtomicCheckInventory.CONTACT_DATA_CLASS),
            ),
            result(
                AtomicCheckInventory.JID_RAW_STRING,
                DiagnosticStatus.BLOCKED,
                listOf(AtomicCheckInventory.JID_CLASS),
            ),
            result(
                "trigger.typing_privacy",
                DiagnosticStatus.BLOCKED,
                listOf(AtomicCheckInventory.JID_RAW_STRING),
            ),
        )
        val clusters = RootCauseClusterer.cluster(results)
        assertEquals(
            "one broken resolver chain must be one cluster, not four symptoms",
            1,
            clusters.size,
        )
        val cluster = clusters.single()
        assertEquals(AtomicCheckInventory.CONTACT_DATA_CLASS, cluster.rootCauseId)
        assertTrue(cluster.symptomIds.contains(AtomicCheckInventory.JID_CLASS))
        assertTrue(cluster.symptomIds.contains("trigger.typing_privacy"))
    }

    @Test fun unrelatedFailuresStaySeparate() {
        val results = listOf(
            result("resolver.a", DiagnosticStatus.FAIL),
            result("resolver.b", DiagnosticStatus.FAIL),
        )
        assertEquals(2, RootCauseClusterer.cluster(results).size)
    }

    @Test fun theFirstFailedDependencyFollowsTheCausalOrderNotAlphabetical() {
        val results = listOf(
            result(AtomicCheckInventory.JID_RAW_STRING, DiagnosticStatus.FAIL),
            result(AtomicCheckInventory.CONTACT_DATA_CLASS, DiagnosticStatus.FAIL),
        )
        val order = AtomicCheckInventory.orderWith(emptyList())
        assertEquals(
            AtomicCheckInventory.CONTACT_DATA_CLASS,
            RootCauseClusterer.firstFailedDependency(results, order)?.id,
        )
    }

    @Test fun aChainWithoutFailuresHasNoRootCause() {
        val results = listOf(
            result("a", DiagnosticStatus.PASS, failureClass = FailureClass.NONE),
            result("b", DiagnosticStatus.NOT_TESTED, failureClass = FailureClass.NONE),
        )
        assertTrue(RootCauseClusterer.cluster(results).isEmpty())
        assertNull(
            RootCauseClusterer.firstFailedDependency(results, listOf("a", "b")),
        )
    }

    @Test fun summaryCountsAreNotAHealthScore() {
        val results = listOf(
            result("a", DiagnosticStatus.PASS, failureClass = FailureClass.NONE),
            result("b", DiagnosticStatus.FAIL),
            result("c", DiagnosticStatus.NOT_TESTED, failureClass = FailureClass.NONE),
            result("d", DiagnosticStatus.UNSUPPORTED, failureClass = FailureClass.NONE),
            result(
                "e", DiagnosticStatus.NEEDS_EXTERNAL_VERIFICATION,
                failureClass = FailureClass.NONE,
            ),
        )
        val summary = DiagnosticSummary(
            total = results.size,
            passed = results.count { it.status == DiagnosticStatus.PASS },
            failed = results.count { it.status == DiagnosticStatus.FAIL },
            blocked = results.count { it.status == DiagnosticStatus.BLOCKED },
            notTested = results.count { it.status == DiagnosticStatus.NOT_TESTED },
            unsupported = results.count { it.status == DiagnosticStatus.UNSUPPORTED },
            needsExternalVerification = results.count {
                it.status == DiagnosticStatus.NEEDS_EXTERNAL_VERIFICATION
            },
            clusters = RootCauseClusterer.cluster(results),
        )
        assertEquals(2, summary.inconclusive)
        assertFalse(
            "the JSON must expose counts, never a single fabricated score",
            summary.toJson().contains("\"score\""),
        )
        assertTrue(summary.toJson().contains("\"inconclusive\":2"))
    }
}
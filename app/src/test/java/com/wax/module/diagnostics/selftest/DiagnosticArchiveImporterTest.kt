package com.wax.module.diagnostics.selftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Import of a previously exported archive (#170).
 *
 * Every test here drives the untrusted-input path: the archive is attacker
 * controlled, so the assertions are about what the importer *refuses* just as
 * much as about what it returns.
 */
class DiagnosticArchiveImporterTest {
    private val exporter = DiagnosticZipExporter()
    private val importer = DiagnosticArchiveImporter(exporter)

    @Test fun aVerifiedArchiveIsImportedWithItsOwnFacts() {
        val bytes = archiveFor("2.26.39.74", "abc123", listOf("hook.custom_time"))
        val accepted = importer.import(bytes)
        assertTrue("a self-consistent archive must import: $accepted", accepted is DiagnosticArchiveImporter.ImportResult.Accepted)
        val result = accepted as DiagnosticArchiveImporter.ImportResult.Accepted
        assertEquals(DiagnosticSchema.SCHEMA_VERSION, result.manifest.schemaVersion)
        assertEquals("2.26.39.74", result.manifest.whatsappVersion)
        assertEquals("abc123", result.manifest.appBuildSha)
        assertTrue(
            "the declared missing sources survive the import",
            result.missingSources.contains("logs/sanitized-runtime.log"),
        )
    }

    @Test fun anArchiveWhosePayloadWasEditedIsRefused() {
        val bytes = archiveFor("2.26.39.74", "abc123", emptyList(), tamper = true)
        val result = importer.import(bytes)
        assertTrue("edited bytes must be refused", result is DiagnosticArchiveImporter.ImportResult.Rejected)
        assertEquals(
            DiagnosticArchiveImporter.ImportResult.Reason.CHECKSUMS_MISSING_OR_MISMATCHED,
            (result as DiagnosticArchiveImporter.ImportResult.Rejected).reason,
        )
    }

    @Test fun anArchiveWithoutAChecksumFileIsRefused() {
        val built = exporter.build(listOf(entry("manifest.json", "{}")))
        val result = importer.import(built.bytes)
        assertTrue(result is DiagnosticArchiveImporter.ImportResult.Rejected)
        assertEquals(
            DiagnosticArchiveImporter.ImportResult.Reason.CHECKSUMS_MISSING_OR_MISMATCHED,
            (result as DiagnosticArchiveImporter.ImportResult.Rejected).reason,
        )
    }

    @Test fun somethingThatIsNotAnArchiveIsRefusedRatherThanParsed() {
        val result = importer.import("not a zip at all".toByteArray())
        assertTrue(result is DiagnosticArchiveImporter.ImportResult.Rejected)
        assertEquals(
            DiagnosticArchiveImporter.ImportResult.Reason.NOT_A_VALID_ARCHIVE,
            (result as DiagnosticArchiveImporter.ImportResult.Rejected).reason,
        )
    }

    @Test fun anArchiveFromAnotherSchemaIsRefusedRatherThanGuessedAt() {
        val built =
            sealed(
                entry(
                    "manifest.json",
                    "{\"schema_version\":\"wax.diagnostics/99\",\"scan_id\":\"x\"}",
                ),
                entry("results.json", "[]"),
            )
        val result = importer.import(built)
        assertTrue(result is DiagnosticArchiveImporter.ImportResult.Rejected)
        assertEquals(
            DiagnosticArchiveImporter.ImportResult.Reason.UNSUPPORTED_SCHEMA,
            (result as DiagnosticArchiveImporter.ImportResult.Rejected).reason,
        )
    }

    @Test fun aManifestThatIsNotAnObjectIsRefused() {
        val built = sealed(entry("manifest.json", "[]"), entry("results.json", "[]"))
        val result = importer.import(built)
        assertTrue(result is DiagnosticArchiveImporter.ImportResult.Rejected)
        assertEquals(
            DiagnosticArchiveImporter.ImportResult.Reason.MANIFEST_UNREADABLE,
            (result as DiagnosticArchiveImporter.ImportResult.Rejected).reason,
        )
    }

    /** An archive whose digests are correct, so the later gates are reached. */
    private fun sealed(vararg entries: DiagnosticZipExporter.Entry): ByteArray =
        exporter.build(entries.toList() + DiagnosticZipExporter.Entry(CHECKSUMS_ENTRY, exporter.checksums(entries.toList()))).bytes

    @Test fun aRegressedCheckIsReportedAsARegression() {
        val previous =
            mapOf(
                "resolver.contact_data_class" to
                    DiagnosticArchiveImporter.PreviousResult(
                        id = "resolver.contact_data_class",
                        status = DiagnosticStatus.PASS,
                        evidenceLevel = EvidenceLevel.L2_RESOLVER,
                        observed = "CONTACT_DATA_CLASS_MISSING",
                    ),
                "hook.custom_time" to
                    DiagnosticArchiveImporter.PreviousResult(
                        id = "hook.custom_time",
                        status = DiagnosticStatus.NOT_TESTED,
                        evidenceLevel = EvidenceLevel.L3_HOOK,
                        observed = "",
                    ),
            )
        val deltas =
            importer.compare(
                previous,
                listOf(
                    result("resolver.contact_data_class", DiagnosticStatus.FAIL),
                    result("hook.custom_time", DiagnosticStatus.PASS),
                ),
            )
        assertEquals(2, deltas.size)
        val regressed = deltas.first { it.id == "resolver.contact_data_class" }
        assertTrue("a PASS becoming a FAIL is a regression", regressed.isRegression)
        assertFalse(regressed.isImprovement)
        val improved = deltas.first { it.id == "hook.custom_time" }
        assertTrue("a NOT_TESTED becoming a PASS is an improvement", improved.isImprovement)
        assertFalse(improved.isRegression)
        assertTrue(importer.describe(deltas).contains("1 regressed"))
    }

    @Test fun aCheckThePreviousArchiveNeverCarriedIsNotADelta() {
        val deltas =
            importer.compare(
                emptyMap(),
                listOf(result("hook.brand_new", DiagnosticStatus.PASS)),
            )
        assertTrue("absence of evidence is not a delta", deltas.isEmpty())
        assertEquals("no comparable checks in the previous archive", importer.describe(deltas))
    }

    @Test fun aDisabledFeatureIsNotComparedAsAFailure() {
        // The state vocabulary the scan records: a feature switched off is
        // NOT_TESTED, so importing a previous archive must not turn it into a
        // regression on the next scan.
        val deltas =
            importer.compare(
                mapOf(
                    "hook.share_limit" to
                        DiagnosticArchiveImporter.PreviousResult(
                            "hook.share_limit",
                            DiagnosticStatus.NOT_TESTED,
                            EvidenceLevel.L3_HOOK,
                            "DISABLED",
                        ),
                ),
                listOf(result("hook.share_limit", DiagnosticStatus.NOT_TESTED)),
            )
        assertEquals(1, deltas.size)
        assertTrue(deltas.single().isUnchanged)
        assertFalse(deltas.single().isRegression)
        assertFalse(deltas.single().isImprovement)
    }

    @Test fun newlyLearningThatADependencyIsBrokenIsARegression() {
        // Silence turning into a known failure is the case the issue cares
        // about, so it must be visible rather than read as "unchanged".
        val delta =
            importer
                .compare(
                    mapOf(
                        "hook.jid_access" to
                            DiagnosticArchiveImporter.PreviousResult(
                                "hook.jid_access",
                                DiagnosticStatus.NOT_TESTED,
                                EvidenceLevel.L3_HOOK,
                                "",
                            ),
                    ),
                    listOf(result("hook.jid_access", DiagnosticStatus.BLOCKED)),
                ).single()
        assertTrue(delta.isRegression)
    }

    @Test fun thePreviousStatusesSurviveTheRoundTrip() {
        val bytes = archiveFor("2.26.39.74", "abc123", listOf("hook.custom_time"))
        val accepted = importer.import(bytes) as DiagnosticArchiveImporter.ImportResult.Accepted
        assertNotNull(accepted.previous)
        val reimported = importer.import(bytes) as DiagnosticArchiveImporter.ImportResult.Accepted
        assertEquals(accepted.previous.keys, reimported.previous.keys)
    }

    private fun archiveFor(
        build: String,
        sha: String,
        featureScopes: List<String>,
        tamper: Boolean = false,
    ): ByteArray {
        val inputs =
            DiagnosticReportBuilder.Inputs(
                report = reportFor(featureScopes),
                environment =
                    DiagnosticReportBuilder.Environment(
                        appVersion = "1.2.0",
                        appBuildSha = sha,
                        appVersionCode = 10042L,
                        whatsappPackage = "com.whatsapp",
                        whatsappVersion = build,
                        androidVersion = "17",
                        androidSdk = 37,
                        abi = "arm64-v8a",
                    ),
                hooks = listOf("modern.feature.custom_time.state"),
                resolverStates = mapOf("jid_class" to "AVAILABLE"),
                sanitizedLog = null,
            )
        val entries = DiagnosticReportBuilder.entries(inputs)
        val sealed =
            if (tamper) {
                entries.map {
                    if (it.name == "results.json") {
                        DiagnosticZipExporter.Entry(it.name, "{\"tampered\":true}".toByteArray())
                    } else {
                        it
                    }
                }
            } else {
                entries
            }
        return exporter.build(sealed).bytes
    }

    private fun reportFor(featureScopes: List<String>): DiagnosticEngine.Report {
        val engine = DiagnosticEngine()
        try {
            val features =
                featureScopes.map { id ->
                    AtomicCheckInventory.featureCheck(id, id, emptyList(), "pref_$id", false)
                }
            return engine.run(
                DiagnosticEngine.RunConfig.deep("2.26.39.74", "com.whatsapp"),
                AtomicCheckInventory.PIPELINE + features,
                emptyMap(),
            )
        } finally {
            engine.shutdown()
        }
    }

    private fun result(
        id: String,
        status: DiagnosticStatus,
    ) = AtomicCheckResult(
        id = id,
        title = id,
        scope = "feature:test",
        status = status,
        evidenceLevel = EvidenceLevel.L3_HOOK,
        expected = "",
        observedEvidence = "",
        verification = VerificationState.HOOKED,
        timestampMillis = 0L,
        whatsappBuild = "2.26.39.74",
        severity = "high",
        confidence = 1.0,
        failureClass = FailureClass.NONE,
        remediation = "",
    )

    private fun entry(
        name: String,
        content: String,
    ) = DiagnosticZipExporter.Entry(name, content.toByteArray())
}

package com.wax.module.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.wax.module.R
import com.wax.module.activities.base.BaseActivity
import com.wax.module.diagnostics.selftest.AtomicCheckInventory
import com.wax.module.diagnostics.selftest.DiagnosticArchiveImporter
import com.wax.module.diagnostics.selftest.DiagnosticEngine
import com.wax.module.diagnostics.selftest.DiagnosticProbeSource
import com.wax.module.diagnostics.selftest.DiagnosticReportBuilder
import com.wax.module.diagnostics.selftest.DiagnosticZipExporter
import com.wax.module.diagnostics.selftest.ExportRedactor
import com.wax.module.diagnostics.selftest.ExternalVerificationStore
import com.wax.module.diagnostics.selftest.FeatureCheckInventory
import com.wax.module.diagnostics.selftest.readBounded
import java.io.OutputStream

/**
 * The F155 Atomic Diagnostic & Self-Test screen (#170).
 *
 * Two modes with very different costs:
 * - **Quick Check** — the cheap pipeline prefix, safe to run on demand and
 *   bounded so it cannot scan the DEX on every launch;
 * - **Deep Atomic Scan** — the whole inventory, with progress, cancellation
 *   and per-check timeouts, run off the UI thread.
 *
 * The screen never claims more than the evidence supports: it shows the status
 * and the evidence level side by side, and a check without an observation says
 * NOT TESTED. Export writes a real, verifiable ZIP through the Storage Access
 * Framework, locally only, after an explicit redaction preview.
 */
class DiagnosticsActivity : BaseActivity() {
    private val engine = DiagnosticEngine()
    private val importer = DiagnosticArchiveImporter()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var output: TextView
    private lateinit var progressLabel: TextView
    private var latest: DiagnosticEngine.Report? = null

    /** Only the user can write here; the scan only ever reads. */
    private lateinit var externalVerifications: ExternalVerificationStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        externalVerifications = ExternalVerificationStore(this)
        DiagnosticProbeSource.attach(this, externalVerifications)

        val root = vertical()
        root.addView(title())
        root.addView(body())
        progressLabel = label("")
        root.addView(progressLabel)
        output = label("")
        output.movementMethod = ScrollingMovementMethod()
        val scroller = ScrollView(this)
        scroller.addView(output)
        root.addView(scroller)

        val runRow = horizontal()
        runRow.addView(button(R.string.diagnostics_quick) { runQuickCheck() })
        runRow.addView(button(R.string.diagnostics_deep) { runDeepScan() })
        runRow.addView(button(R.string.diagnostics_cancel) { cancelScan() })
        root.addView(runRow)

        val verificationRow = horizontal()
        verificationRow.addView(
            button(R.string.diagnostics_confirm_external) { showExternalVerificationDialog() },
        )
        root.addView(verificationRow)

        val exportRow = horizontal()
        exportRow.addView(button(R.string.diagnostics_export) { exportWithConfirmation() })
        exportRow.addView(button(R.string.diagnostics_import) { importPreviousArchive() })
        exportRow.addView(button(R.string.diagnostics_close) { finish() })
        root.addView(exportRow)

        setContentView(root)
    }

    /**
     * Guided external verification.
     *
     * L5 evidence exists only when a person with a second account says they saw
     * the effect. The dialog states plainly what is being claimed, and a
     * confirmation is recorded against this WhatsApp build only — so it can
     * never be carried over to a version it was not made on.
     */
    private fun showExternalVerificationDialog() {
        val build = DiagnosticProbeSource.whatsappBuild()
        val candidates =
            FeatureCheckInventory.features().filter { it.externalConfirmationRequired }
        if (candidates.isEmpty()) {
            AlertDialog
                .Builder(this)
                .setTitle(R.string.diagnostics_confirm_external)
                .setMessage(R.string.diagnostics_external_none)
                .setPositiveButton(R.string.diagnostics_close, null)
                .show()
            return
        }
        val labels =
            candidates
                .map { feature ->
                    val confirmed =
                        externalVerifications.confirmationFor(feature.id, build) != null
                    (if (confirmed) "✓ " else "○ ") + feature.title + " — " + feature.id
                }.toTypedArray()
        AlertDialog
            .Builder(this)
            .setTitle(R.string.diagnostics_confirm_external)
            .setMessage(
                getString(R.string.diagnostics_external_explainer, build) +
                    "\n\n" + labels.joinToString("\n"),
            ).setPositiveButton(R.string.diagnostics_confirm) { _, _ ->
                askWhichToConfirm(candidates, build)
            }.setNegativeButton(R.string.diagnostics_cancel, null)
            .show()
    }

    private fun askWhichToConfirm(
        candidates: List<FeatureCheckInventory.Feature>,
        build: String,
    ) {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.diagnostics_confirm_external)
            .setItems(candidates.map { it.title }.toTypedArray()) { _, which ->
                val feature = candidates[which]
                val already = externalVerifications.confirmationFor(feature.id, build)
                if (already != null) {
                    // Confirming twice would be meaningless; the second action is
                    // the only honest way to undo a claim.
                    externalVerifications.revoke(feature.id, build)
                    mainHandler.post { showExternalVerificationDialog() }
                    return@setItems
                }
                externalVerifications.confirm(
                    featureId = feature.id,
                    whatsappBuild = build,
                    note = "confirmed by the owner in the Manager",
                    nowUtcMillis = System.currentTimeMillis(),
                )
                mainHandler.post {
                    AlertDialog
                        .Builder(this)
                        .setTitle(R.string.diagnostics_confirm_external)
                        .setMessage(
                            getString(R.string.diagnostics_external_recorded, feature.title, build),
                        ).setPositiveButton(R.string.diagnostics_close, null)
                        .show()
                }
            }.setNegativeButton(R.string.diagnostics_cancel, null)
            .show()
    }

    /**
     * Imports a previously exported archive and compares it with this scan.
     *
     * The archive is verified first and refused outright when its own digests do
     * not match, so a comparison can never be built on bytes nobody checked.
     */
    private fun importPreviousArchive() {
        openDocument.launch(arrayOf(ZIP_MIME_TYPE, "*/*"))
    }

    /** Reads the picked archive, refusing anything that fails verification. */
    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val stream = uri?.let { contentResolver.openInputStream(it) }
            if (stream == null) {
                showFailure(getString(R.string.diagnostics_import_failed))
                return@registerForActivityResult
            }
            val bytes =
                try {
                    stream.use { it.readBounded(MAX_IMPORT_BYTES) }
                } catch (failure: RuntimeException) {
                    Log.w(TAG, "could not read the archive", failure)
                    showFailure(getString(R.string.diagnostics_import_failed))
                    return@registerForActivityResult
                }
            val current = latest
            when (val result = DiagnosticArchiveImporter().import(bytes)) {
                is DiagnosticArchiveImporter.ImportResult.Rejected -> {
                    AlertDialog
                        .Builder(this)
                        .setTitle(R.string.diagnostics_import_failed)
                        .setMessage(result.reason.name + ": " + result.detail)
                        .setPositiveButton(R.string.diagnostics_close, null)
                        .show()
                }

                is DiagnosticArchiveImporter.ImportResult.Accepted -> {
                    val message =
                        getString(
                            R.string.diagnostics_import_summary,
                            result.manifest.schemaVersion,
                            result.manifest.whatsappVersion ?: "?",
                            result.manifest.appBuildSha ?: "?",
                            result.previous.size,
                        )
                    val comparison =
                        if (current == null) {
                            getString(R.string.diagnostics_import_run_first)
                        } else {
                            importer.describe(importer.compare(result.previous, current.results))
                        }
                    AlertDialog
                        .Builder(this)
                        .setTitle(R.string.diagnostics_import_title)
                        .setMessage(message + "\n\n" + comparison)
                        .setPositiveButton(R.string.diagnostics_close, null)
                        .show()
                }
            }
        }

    private fun vertical(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

    private fun horizontal(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun label(value: String): TextView =
        TextView(this).apply {
            text = value
            setPadding(0, dp(8), 0, dp(8))
        }

    private fun title(): TextView =
        label(getString(R.string.diagnostics_title)).apply {
            textSize = 20f
            setPadding(0, 0, 0, dp(8))
        }

    private fun body(): TextView = label(getString(R.string.diagnostics_explain))

    private fun button(
        labelRes: Int,
        onClick: () -> Unit,
    ): Button =
        Button(this).apply {
            text = getString(labelRes)
            setOnClickListener { onClick() }
        }

    private fun cancelScan() {
        engine.cancel()
        progressLabel.text = getString(R.string.diagnostics_cancelled)
    }

    private fun runQuickCheck() {
        val config =
            DiagnosticEngine.RunConfig.quick(
                DiagnosticProbeSource.whatsappBuild(),
                DiagnosticProbeSource.TARGET_PACKAGE,
            )
        // Cheap prefix only: no DEX scan, safe to run whenever the user asks.
        runScan(config, engine.quickSubset())
    }

    private fun runDeepScan() {
        val config =
            DiagnosticEngine.RunConfig.deep(
                DiagnosticProbeSource.whatsappBuild(),
                DiagnosticProbeSource.TARGET_PACKAGE,
            )
        // The deep scan is the whole inventory: the shared pipeline plus one
        // hook check and one trigger check per feature, so the report can say
        // which feature a resolver failure actually blocks.
        runScan(config, FeatureCheckInventory.full())
    }

    private fun runScan(
        config: DiagnosticEngine.RunConfig,
        inventory: List<AtomicCheckInventory.Definition>,
    ) {
        output.text = getString(R.string.diagnostics_running)
        progressLabel.text = ""
        val worker = Thread({ scanOnWorkerThread(config, inventory) }, "wax-diagnostics-ui")
        worker.isDaemon = true
        worker.start()
    }

    private fun scanOnWorkerThread(
        config: DiagnosticEngine.RunConfig,
        inventory: List<AtomicCheckInventory.Definition>,
    ) {
        val report =
            engine.run(
                config,
                inventory,
                DiagnosticProbeSource.probes(),
            ) { completed, total, lastId ->
                mainHandler.post {
                    // Progress by verified checks, never an invented percentage.
                    progressLabel.text =
                        getString(R.string.diagnostics_progress, completed, total, lastId)
                }
            }
        mainHandler.post { render(report) }
    }

    private fun render(report: DiagnosticEngine.Report) {
        latest = report
        val summary = report.summary
        val builder = StringBuilder()
        builder.appendLine("${getString(R.string.diagnostics_scan)} ${report.scanId}")
        val counts =
            getString(R.string.diagnostics_counts) + " " +
                "pass=${summary.passed} fail=${summary.failed} " +
                "blocked=${summary.blocked} notTested=${summary.notTested} " +
                "unsupported=${summary.unsupported} " +
                "external=${summary.needsExternalVerification}"
        builder.appendLine(counts)
        report.firstFailedDependency()?.let { first ->
            val firstFailure =
                getString(R.string.diagnostics_first_failed) + " ${first.id} — ${first.remediation}"
            builder.appendLine(firstFailure)
        }
        if (summary.clusters.isNotEmpty()) {
            builder.appendLine(getString(R.string.diagnostics_root_causes))
            for (cluster in summary.clusters) {
                builder.appendLine("• ${cluster.rootCauseId}: ${cluster.rootTitle}")
                builder.appendLine("  ${cluster.symptomIds.joinToString(", ")}")
                if (cluster.remediation.isNotBlank()) {
                    builder.appendLine("  → ${cluster.remediation}")
                }
            }
        }
        builder.appendLine()
        for (result in report.results) {
            val line =
                "${result.id}: ${result.status} [${result.evidenceLevel}/${result.verification}] " +
                    result.remediation
            builder.appendLine(line)
        }
        output.text = builder.toString()
        progressLabel.text = getString(R.string.diagnostics_finished)
    }

    /**
     * Export is local-only and always preceded by a redaction preview and an
     * explicit confirmation; there is no unredacted option in this UI.
     */
    private fun exportWithConfirmation() {
        val report = latest
        if (report == null) {
            toast(getString(R.string.diagnostics_run_first))
            return
        }
        val entries = DiagnosticReportBuilder.entries(reportInputs(report))
        // The archive that gets written is the redacted one. The preview and
        // the export are produced from the same pass, so what the user confirms
        // is exactly what lands on storage.
        val redactor = ExportRedactor()
        val redacted = redactor.redactEntries(entries)
        val preview = redactor.redactAll(entries.map { String(it.content) })
        AlertDialog
            .Builder(this)
            .setTitle(R.string.diagnostics_redaction_preview)
            .setMessage(redactionMessage(preview.text, redacted.report.total))
            .setPositiveButton(R.string.diagnostics_export) { _, _ ->
                // The redaction account is written from what the redactor really
                // removed, then the digests are re-sealed over the final bytes.
                writeZip(DiagnosticReportBuilder.withRedactionReport(redacted.entries, redacted.report))
            }.setNegativeButton(R.string.diagnostics_cancel, null)
            .show()
    }

    private fun redactionMessage(
        preview: String,
        total: Int,
    ): String = getString(R.string.diagnostics_redaction_summary, total) + "\n\n" + preview.take(1200)

    private fun reportInputs(report: DiagnosticEngine.Report): DiagnosticReportBuilder.Inputs =
        DiagnosticReportBuilder.Inputs(
            report = report,
            environment = DiagnosticProbeSource.environment(),
            hooks = DiagnosticProbeSource.reportedHooks(),
            resolverStates = DiagnosticProbeSource.reportedResolvers(),
            sanitizedLog = null,
        )

    /** Held between launching the SAF picker and writing to the chosen document. */
    private var pendingBytes: ByteArray? = null

    private fun writeZip(redactedEntries: List<DiagnosticZipExporter.Entry>) {
        val exporter = DiagnosticZipExporter()
        val built =
            try {
                exporter.build(redactedEntries)
            } catch (failure: RuntimeException) {
                Log.w(TAG, "export failed", failure)
                showFailure(failure.message ?: "")
                return
            }
        // Verified before the user is offered anything: an archive that cannot
        // be re-opened, or whose checksums do not match, must never be
        // presented as a finished report.
        val verification = exporter.verify(built.bytes)
        if (!verification.valid) {
            showFailure(getString(R.string.diagnostics_manifest_missing))
            return
        }
        pendingBytes = built.bytes
        createDocument.launch(exporter.fileName(System.currentTimeMillis()))
    }

    /** Writes the verified archive to the document the user picked, or reports why not. */
    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(ZIP_MIME_TYPE)) { uri ->
            val bytes = pendingBytes
            pendingBytes = null
            if (uri == null || bytes == null) return@registerForActivityResult
            try {
                val stream =
                    contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("storage provider returned no stream")
                stream.use { DiagnosticZipExporter().writeTo(ResolverTarget(it), bytes) }
                AlertDialog
                    .Builder(this)
                    .setTitle(R.string.diagnostics_export_done)
                    .setMessage(uri.toString())
                    .show()
            } catch (failure: Exception) {
                Log.w(TAG, "could not write the export", failure)
                showFailure(failure.message ?: "")
            }
        }

    private fun showFailure(reason: String) {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.diagnostics_export_failed)
            .setMessage(reason)
            .show()
    }

    /** Streams the verified archive into the document the user picked. */
    private class ResolverTarget(
        private val stream: OutputStream,
    ) : DiagnosticZipExporter.OutputStreamTarget {
        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            stream.write(buffer, offset, length)
        }

        override fun finish() {
            stream.flush()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        engine.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WA-X Diagnostics"
        const val ZIP_MIME_TYPE = "application/zip"

        /**
         * An imported archive is untrusted input, so it is read under a cap
         * rather than into memory on the strength of its own claims.
         */
        const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
    }
}

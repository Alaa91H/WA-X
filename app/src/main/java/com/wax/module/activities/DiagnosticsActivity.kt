package com.wax.module.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
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
import com.wax.module.diagnostics.selftest.DiagnosticScanFallback
import com.wax.module.diagnostics.selftest.DiagnosticScanSession
import com.wax.module.diagnostics.selftest.DiagnosticStatus
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
    private val scanSession = DiagnosticScanSession()
    private val importer = DiagnosticArchiveImporter()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var output: TextView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var progressLabel: TextView
    private var latest: DiagnosticEngine.Report? = null

    /** Only the user can write here; the scan only ever reads. */
    private lateinit var externalVerifications: ExternalVerificationStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        externalVerifications = ExternalVerificationStore(this)
        DiagnosticProbeSource.attach(this, externalVerifications)

        // A long atomic report scrolls independently; ZIP actions stay visible.
        // Preserve the widget IDs and footer contract from #475.
        val root = vertical()
        val reportContent = vertical()
        reportContent.addView(title())
        reportContent.addView(body())
        progressLabel = label(getString(R.string.diagnostics_no_results))
        reportContent.addView(progressLabel)
        output = label(getString(R.string.diagnostics_no_results))
        resultsContainer = vertical().apply { setPadding(0, 0, 0, 0) }
        resultsContainer.addView(output)
        reportContent.addView(resultsContainer)
        val scroller =
            ScrollView(this).apply {
                id = R.id.diagnostics_report_scroll
                isFillViewport = false
                addView(reportContent)
            }
        root.addView(
            scroller,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val actions =
            vertical().apply {
                id = R.id.diagnostics_actions
                setPadding(dp(16), dp(4), dp(16), dp(16))
            }
        val runRow = horizontal()
        runRow.addView(button(R.string.diagnostics_quick) { runQuickCheck() })
        runRow.addView(button(R.string.diagnostics_deep) { runDeepScan() })
        runRow.addView(button(R.string.diagnostics_cancel) { cancelScan() })
        actions.addView(runRow)

        val verificationRow = horizontal()
        verificationRow.addView(
            button(R.string.diagnostics_confirm_external) { showExternalVerificationDialog() },
        )
        actions.addView(verificationRow)

        val exportRow = horizontal()
        exportRow.addView(button(R.string.diagnostics_export) { exportWithConfirmation() })
        exportRow.addView(button(R.string.diagnostics_import) { importPreviousArchive() })
        exportRow.addView(button(R.string.diagnostics_close) { finish() })
        actions.addView(exportRow)
        root.addView(actions)
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

    private fun scrollRow(row: LinearLayout): HorizontalScrollView =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }

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
        if (scanSession.cancel()) {
            engine.cancel()
            progressLabel.text = getString(R.string.diagnostics_cancelled)
        }
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
        val token = scanSession.begin()
        if (token == null) {
            toast(getString(R.string.diagnostics_busy))
            return
        }
        output.text = getString(R.string.diagnostics_running)
        resultsContainer.removeAllViews()
        resultsContainer.addView(output)
        progressLabel.text = getString(R.string.diagnostics_progress, 0, inventory.size, "")
        val worker =
            Thread(
                { scanOnWorkerThread(token, config, inventory) },
                "wax-diagnostics-ui",
            )
        worker.isDaemon = true
        worker.start()
    }

    private fun scanOnWorkerThread(
        token: Long,
        config: DiagnosticEngine.RunConfig,
        inventory: List<AtomicCheckInventory.Definition>,
    ) {
        val report =
            try {
                engine.run(config, inventory, DiagnosticProbeSource.probes()) { completed, total, lastId ->
                    mainHandler.post {
                        if (scanSession.acceptsProgress(token) && !isDestroyed) {
                            progressLabel.text =
                                getString(R.string.diagnostics_progress, completed, total, lastId)
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                // No exception message, stacktrace or contact metadata in the
                // exported record. A failed scan still produces a truthful ZIP.
                Log.w(TAG, "diagnostic scan runner failed: ${failure.javaClass.simpleName}")
                DiagnosticScanFallback.failed(
                    config,
                    failure.javaClass.simpleName,
                    System.currentTimeMillis(),
                )
            }
        mainHandler.post {
            if (isDestroyed || isFinishing) return@post
            when (scanSession.finish(token)) {
                DiagnosticScanSession.Completion.STALE -> {
                    Unit
                }

                DiagnosticScanSession.Completion.CANCELLED -> {
                    render(report)
                    progressLabel.text = getString(R.string.diagnostics_cancelled)
                }

                DiagnosticScanSession.Completion.FINISHED -> {
                    render(report)
                    if (report.results.isEmpty()) {
                        progressLabel.text = getString(R.string.diagnostics_no_results)
                    } else if (report.scanId.startsWith("failed-")) {
                        progressLabel.text = getString(R.string.diagnostics_scan_failed)
                    }
                }
            }
        }
    }

    private fun render(report: DiagnosticEngine.Report) {
        latest = report
        val summary = report.summary
        // Summarize first. Never dump the raw diagnostic evidence as the
        // default UI; technical IDs and observations remain available on tap.
        output.text =
            buildString {
                appendLine(getString(R.string.diagnostics_scan) + " " + report.scanId)
                appendLine(
                    getString(
                        R.string.diagnostics_summary_readable,
                        summary.passed,
                        summary.failed,
                        summary.blocked,
                        summary.notTested,
                    ),
                )
                if (summary.unsupported > 0 || summary.needsExternalVerification > 0) {
                    appendLine(
                        getString(
                            R.string.diagnostics_summary_additional,
                            summary.unsupported,
                            summary.needsExternalVerification,
                        ),
                    )
                }
                report.firstFailedDependency()?.let {
                    appendLine(getString(R.string.diagnostics_first_failed) + " " + it.title)
                }
            }
        resultsContainer.removeAllViews()
        resultsContainer.addView(output)
        if (report.results.isEmpty()) {
            resultsContainer.addView(label(getString(R.string.diagnostics_no_results)))
        } else {
            val groups =
                report.results.groupBy {
                    if (it.scope.startsWith("feature:")) {
                        R.string.diagnostics_feature_checks
                    } else {
                        R.string.diagnostics_system_checks
                    }
                }
            for ((groupLabel, checks) in groups) {
                val heading =
                    label(getString(groupLabel)).apply {
                        textSize = 17f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                resultsContainer.addView(heading)
                for (check in checks) {
                    val status = localizedStatus(check.status)
                    val row =
                        label(
                            buildString {
                                appendLine(check.title + " — " + status)
                                append(check.evidenceLevel.name + " / " + check.verification.name)
                                if (check.status != DiagnosticStatus.PASS && check.remediation.isNotBlank()) {
                                    appendLine()
                                    append(check.remediation)
                                }
                            },
                        ).apply {
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                            setBackgroundResource(android.R.drawable.list_selector_background)
                            isFocusable = true
                            isClickable = true
                            contentDescription = check.title + ", " + status
                            setOnClickListener {
                                AlertDialog
                                    .Builder(this@DiagnosticsActivity)
                                    .setTitle(check.title)
                                    .setMessage(
                                        "${check.id}\n${check.status} [${check.evidenceLevel}/${check.verification}]" +
                                            "\n${check.failureClass}\n${check.remediation}",
                                    ).setPositiveButton(R.string.diagnostics_close, null)
                                    .show()
                            }
                        }
                    resultsContainer.addView(row)
                }
            }
        }
        progressLabel.text = getString(R.string.diagnostics_finished)
    }

    private fun localizedStatus(status: DiagnosticStatus): String =
        getString(
            when (status) {
                DiagnosticStatus.PASS -> R.string.diagnostics_status_pass
                DiagnosticStatus.FAIL -> R.string.diagnostics_status_fail
                DiagnosticStatus.BLOCKED -> R.string.diagnostics_status_blocked
                DiagnosticStatus.NOT_TESTED -> R.string.diagnostics_status_not_tested
                DiagnosticStatus.UNSUPPORTED -> R.string.diagnostics_status_unsupported
                DiagnosticStatus.NEEDS_EXTERNAL_VERIFICATION -> R.string.diagnostics_status_external
                DiagnosticStatus.RUNNING -> R.string.diagnostics_running
            },
        )

    /**
     * Export is local-only and always preceded by a redaction preview and an
     * explicit confirmation; there is no unredacted option in this UI.
     */
    private fun exportWithConfirmation() {
        // An unavailable/failed scan must not hide the export action. The
        // archive contains explicit zero observations, never invented passes.
        val report =
            latest ?: DiagnosticScanFallback.unrun(
                DiagnosticEngine.RunConfig.quick(
                    DiagnosticProbeSource.whatsappBuild(),
                    DiagnosticProbeSource.TARGET_PACKAGE,
                ),
                System.currentTimeMillis(),
            )
        try {
            val entries = DiagnosticReportBuilder.entries(reportInputs(report))
            val redactor = ExportRedactor()
            val redacted = redactor.redactEntries(entries)
            val preview = redactor.redactAll(entries.map { String(it.content, Charsets.UTF_8) })
            AlertDialog
                .Builder(this)
                .setTitle(R.string.diagnostics_redaction_preview)
                .setMessage(
                    (if (latest == null) getString(R.string.diagnostics_export_without_scan) + "\n\n" else "") +
                        redactionMessage(preview.text, redacted.report.total),
                ).setPositiveButton(R.string.diagnostics_export) { _, _ ->
                    writeZip(DiagnosticReportBuilder.withRedactionReport(redacted.entries, redacted.report))
                }.setNegativeButton(R.string.diagnostics_cancel, null)
                .show()
        } catch (failure: Exception) {
            Log.w(TAG, "could not prepare redacted diagnostics: ${failure.javaClass.simpleName}")
            showFailure(failure.javaClass.simpleName)
        }
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
        progressLabel.text = getString(R.string.diagnostics_export_preparing)
        Thread({
            val exporter = DiagnosticZipExporter()
            val built =
                try {
                    exporter.build(redactedEntries).also {
                        require(exporter.verify(it.bytes).valid) {
                            "diagnostic ZIP failed pre-export verification"
                        }
                    }
                } catch (failure: Exception) {
                    Log.w(TAG, "diagnostic ZIP preparation failed: ${failure.javaClass.simpleName}")
                    mainHandler.post {
                        if (!isDestroyed) showFailure(failure.javaClass.simpleName)
                    }
                    return@Thread
                }
            mainHandler.post {
                if (isDestroyed || isFinishing) return@post
                pendingBytes = built.bytes
                createDocument.launch(exporter.fileName(System.currentTimeMillis()))
            }
        }, "wax-diagnostics-zip").apply { isDaemon = true }.start()
    }

    /** Writes the verified archive to the document the user picked, or reports why not. */
    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(ZIP_MIME_TYPE)) { uri ->
            val bytes = pendingBytes
            pendingBytes = null
            if (uri == null) {
                progressLabel.text = getString(R.string.diagnostics_cancelled)
                return@registerForActivityResult
            }
            if (bytes == null) {
                showFailure(getString(R.string.diagnostics_export_lost))
                return@registerForActivityResult
            }
            progressLabel.text = getString(R.string.diagnostics_export_preparing)
            Thread({
                try {
                    val stream =
                        contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException("storage provider returned no stream")
                    stream.use { DiagnosticZipExporter().writeTo(ResolverTarget(it), bytes) }
                    // Do not claim SAF success until the exact file is readable
                    // and its ZIP digest manifest has been rechecked.
                    val readBack =
                        contentResolver
                            .openInputStream(uri)
                            ?.use { it.readBounded(MAX_IMPORT_BYTES) }
                            ?: throw IllegalStateException("provider read-back unavailable")
                    if (!bytes.contentEquals(readBack) ||
                        !DiagnosticZipExporter().verify(readBack).valid
                    ) {
                        throw IllegalStateException("saved archive integrity verification failed")
                    }
                    mainHandler.post {
                        if (!isDestroyed) {
                            progressLabel.text = getString(R.string.diagnostics_export_done)
                            AlertDialog
                                .Builder(this)
                                .setTitle(R.string.diagnostics_export_done)
                                .setMessage(getString(R.string.diagnostics_export_verified))
                                .setPositiveButton(R.string.diagnostics_close, null)
                                .show()
                        }
                    }
                } catch (failure: Exception) {
                    Log.w(TAG, "diagnostic ZIP write/verify failed: ${failure.javaClass.simpleName}")
                    mainHandler.post {
                        if (!isDestroyed) showFailure(failure.javaClass.simpleName)
                    }
                }
            }, "wax-diagnostics-save").apply { isDaemon = true }.start()
        }

    private fun showFailure(reason: String) {
        progressLabel.text = getString(R.string.diagnostics_scan_failed)
        if (isDestroyed || isFinishing) return
        AlertDialog
            .Builder(this)
            .setTitle(R.string.diagnostics_export_failed)
            .setMessage(reason)
            .setPositiveButton(R.string.diagnostics_close, null)
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
        scanSession.invalidate()
        engine.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
        pendingBytes = null
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

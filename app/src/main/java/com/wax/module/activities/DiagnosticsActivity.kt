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
import com.wax.module.diagnostics.selftest.DiagnosticEngine
import com.wax.module.diagnostics.selftest.DiagnosticProbeSource
import com.wax.module.diagnostics.selftest.DiagnosticReportBuilder
import com.wax.module.diagnostics.selftest.DiagnosticZipExporter
import com.wax.module.diagnostics.selftest.ExportRedactor
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var output: TextView
    private lateinit var progressLabel: TextView
    private var latest: DiagnosticEngine.Report? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticProbeSource.attach(this)

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

        val exportRow = horizontal()
        exportRow.addView(button(R.string.diagnostics_export) { exportWithConfirmation() })
        exportRow.addView(button(R.string.diagnostics_close) { finish() })
        root.addView(exportRow)

        setContentView(root)
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
        runScan(config, AtomicCheckInventory.all())
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
                    progressLabel.text = "$completed / $total  ($lastId)"
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
                writeZip(redacted.entries)
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
                val stream = contentResolver.openOutputStream(uri)
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
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics_export_failed)
            .setMessage(reason)
            .show()
    }

    /** Streams the verified archive into the document the user picked. */
    private class ResolverTarget(private val stream: OutputStream) :
        DiagnosticZipExporter.OutputStreamTarget {
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
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
    }
}

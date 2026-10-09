package com.wax.module.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.wax.module.activities.base.BaseActivity
import com.wax.module.diagnostics.selftest.AtomicCheckInventory
import com.wax.module.diagnostics.selftest.DiagnosticEngine
import com.wax.module.diagnostics.selftest.DiagnosticProbeSource
import com.wax.module.diagnostics.selftest.DiagnosticReportBuilder
import com.wax.module.diagnostics.selftest.DiagnosticZipExporter
import com.wax.module.diagnostics.selftest.DiagnosticStatus
import com.wax.module.diagnostics.selftest.ExportRedactor

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.diagnostics_title)
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.diagnostics_explain)
            setPadding(0, dp(8), 0, dp(8))
        })

        progressLabel = TextView(this).apply { text = "" }
        root.addView(progressLabel)

        output = TextView(this).apply {
            movementMethod = ScrollingMovementMethod()
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(ScrollView(this).apply { addView(output) })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button(R.string.diagnostics_quick) { runQuickCheck() })
        buttons.addView(button(R.string.diagnostics_deep) { runDeepScan() })
        buttons.addView(button(R.string.diagnostics_cancel) {
            engine.cancel()
            progressLabel.text = getString(R.string.diagnostics_cancelled)
        })
        root.addView(buttons)

        val exportRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        exportRow.addView(button(R.string.diagnostics_export) { exportWithConfirmation() })
        exportRow.addView(button(R.string.diagnostics_close) { finish() })
        root.addView(exportRow)

        setContentView(root)
    }

    private fun button(labelRes: Int, onClick: () -> Unit) = Button(this).apply {
        text = getString(labelRes)
        setOnClickListener { onClick() }
    }

    private fun runQuickCheck() = runScan(DiagnosticEngine.RunConfig.quick(
        DiagnosticProbeSource.whatsappBuild(), DiagnosticProbeSource.TARGET_PACKAGE,
    ))

    private fun runDeepScan() = runScan(DiagnosticEngine.RunConfig.deep(
        DiagnosticProbeSource.whatsappBuild(), DiagnosticProbeSource.TARGET_PACKAGE,
    ))

    private fun runScan(config: DiagnosticEngine.RunConfig) {
        output.text = getString(R.string.diagnostics_running)
        progressLabel.text = ""
        Thread({
            val report = engine.run(
                config,
                AtomicCheckInventory.all(),
                DiagnosticProbeSource.probes(),
            ) { completed, total, lastId ->
                mainHandler.post {
                    // Progress by verified checks, never an invented percentage.
                    progressLabel.text = "$completed / $total  ($lastId)"
                }
            }
            mainHandler.post { render(report) }
        }, "wax-diagnostics-ui").apply { isDaemon = true }.start()
    }

    private fun render(report: DiagnosticEngine.Report) {
        latest = report
        val summary = report.summary
        val builder = StringBuilder()
        builder.appendLine(
            "${getString(R.string.diagnostics_scan)} ${report.scanId}",
        )
        builder.appendLine(
            getString(R.string.diagnostics_counts) + " " +
                "pass=${summary.passed} fail=${summary.failed} blocked=${summary.blocked} " +
                "notTested=${summary.notTested} unsupported=${summary.unsupported} " +
                "external=${summary.needsExternalVerification}",
        )
        report.firstFailedDependency()?.let { first ->
            builder.appendLine(
                getString(R.string.diagnostics_first_failed) + " ${first.id} — ${first.remediation}",
            )
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
            builder.appendLine(
                "${result.id}: ${result.status} [${result.evidenceLevel}/" +
                    "${result.verification}] ${result.remediation}",
            )
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
            Toast(getString(R.string.diagnostics_run_first))
            return
        }
        val inputs = DiagnosticReportBuilder.Inputs(
            report = report,
            environment = DiagnosticProbeSource.environment(),
            hooks = DiagnosticProbeSource.reportedHooks(),
            resolverStates = DiagnosticProbeSource.reportedResolvers(),
            sanitizedLog = null,
        )
        val entries = DiagnosticReportBuilder.entries(inputs)
        val preview = ExportRedactor().redactAll(entries.map { String(it.content) })
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics_redaction_preview)
            .setMessage(
                getString(R.string.diagnostics_redaction_summary, preview.report.total) +
                    "\n\n" + preview.text.take(1200),
            )
            .setPositiveButton(R.string.diagnostics_export) { _, _ -> writeZip(entries) }
            .setNegativeButton(R.string.diagnostics_cancel, null)
            .show()
    }

    private fun writeZip(entries: List<DiagnosticZipExporter.Entry>) {
        val declaredMissing = entries.filter { it.name == "manifest.json" }.map {
            it.content.toString(Charsets.UTF_8)
        }
        val withChecksums = entries + DiagnosticZipExporter.Entry(
            "checksums.sha256",
            DiagnosticZipExporter().checksums(entries),
        )
        val name = DiagnosticZipExporter().fileName(System.currentTimeMillis())
        val built = try {
            DiagnosticZipExporter().build(withChecksums, declaredMissing)
        } catch (failure: RuntimeException) {
            Log.w("WA-X Diagnostics", "export failed", failure)
            AlertDialog.Builder(this)
                .setTitle(R.string.diagnostics_export_failed)
                .setMessage(failure.message ?: "")
                .show()
            return
        }
        // Verified before the user is offered anything: an archive that cannot
        // be re-opened must never be presented as a finished report.
        if (!DiagnosticZipExporter().verify(built.bytes).manifestPresent) {
            AlertDialog.Builder(this)
                .setTitle(R.string.diagnostics_export_failed)
                .setMessage(getString(R.string.diagnostics_manifest_missing))
                .show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, REQUEST_EXPORT)
        pendingBytes = built.bytes
    }

    private var pendingBytes: ByteArray? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != Activity.RESULT_OK) return
        val bytes = pendingBytes ?: return
        val uri = data?.data ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                DiagnosticZipExporter().writeTo(
                    object : DiagnosticZipExporter.OutputStreamTarget {
                        override fun write(buffer: ByteArray, offset: Int, length: Int) {
                            stream.write(buffer, offset, length)
                        }

                        override fun finish() {
                            stream.flush()
                        }
                    },
                    bytes,
                )
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.diagnostics_export_done)
                .setMessage(uri.toString())
                .show()
        } catch (failure: Exception) {
            Log.w("WA-X Diagnostics", "could not write the export", failure)
            AlertDialog.Builder(this)
                .setTitle(R.string.diagnostics_export_failed)
                .setMessage(failure.message ?: "")
                .show()
        } finally {
            pendingBytes = null
        }
    }

    private fun Toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        engine.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_EXPORT = 0x0D19
    }
}
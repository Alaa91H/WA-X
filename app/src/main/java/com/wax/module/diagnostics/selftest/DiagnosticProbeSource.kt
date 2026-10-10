package com.wax.module.diagnostics.selftest

import android.content.Context
import android.os.Build
import com.wax.module.BuildConfig
import com.wax.module.modern.ModernManagerRuntimeStatus
import com.wax.module.modern.ModernTargetTelemetryProvider
import com.wax.module.platform.SupportedPackages

/**
 * Supplies the F155 probes from state that already exists.
 *
 * It deliberately reuses [ModernManagerRuntimeStatus] and the telemetry report
 * store rather than inventing a parallel health system: the engine asks
 * questions, and the answers come from the same evidence the Home screen
 * shows. A probe that cannot observe returns null, which the engine reports as
 * `NOT_TESTED` — the difference between "not measured" and "broken".
 */
object DiagnosticProbeSource {
    const val TARGET_PACKAGE = "com.whatsapp"

    fun whatsappBuild(): String =
        runCatching {
            val info = context()?.packageManager?.getPackageInfo(TARGET_PACKAGE, 0)
            info?.versionName
        }.getOrNull() ?: "unknown"

    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    private fun context(): Context? = appContext

    /** The probes the Manager can actually answer right now. */
    fun probes(): Map<String, DiagnosticEngine.Probe> =
        linkedMapOf(
            AtomicCheckInventory.ENV_ANDROID to DiagnosticEngine.Probe {
                DiagnosticEngine.Observation(
                    evidence = "Android ${Build.VERSION.RELEASE} sdk ${Build.VERSION.SDK_INT} " +
                        Build.SUPPORTED_ABIS.joinToString(","),
                    level = EvidenceLevel.L0_PACKAGE,
                    verification = VerificationState.LOCALLY_VERIFIED,
                )
            },
            AtomicCheckInventory.ENV_TARGET to DiagnosticEngine.Probe {
                val snapshot = snapshot() ?: return@Probe null
                val target = snapshot.targets.firstOrNull { it.packageName == TARGET_PACKAGE }
                    ?: return@Probe null
                DiagnosticEngine.Observation(
                    evidence = "${target.packageName} evidence=${target.evidence}",
                    level = EvidenceLevel.L0_PACKAGE,
                    verification = VerificationState.LOCALLY_VERIFIED,
                )
            },
            AtomicCheckInventory.FRAMEWORK_API102 to DiagnosticEngine.Probe {
                val snapshot = snapshot() ?: return@Probe null
                val api = snapshot.frameworkApi
                DiagnosticEngine.Observation(
                    evidence = "framework=${snapshot.frameworkName ?: "unknown"} api=$api",
                    level = EvidenceLevel.L0_PACKAGE,
                    verification = VerificationState.LOCALLY_VERIFIED,
                    expectedMatch = api != null && api >= 100,
                    failureClass = FailureClass.FRAMEWORK_UNAVAILABLE,
                )
            },
            AtomicCheckInventory.MODULE_LOADED to DiagnosticEngine.Probe {
                val target = target() ?: return@Probe null
                if (!target.bootstrapMilestones.contains("MODULE_LOADED")) return@Probe null
                DiagnosticEngine.Observation(
                    evidence = "milestones=" + target.bootstrapMilestones.joinToString(">"),
                    level = EvidenceLevel.L1_LIFECYCLE,
                    verification = VerificationState.TRIGGERED,
                )
            },
            AtomicCheckInventory.APP_ATTACH to DiagnosticEngine.Probe {
                val target = target() ?: return@Probe null
                if (!target.bootstrapMilestones.contains("ATTACH_OBSERVED")) return@Probe null
                DiagnosticEngine.Observation(
                    evidence = "Application.attach intercepted",
                    level = EvidenceLevel.L1_LIFECYCLE,
                    verification = VerificationState.TRIGGERED,
                )
            },
            AtomicCheckInventory.MANAGER_IPC to DiagnosticEngine.Probe {
                val target = target() ?: return@Probe null
                if (target.bootstrapMilestones.none { it.endsWith("HEARTBEAT_WRITE_CONFIRMED") }) {
                    return@Probe null
                }
                DiagnosticEngine.Observation(
                    evidence = "provider accepted an authenticated report",
                    level = EvidenceLevel.L1_LIFECYCLE,
                    verification = VerificationState.LOCALLY_VERIFIED,
                )
            },
            AtomicCheckInventory.HEARTBEAT to DiagnosticEngine.Probe {
                val target = target() ?: return@Probe null
                DiagnosticEngine.Observation(
                    evidence = "evidence=" + target.evidence,
                    level = EvidenceLevel.L1_LIFECYCLE,
                    verification =
                        if (target.evidence ==
                            ModernManagerRuntimeStatus.Evidence.LIVE_HEARTBEAT
                        ) {
                            VerificationState.LOCALLY_VERIFIED
                        } else {
                            VerificationState.HOOKED
                        },
                    expectedMatch = target.evidence ==
                        ModernManagerRuntimeStatus.Evidence.LIVE_HEARTBEAT,
                    failureClass = FailureClass.SCOPE_MISSING,
                )
            },
            AtomicCheckInventory.DEXKIT_NATIVE to DiagnosticEngine.Probe {
                val loaded = runCatching { System.loadLibrary("dexkit") }.isSuccess
                DiagnosticEngine.Observation(
                    evidence = if (loaded) "dexkit native library loaded" else "load failed",
                    level = EvidenceLevel.L2_RESOLVER,
                    verification =
                        if (loaded) {
                            VerificationState.LOCALLY_VERIFIED
                        } else {
                            VerificationState.NOT_OBSERVED
                        },
                    expectedMatch = loaded,
                    failureClass = FailureClass.DEPENDENCY_MISSING,
                )
            },
            AtomicCheckInventory.ENV_SCOPE to DiagnosticEngine.Probe {
                val packages = SupportedPackages.ALL
                if (!packages.contains(TARGET_PACKAGE)) return@Probe null
                DiagnosticEngine.Observation(
                    evidence = "supported=${packages.joinToString(",")}",
                    level = EvidenceLevel.L0_PACKAGE,
                    verification = VerificationState.LOCALLY_VERIFIED,
                )
            },
            AtomicCheckInventory.REGISTRY to DiagnosticEngine.Probe {
                // The registry lives in the hooked process, so the honest answer
                // here is whatever the target reported back, not a source file and
                // not an assumed count.
                val reported = reportedFeatureStates().size
                if (reported == 0) return@Probe null
                DiagnosticEngine.Observation(
                    evidence = "features reported by the target=$reported",
                    level = EvidenceLevel.L2_RESOLVER,
                    verification = VerificationState.LOCALLY_VERIFIED,
                    expectedMatch = reported > 0,
                    failureClass = FailureClass.DEPENDENCY_MISSING,
                )
            },
            // The four contact/JID resolver checks answer from the states the
            // target itself reported, which is exactly the observed failure chain.
            AtomicCheckInventory.CONTACT_CLASS to resolverProbe("contact_access"),
            AtomicCheckInventory.CONTACT_DATA_CLASS to resolverProbe("contact_data_class"),
            AtomicCheckInventory.JID_CLASS to resolverProbe("jid_class"),
            AtomicCheckInventory.JID_RAW_STRING to resolverProbe("jid_raw_string"),
            AtomicCheckInventory.MESSAGE_CLASS to resolverProbe("message_class"),
            AtomicCheckInventory.MESSAGE_KEY_CLASS to resolverProbe("message_key_class"),
        )

        /**
     * Maps a pipeline resolver check onto the state the target reported for it.
     *
     * A reported failure keeps its own reason so the clusterer can attach every
     * downstream symptom to this one cause instead of repeating it.
     */
    private fun resolverProbe(stateKey: String) = DiagnosticEngine.Probe {
        val reported = reportedState(stateKey) ?: return@Probe null
        val failed = reported.contains("_MISSING") || reported.contains("_UNRESOLVED") ||
            reported.contains("_AMBIGUOUS") || reported.contains("_UNSUPPORTED") ||
            reported.startsWith("ERROR") || reported == "UNSUPPORTED_TARGET"
        DiagnosticEngine.Observation(
            evidence = reported,
            level = EvidenceLevel.L2_RESOLVER,
            verification = if (failed) VerificationState.NOT_OBSERVED else VerificationState.LOCALLY_VERIFIED,
            expectedMatch = !failed,
            failureClass =
                when {
                    reported.contains("AMBIGUOUS") -> FailureClass.RESOLVER_AMBIGUOUS
                    failed -> FailureClass.DEPENDENCY_MISSING
                    else -> FailureClass.NONE
                },
        )
    }

    private fun snapshot(): ModernManagerRuntimeStatus.Snapshot? {
        val context = context() ?: return null
        return runCatching { ModernManagerRuntimeStatus.inspect(context) }.getOrNull()
    }

    private fun target(): ModernManagerRuntimeStatus.Target? =
        snapshot()?.targets?.firstOrNull { it.packageName == TARGET_PACKAGE }

    private fun reportedState(key: String): String? =
        runCatching { targetPrefs()?.getString("modern.feature.$key.state.$TARGET_PACKAGE", null) }
            .getOrNull()

    private fun targetPrefs() = context()?.getSharedPreferences(
        ModernTargetTelemetryProvider.LOCAL_PREFS,
        Context.MODE_PRIVATE,
    )

    /** Feature states the target reported, keyed by feature id. */
    private fun reportedFeatureStates(): Map<String, String> {
        val prefs = targetPrefs() ?: return emptyMap()
        return prefs.all
            .filter { it.key.startsWith("modern.feature.") && it.key.contains(".state.$TARGET_PACKAGE") }
            .mapNotNull { (key, value) ->
                val feature = value as? String ?: return@mapNotNull null
                key.substringAfter("modern.feature.").substringBefore(".state.") to feature
            }
            .toMap()
    }

    fun environment(): DiagnosticReportBuilder.Environment {
        val context = context()
        return DiagnosticReportBuilder.Environment(
            appVersion = BuildConfig.VERSION_NAME,
            appBuildSha = BuildConfig.VERSION_CODE.toString(),
            whatsappPackage = TARGET_PACKAGE,
            whatsappVersion = whatsappBuild(),
            androidVersion = Build.VERSION.RELEASE,
            androidSdk = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.joinToString(","),
        )
    }

    /** Hook ids currently reported by the target, for `hooks.json`. */
    fun reportedHooks(): List<String> = targetPrefs()
        ?.all
        ?.keys
        ?.filter { it.startsWith("modern.feature.") && it.contains(".state.$TARGET_PACKAGE") }
        ?.sorted()
        ?: emptyList()

    /** Resolver states the target reported, for `resolvers.json`. */
    fun reportedResolvers(): Map<String, String> = reportedFeatureStates()
}

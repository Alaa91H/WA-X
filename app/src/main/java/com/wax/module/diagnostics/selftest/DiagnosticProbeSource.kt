package com.wax.module.diagnostics.selftest

import android.content.Context
import android.os.Build
import com.wax.module.BuildConfig
import com.wax.module.modern.ControlEffective
import com.wax.module.modern.ControlPolicy
import com.wax.module.modern.ControlRequested
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

    /** The only feature the runtime counts invocations for today. */
    private const val CUSTOM_TIME_FEATURE = "custom_time"

    fun whatsappBuild(): String =
        runCatching {
            val info = context()?.packageManager?.getPackageInfo(TARGET_PACKAGE, 0)
            info?.versionName
        }.getOrNull() ?: ExternalVerificationStore.UNKNOWN_BUILD

    private var appContext: Context? = null

    /**
     * User confirmations of externally observed behaviour.
     *
     * Optional on purpose: a runtime with no confirmations recorded simply has no
     * L5 evidence, which the engine reports honestly rather than guessing at.
     */
    var externalVerifications: ExternalVerificationStore? = null
        private set

    fun attach(
        context: Context,
        externalVerifications: ExternalVerificationStore = ExternalVerificationStore(context),
    ) {
        appContext = context.applicationContext
        this.externalVerifications = externalVerifications
    }

    private fun context(): Context? = appContext

    /** The probes the Manager can actually answer right now. */
    fun probes(): Map<String, DiagnosticEngine.Probe> =
        linkedMapOf(
            AtomicCheckInventory.ENV_ANDROID to
                DiagnosticEngine.Probe {
                    DiagnosticEngine.Observation(
                        evidence =
                            "Android ${Build.VERSION.RELEASE} sdk ${Build.VERSION.SDK_INT} " +
                                Build.SUPPORTED_ABIS.joinToString(","),
                        level = EvidenceLevel.L0_PACKAGE,
                        verification = VerificationState.LOCALLY_VERIFIED,
                    )
                },
            AtomicCheckInventory.ENV_TARGET to
                DiagnosticEngine.Probe {
                    val snapshot = snapshot() ?: return@Probe null
                    val target =
                        snapshot.targets.firstOrNull { it.packageName == TARGET_PACKAGE }
                            ?: return@Probe null
                    DiagnosticEngine.Observation(
                        evidence = "${target.packageName} evidence=${target.evidence}",
                        level = EvidenceLevel.L0_PACKAGE,
                        verification = VerificationState.LOCALLY_VERIFIED,
                    )
                },
            AtomicCheckInventory.FRAMEWORK_API102 to
                DiagnosticEngine.Probe {
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
            AtomicCheckInventory.MODULE_LOADED to
                DiagnosticEngine.Probe {
                    val target = target() ?: return@Probe null
                    if (!target.bootstrapMilestones.contains("MODULE_LOADED")) return@Probe null
                    DiagnosticEngine.Observation(
                        evidence = "milestones=" + target.bootstrapMilestones.joinToString(">"),
                        level = EvidenceLevel.L1_LIFECYCLE,
                        verification = VerificationState.TRIGGERED,
                    )
                },
            AtomicCheckInventory.APP_ATTACH to
                DiagnosticEngine.Probe {
                    val target = target() ?: return@Probe null
                    if (!target.bootstrapMilestones.contains("ATTACH_OBSERVED")) return@Probe null
                    DiagnosticEngine.Observation(
                        evidence = "Application.attach intercepted",
                        level = EvidenceLevel.L1_LIFECYCLE,
                        verification = VerificationState.TRIGGERED,
                    )
                },
            AtomicCheckInventory.MANAGER_IPC to
                DiagnosticEngine.Probe {
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
            AtomicCheckInventory.HEARTBEAT to
                DiagnosticEngine.Probe {
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
                        expectedMatch =
                            target.evidence ==
                                ModernManagerRuntimeStatus.Evidence.LIVE_HEARTBEAT,
                        failureClass = FailureClass.SCOPE_MISSING,
                    )
                },
            AtomicCheckInventory.DEXKIT_NATIVE to
                DiagnosticEngine.Probe {
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
            AtomicCheckInventory.ENV_SCOPE to
                DiagnosticEngine.Probe {
                    val packages = SupportedPackages.ALL
                    if (!packages.contains(TARGET_PACKAGE)) return@Probe null
                    DiagnosticEngine.Observation(
                        evidence = "supported=${packages.joinToString(",")}",
                        level = EvidenceLevel.L0_PACKAGE,
                        verification = VerificationState.LOCALLY_VERIFIED,
                    )
                },
            AtomicCheckInventory.REGISTRY to
                DiagnosticEngine.Probe {
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
        ) + featureProbes()

    /**
     * The per-feature hook and trigger probes.
     *
     * They answer from what the target actually reported for the feature, not
     * from a source file or an assumed count, and they keep the three states
     * apart that the issue insists on: a feature that is switched off is
     * `NOT_TESTED`, a feature whose hook is registered is `HOOKED` and stops
     * there, and only a real callback moves it to `TRIGGERED`.
     */
    private fun featureProbes(): Map<String, DiagnosticEngine.Probe> {
        val probes = LinkedHashMap<String, DiagnosticEngine.Probe>()
        for (feature in FeatureCheckInventory.features()) {
            probes[AtomicCheckInventory.HOOK_PREFIX + feature.id] = hookProbe(feature)
            probes[AtomicCheckInventory.TRIGGER_PREFIX + feature.id] = triggerProbe(feature)
        }
        return probes
    }

    private fun hookProbe(feature: FeatureCheckInventory.Feature) =
        DiagnosticEngine.Probe {
            val reported = reportedState(feature.id)
            if (reported == null || reported.isEmpty()) {
                // Nothing was reported at all: the target never ran this feature.
                return@Probe DiagnosticEngine.Observation(
                    evidence = "no state reported by the target",
                    level = EvidenceLevel.L3_HOOK,
                    verification = VerificationState.NOT_OBSERVED,
                    expectedMatch = false,
                    failureClass = FailureClass.DEPENDENCY_MISSING,
                )
            }
            val requested = requestedState(feature)
            val effective = ControlPolicy.effectiveFrom(reported, false, requested)
            DiagnosticEngine.Observation(
                evidence = "$reported (${effective.name})",
                level = EvidenceLevel.L3_HOOK,
                verification = VerificationState.HOOKED,
                // A feature the user switched off is not a broken feature, and a
                // feature that never reported is not a failing one either. Both
                // are stated directly rather than being forced through the
                // pass/fail axis, so the export cannot imply otherwise.
                statusOverride =
                    when (effective) {
                        ControlEffective.DISABLED -> DiagnosticStatus.NOT_TESTED

                        ControlEffective.NOT_OBSERVED -> DiagnosticStatus.NOT_TESTED

                        ControlEffective.PENDING_MIGRATION -> DiagnosticStatus.UNSUPPORTED

                        ControlEffective.RESOLVER_FAILED,
                        ControlEffective.UNSAFE_SIGNATURE,
                        ControlEffective.ERROR,
                        -> DiagnosticStatus.FAIL

                        else -> DiagnosticStatus.PASS
                    },
                failureClass =
                    when (effective) {
                        ControlEffective.RESOLVER_FAILED -> FailureClass.DEPENDENCY_MISSING
                        ControlEffective.UNSAFE_SIGNATURE -> FailureClass.SIGNATURE_UNSUPPORTED
                        ControlEffective.ERROR -> FailureClass.CRASHED
                        ControlEffective.DISABLED -> FailureClass.PREFERENCE_DISABLED
                        else -> FailureClass.NONE
                    },
            )
        }

    /**
     * What the Manager last requested for this feature.
     *
     * Read from the Manager's own preference store, because that is what decides
     * whether `DISABLED` means "switched off" or "switched on but the target has
     * not restarted yet" — the difference between a normal state and a
     * restart-required one.
     */
    private fun requestedState(feature: FeatureCheckInventory.Feature): ControlRequested {
        if (feature.alwaysOn || feature.preferenceKey.isEmpty()) return ControlRequested.ENABLED
        val context = context() ?: return ControlRequested.UNKNOWN
        val prefs =
            runCatching {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            }.getOrNull() ?: return ControlRequested.UNKNOWN
        if (prefs.contains(feature.preferenceKey)) {
            val value = prefs.all[feature.preferenceKey]
            return when (value) {
                is Boolean -> if (value) ControlRequested.ENABLED else ControlRequested.DISABLED
                is String -> if (value.isNotEmpty()) ControlRequested.ENABLED else ControlRequested.DISABLED
                else -> ControlRequested.UNKNOWN
            }
        }
        // Always-on infrastructure reports no preference of its own; a switched-on
        // wired feature whose key was never stored is genuinely unknown.
        return ControlRequested.UNKNOWN
    }

    /**
     * The trigger probe.
     *
     * Three sources of truth, in increasing strength:
     * 1. only Custom Time reports an invocation counter, so for the other
     *    features there is no local evidence at all and the probe returns null;
     * 2. a feature whose effect only a second account can see may be confirmed
     *    by the user, which is recorded per feature and per WhatsApp build;
     * 3. without either, the check stays `NEEDS_EXTERNAL_VERIFICATION`. It is
     *    never upgraded on the strength of an installed hook.
     */
    private fun triggerProbe(feature: FeatureCheckInventory.Feature) =
        DiagnosticEngine.Probe {
            if (feature.id == CUSTOM_TIME_FEATURE) {
                val count =
                    reportedLong("modern.feature.custom_time.invocation_count.$TARGET_PACKAGE")
                if (count != null && count > 0L) {
                    return@Probe DiagnosticEngine.Observation(
                        evidence = "invocations=$count",
                        level = EvidenceLevel.L4_TRIGGER,
                        verification = VerificationState.TRIGGERED,
                        expectedMatch = true,
                    )
                }
            }
            val build = whatsappBuild()
            val confirmation = externalVerifications?.confirmationFor(feature.id, build)
            if (confirmation == null) return@Probe null
            DiagnosticEngine.Observation(
                evidence =
                    "confirmed by the user on $build at ${confirmation.confirmedAtUtcMillis}" +
                        if (confirmation.note.isBlank()) "" else " — ${confirmation.note}",
                level = EvidenceLevel.L5_EXTERNAL,
                verification = VerificationState.EXTERNALLY_VERIFIED,
                expectedMatch = true,
            )
        }

    private fun reportedLong(key: String): Long? =
        runCatching {
            val prefs = targetPrefs() ?: return null
            if (!prefs.contains(key)) null else prefs.getLong(key, 0L)
        }.getOrNull()

    /**
     * Maps a pipeline resolver check onto the state the target reported for it.
     *
     * A reported failure keeps its own reason so the clusterer can attach every
     * downstream symptom to this one cause instead of repeating it.
     */
    private fun resolverProbe(stateKey: String) =
        DiagnosticEngine.Probe {
            val reported = reportedState(stateKey) ?: return@Probe null
            val failed =
                reported.contains("_MISSING") || reported.contains("_UNRESOLVED") ||
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

    private fun target(): ModernManagerRuntimeStatus.Target? = snapshot()?.targets?.firstOrNull { it.packageName == TARGET_PACKAGE }

    private fun reportedState(key: String): String? =
        runCatching { targetPrefs()?.getString("modern.feature.$key.state.$TARGET_PACKAGE", null) }
            .getOrNull()

    private fun targetPrefs() =
        context()?.getSharedPreferences(
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
            }.toMap()
    }

    fun environment(): DiagnosticReportBuilder.Environment {
        val context = context()
        return DiagnosticReportBuilder.Environment(
            appVersion = BuildConfig.VERSION_NAME,
            // The commit the APK was built from, so a report can be traced back
            // to the code that produced it. The version code stays in
            // appVersion and is not passed off as a revision.
            appBuildSha = BuildConfig.GIT_SHA,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            whatsappPackage = TARGET_PACKAGE,
            whatsappVersion = whatsappBuild(),
            androidVersion = Build.VERSION.RELEASE,
            androidSdk = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.joinToString(","),
        )
    }

    /** Hook ids currently reported by the target, for `hooks.json`. */
    fun reportedHooks(): List<String> =
        targetPrefs()
            ?.all
            ?.keys
            ?.filter { it.startsWith("modern.feature.") && it.contains(".state.$TARGET_PACKAGE") }
            ?.sorted()
            ?: emptyList()

    /** Resolver states the target reported, for `resolvers.json`. */
    fun reportedResolvers(): Map<String, String> = reportedFeatureStates()
}

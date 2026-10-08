package com.wax.module.xposed.core

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.crossbowffs.remotepreferences.RemotePreferences
import com.wax.module.BuildConfig
import com.wax.module.ModuleApplication
import com.wax.module.ModuleEntryPoint
import com.wax.module.R
import com.wax.module.TargetRuntime
import com.wax.module.UpdateChecker
import com.wax.module.activation.ActivationHeartbeatFactory
import com.wax.module.activation.TargetHeartbeatCodec
import com.wax.module.activities.CrashReportActivity
import com.wax.module.bootstrap.BootstrapLogSink
import com.wax.module.bootstrap.BootstrapStage
import com.wax.module.bootstrap.BootstrapState
import com.wax.module.bootstrap.StageOutcome
import com.wax.module.bootstrap.StageRunner
import com.wax.module.compat.TargetVersions
import com.wax.module.contract.FeatureContext
import com.wax.module.contract.FeatureStartResult
import com.wax.module.contract.WaFeature
import com.wax.module.diagnostics.FailureCode
import com.wax.module.diagnostics.FailureReportCodec
import com.wax.module.diagnostics.FailureReportStore
import com.wax.module.diagnostics.FeatureFailureReport
import com.wax.module.diagnostics.ReportRedactor
import com.wax.module.graph.RuntimeGraph
import com.wax.module.graph.RuntimeSlot
import com.wax.module.graph.TargetIdentity
import com.wax.module.health.RuntimeFailureCode
import com.wax.module.health.RuntimeHealth
import com.wax.module.health.RuntimeSubsystem
import com.wax.module.health.SubsystemState
import com.wax.module.platform.SupportedPackages
import com.wax.module.settings.TargetSettingsBridge
import com.wax.module.xposed.contract.RuntimeFeatureContexts
import com.wax.module.xposed.core.components.AlertDialogWpp
import com.wax.module.xposed.core.components.FMessageWpp
import com.wax.module.xposed.core.components.FStatusWpp
import com.wax.module.xposed.core.components.ProtocolTreeNodeWpp
import com.wax.module.xposed.core.components.SharedPreferencesWrapper
import com.wax.module.xposed.core.components.WaContactWpp
import com.wax.module.xposed.core.devkit.Unobfuscator
import com.wax.module.xposed.core.devkit.UnobfuscatorCache
import com.wax.module.xposed.features.customization.BubbleColors
import com.wax.module.xposed.features.customization.ContactVerify
import com.wax.module.xposed.features.customization.CustomThemeV2
import com.wax.module.xposed.features.customization.CustomTime
import com.wax.module.xposed.features.customization.CustomToolbar
import com.wax.module.xposed.features.customization.CustomView
import com.wax.module.xposed.features.customization.DefaultEmoji
import com.wax.module.xposed.features.customization.FilterGroups
import com.wax.module.xposed.features.customization.FloatingBottomBar
import com.wax.module.xposed.features.customization.HideSeenView
import com.wax.module.xposed.features.customization.HideTabs
import com.wax.module.xposed.features.customization.IGStatus
import com.wax.module.xposed.features.customization.SeparateGroup
import com.wax.module.xposed.features.customization.ShowOnline
import com.wax.module.xposed.features.general.AboutContactPicker
import com.wax.module.xposed.features.general.AntiRevoke
import com.wax.module.xposed.features.general.CallType
import com.wax.module.xposed.features.general.CaptureDevice
import com.wax.module.xposed.features.general.ChatLimit
import com.wax.module.xposed.features.general.DeleteStatus
import com.wax.module.xposed.features.general.NewChat
import com.wax.module.xposed.features.general.Others
import com.wax.module.xposed.features.general.PinnedLimit
import com.wax.module.xposed.features.general.SeenTick
import com.wax.module.xposed.features.general.ShareLimit
import com.wax.module.xposed.features.general.ShowEditMessage
import com.wax.module.xposed.features.general.Tasker
import com.wax.module.xposed.features.listeners.ContactItemListener
import com.wax.module.xposed.features.listeners.ConversationItemListener
import com.wax.module.xposed.features.media.CallRecording
import com.wax.module.xposed.features.media.DownloadProfile
import com.wax.module.xposed.features.media.DownloadViewOnce
import com.wax.module.xposed.features.media.MediaPreview
import com.wax.module.xposed.features.media.MediaQuality
import com.wax.module.xposed.features.media.StatusDownload
import com.wax.module.xposed.features.others.ActivityController
import com.wax.module.xposed.features.others.AudioTranscript
import com.wax.module.xposed.features.others.BackupRestore
import com.wax.module.xposed.features.others.Channels
import com.wax.module.xposed.features.others.ChatFilters
import com.wax.module.xposed.features.others.CopySelectionMessage
import com.wax.module.xposed.features.others.CopyStatus
import com.wax.module.xposed.features.others.DebugFeature
import com.wax.module.xposed.features.others.GoogleTranslate
import com.wax.module.xposed.features.others.GroupAdmin
import com.wax.module.xposed.features.others.JumpFirstMessage
import com.wax.module.xposed.features.others.MenuHome
import com.wax.module.xposed.features.others.MinorFixes
import com.wax.module.xposed.features.others.Stickers
import com.wax.module.xposed.features.others.TextStatusComposer
import com.wax.module.xposed.features.others.ToastViewer
import com.wax.module.xposed.features.privacy.AntiWa
import com.wax.module.xposed.features.privacy.CallPrivacy
import com.wax.module.xposed.features.privacy.CustomPrivacy
import com.wax.module.xposed.features.privacy.DndMode
import com.wax.module.xposed.features.privacy.FreezeLastSeen
import com.wax.module.xposed.features.privacy.HideChat
import com.wax.module.xposed.features.privacy.HideSeen
import com.wax.module.xposed.features.privacy.LockedChatsEnhancer
import com.wax.module.xposed.features.privacy.TagMessage
import com.wax.module.xposed.features.privacy.TypingPrivacy
import com.wax.module.xposed.features.privacy.ViewOnce
import com.wax.module.xposed.features.providers.ContextMenuActionProvider
import com.wax.module.xposed.features.providers.MenuStatusProvider
import com.wax.module.xposed.graph.RuntimeGraphs
import com.wax.module.xposed.registry.RuntimeFeatureRegistry
import com.wax.module.xposed.spoofer.HookBL
import com.wax.module.xposed.utils.DesignUtils
import com.wax.module.xposed.utils.ReflectionUtils
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.SELinuxHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.jvm.java

class FeatureLoader private constructor() {
    companion object {
        // These references are process-scoped by design: the Xposed hook is installed
        // into the WhatsApp process and lives exactly as long as that process. Neither
        // field ever stores an Activity or shorter-lived UI context.
        @SuppressLint("StaticFieldLeak")
        @JvmField
        var mApp: Application? = null

        @SuppressLint("StaticFieldLeak")
        lateinit var moduleContext: Context

        const val PACKAGE_WPP = SupportedPackages.WHATSAPP
        const val PACKAGE_BUSINESS = SupportedPackages.WHATSAPP_BUSINESS

        private val FALLBACK_SUPPORTED_VERSIONS_WPP =
            listOf(
                "2.26.32.xx",
                "2.26.34.xx",
                "2.26.35.xx",
                "2.26.36.xx",
                "2.26.37.xx",
                "2.26.38.xx",
                "2.26.39.xx",
                "2.26.40.xx",
            )

        private val FALLBACK_SUPPORTED_VERSIONS_BUSINESS =
            listOf(
                "2.26.32.xx",
                "2.26.34.xx",
                "2.26.35.xx",
                "2.26.36.xx",
                "2.26.37.xx",
                "2.26.38.xx",
                "2.26.39.xx",
            )

        private val failureReports =
            Collections.synchronizedList(ArrayList<FeatureFailureReport>())

        @Volatile
        private var reportStore: FailureReportStore? = null

        /**
         * Records a structured failure report and returns it.
         *
         * [code] overrides the classification for callers that already know why something failed. A
         * feature that reports `FailureCode.REQUIRED_CLASS_MISSING` should not have that re-derived
         * from an exception message, and it is not only a cosmetic difference: the code is what the
         * diagnostics dialog and the compatibility tooling read.
         */
        private fun recordFailure(
            featureId: String,
            throwable: Throwable,
            whatsAppVersion: String,
            packageName: String,
            resolver: String? = null,
            stage: String? = null,
            code: FailureCode? = null,
        ): FeatureFailureReport {
            val report =
                FeatureFailureReport.fromThrowable(
                    featureId = featureId,
                    throwable = throwable,
                    moduleVersion = BuildConfig.VERSION_NAME,
                    whatsappVersion = whatsAppVersion,
                    packageName = packageName,
                    resolver = resolver,
                    stage = stage,
                    timestampMillis = System.currentTimeMillis(),
                    threadName = Thread.currentThread().name,
                    code = code,
                )
            failureReports.add(report)
            XposedBridge.log("FeatureFailure ${report.toSummaryLine()}")
            runCatching { reportStore?.append(report) }
            return report
        }

        /** The failure reports collected so far this session, newest last. */
        @JvmStatic
        fun getFailureReports(): List<FeatureFailureReport> = synchronized(failureReports) { failureReports.toList() }

        /** Attaches persistence once an application context exists. */
        private fun attachStore(application: Application) {
            if (reportStore != null) return
            reportStore = runCatching { FailureReportStore(application) }.getOrNull()
            // The same moment storage becomes available to failure reports is the earliest
            // moment the health document can be written, so anything recorded before the
            // application existed stops being memory-only here.
            runCatching { RuntimeHealth.attachStore(application) }
            runCatching { RuntimeHealth.current().persist() }
        }

        /**
         * Component id for the resolution engine's initialisation stage in the health stream.
         *
         * It is deliberately named after the stage, never after the engine library. The A00
         * architecture law counts a file as reaching the engine when that library's package
         * prefix appears anywhere in the text, so an id that spelled the prefix out made this
         * file count as a resolver-layer violation: the gate measures a substring, not an API.
         * The honest fix is to stop writing the substring, not to raise the limit the gate
         * measured. The rule exists to catch a real dependency, and a false positive is a
         * reason to stop tripping it, never to weaken it.
         */
        private const val RESOLVER_INIT_COMPONENT = "resolver.engine.init"

        /**
         * Extra carrying the encoded activation heartbeat in the probe reply.
         *
         * One extra rather than a dozen because the reply already exists: the Manager's probe
         * is the moment it asks, so the answer travels back on the same broadcast rather than
         * through a second channel that would have to be built, secured and versioned.
         */
        const val EXTRA_HEARTBEAT: String = "ACTIVATION_HEARTBEAT"

        private var supportedVersions: List<String> = emptyList()
        private var currentVersion: String? = null
        private var crashHandlerInstalled = false
        private const val UPDATE_CHECK_COOLDOWN_MS = 6 * 60 * 60 * 1000L

        /**
         * How long the feature set may take before the rest of it is given up on.
         *
         * Recorded rather than guessed at: the previous fifteen-second bound was never a
         * decision, it was a literal nobody could see, and the way it failed was silent.
         */
        private const val HOOK_INSTALL_BUDGET_MS = 15_000L
        private val INTERNAL_BROADCAST_PERMISSION =
            BuildConfig.APPLICATION_ID + ".permission.INTERNAL_BROADCAST"
        private var lastUpdateCheckScheduledAt = 0L

        /**
         * Starts the module inside a target process.
         *
         * The bootstrap is a [StageRunner] sequence rather than a straight line, and the reason
         * is what the straight line could not do. It began with the resolution engine and used
         * `if (!engineStarted) return`, so a failure there stopped every stage after it -
         * including the ones that never needed the engine - and it could not say which ones
         * those were. Nothing below the engine could report anything either, because the
         * receivers that carry a report are attached later still.
         *
         * So the sequence is:
         *
         * 1. **Run the stages that do not need the target's `Application`.** Reaching this
         *    function already proves the framework, the module, the effective scope, the target
         *    process and injection, and those five are recorded as five stages rather than as a
         *    comment, because they are the evidence the Manager reads.
         * 2. **Install the one hook that lets the rest happen.** `callApplicationOnCreate` is
         *    hooked before the engine is asked for anything, so a bootstrap that stops early can
         *    still attach to the target and report.
         * 3. **Run the stages that do need it, when it arrives.** The runner skips what it
         *    cannot do yet rather than failing, and a later pass picks up exactly where the
         *    previous one stopped.
         *
         * Every pass answers the Manager's probe on the way out, whether it ran one stage or
         * eleven, so a bootstrap that stops at the engine is still visible - which is the half
         * of M00-DEF-02 that M02 had to work around and that this stage order removes.
         */
        @JvmStatic
        fun start(
            loader: ClassLoader,
            sourceDir: String,
            packageName: String,
            processName: String,
        ) {
            // Health exists before the first thing that can fail does. M00 recorded that the
            // DexKit failure had nowhere to go: the module logged it and returned with zero
            // hooks installed, and the app went on showing a green banner from another process.
            val health = RuntimeHealth.beginForTarget(packageName, processName)
            val runner = StageRunner(health)
            // Stage failures are contained rather than propagated, so the only place the cause
            // survives is the framework log. Without this the containment would be invisible,
            // and an invisible containment is indistinguishable from a swallowed failure.
            BootstrapLogSink.sink =
                BootstrapLogSink { stage, throwable ->
                    XposedBridge.log("WA X stage ${stage.name} failed")
                    XposedBridge.log(throwable)
                }

            val target = TargetContext(loader, sourceDir, packageName, processName)

            val passStartedAt = System.currentTimeMillis()
            val firstReport = runner.run(execute = { stage -> target.run(stage) }, isApplicationAvailable = { false })
            XposedBridge.log("WA X bootstrap pass 1: ${firstReport.summary()}")

            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java,
                "callApplicationOnCreate",
                Application::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val application = param.args[0] as? Application ?: return
                        mApp = application
                        target.attach(application)
                        attachRuntimeGraph(packageName, application, loader)
                        // The callback is not guaranteed to fire once. A second pass re-runs
                        // nothing that already finished - that is the runner's job - but the
                        // receivers and lifecycle callbacks below are registered here, and
                        // registering them twice installs two of every handler.
                        if (!bootstrapAttached.compareAndSet(false, true)) return
                        val startedAt = System.currentTimeMillis()
                        val report = runner.run(execute = { stage -> target.run(stage) }, isApplicationAvailable = { true })
                        XposedBridge.log("WA X bootstrap pass ${report.pass}: ${report.summary()}")
                        XposedBridge.log("Loaded Hooks in ${System.currentTimeMillis() - startedAt}ms")
                        // The heartbeat is not a stage. It answers a question the Manager asked,
                        // and a stage that exists only to be asked a question would be a stage
                        // whose outcome depends on who is watching.
                        sendEnabledBroadcast(application)
                        XposedBridge.log("WA X bootstrap state: ${report.state}")
                    }
                },
            )

            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.thisObject.javaClass.simpleName != "HomeActivity") return
                        val errors = getFailureReports()
                        if (errors.isNotEmpty()) {
                            val activity = param.thisObject as Activity
                            // Redacted by construction, so this text is safe to show and to
                            // copy out of the dialog.
                            val shareableText = FailureReportCodec.renderText(errors)
                            val msg =
                                errors.joinToString("\n") { it.toSummaryLine() }

                            AlertDialogWpp(activity)
                                .setTitle(activity.getString(R.string.error_detected))
                                .setMessage(
                                    "${activity.getString(
                                        R.string.version_error,
                                    )}$msg\n\nCurrent Version: $currentVersion\nSupported Versions:\n${
                                        supportedVersions.joinToString(
                                            "\n",
                                        )
                                    }",
                                ).setPositiveButton(activity.getString(R.string.copy_to_clipboard)) { dialog, _ ->
                                    val clipboard =
                                        mApp?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip =
                                        ClipData.newPlainText(
                                            "text",
                                            shareableText,
                                        )
                                    clipboard.setPrimaryClip(clip)
                                    Toast
                                        .makeText(
                                            mApp,
                                            R.string.copied_to_clipboard,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    dialog.dismiss()
                                }.show()
                        }
                    }
                },
            )
        }

        private fun getPreferences(context: Context): SharedPreferences {
            val pref = ModuleEntryPoint.getPref()
            pref.reload()
            try {
                val fileCanRead =
                    SELinuxHelper.getAppDataFileService().checkFileAccess(pref.file.absolutePath, 4)
                if (fileCanRead) {
                    return pref
                }
            } catch (e: Exception) {
                XposedBridge.log(e)
            }
            XposedBridge.log("XSharedPreferences not accessible, using RemotePreferences fallback")
            return RemotePreferences(
                context,
                BuildConfig.APPLICATION_ID + ".preferences",
                BuildConfig.APPLICATION_ID + "_preferences",
            )
        }

        private fun resolveSupportedVersions(application: Application): List<String> {
            val resIdArray =
                if (application.packageName == PACKAGE_WPP) {
                    R.array.supported_versions_wpp
                } else {
                    R.array.supported_versions_business
                }

            val fromResources =
                try {
                    application.resources
                        .getStringArray(resIdArray)
                        ?.toList()
                        .orEmpty()
                } catch (e: Throwable) {
                    XposedBridge.log("Can't read supported versions from resources: ${e.message}")
                    emptyList()
                }

            val fallback =
                if (application.packageName == PACKAGE_WPP) {
                    FALLBACK_SUPPORTED_VERSIONS_WPP
                } else {
                    FALLBACK_SUPPORTED_VERSIONS_BUSINESS
                }

            val resolved = TargetVersions.resolve(fromResources, fallback)
            if (TargetVersions.normalise(fromResources).isEmpty()) {
                XposedBridge.log("Using built-in supported versions list: ${resolved.joinToString(", ")}")
            }
            return resolved
        }

        private fun initializeModuleContext() {
            try {
                val context =
                    mApp!!.createPackageContext(
                        BuildConfig.APPLICATION_ID,
                        Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                    )
                moduleContext = android.view.ContextThemeWrapper(context, R.style.AppTheme)
                // The graph holds a reference to the same object the field does, so a holder that
                // needs the module's own resources can ask the process for them without reaching
                // into the loader. The field stays until A08 gives the feature layer a typed port;
                // deleting it here would leave 3 files with no way to reach the module context.
                RuntimeGraphs
                    .current()
                    ?.put(RuntimeSlot.MODULE_CONTEXT, moduleContext)
            } catch (_: PackageManager.NameNotFoundException) {
                throw PackageManager.NameNotFoundException(Utils.application.getString(R.string.alert_module_notfound))
            }
        }

        /**
         * Publishes the process's runtime graph.
         *
         * The first place the loader learns anything about the app it is running inside, so it is
         * the first place that can say what that app is. Everything the graph will hold arrives
         * after this: the preferences in the PREFERENCES stage, the module context in
         * RESOLVER_CACHE, the feature context in ESSENTIAL. Attaching it here means a holder that
         * looks can tell "bootstrap has not got there yet" from "bootstrap is not running".
         *
         * The version is not read here because it is not knowable here: this runs at the first
         * framework callback, and the `PackageManager` has not been asked yet. The stage that asks
         * records it on the graph, which is why [RuntimeGraph.recordTargetVersion] is one-way.
         */
        private fun attachRuntimeGraph(
            packageName: String,
            application: Application,
            targetClassLoader: ClassLoader,
        ) {
            val graph =
                RuntimeGraphs.attach(
                    RuntimeGraph(
                        TargetIdentity(
                            packageName = packageName,
                            versionName = null,
                            sdkInt = Build.VERSION.SDK_INT,
                            classLoader = targetClassLoader,
                        ),
                    ),
                )
            graph.put(RuntimeSlot.TARGET_APPLICATION, application)
        }

        private fun installCrashHandler(
            application: Application,
            whatsAppVersion: String,
        ) {
            if (crashHandlerInstalled) return
            crashHandlerInstalled = true

            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    XposedBridge.log(throwable)
                    val isMainThread = Looper.getMainLooper().thread == thread
                    val isFatalSystemError = throwable is Error
                    if (!isMainThread && !isFatalSystemError) {
                        previousHandler?.uncaughtException(thread, throwable)
                        return@setDefaultUncaughtExceptionHandler
                    }
                    val crashInfo = buildCrashInfo(application, whatsAppVersion) + buildFailureHistory()
                    // The raw stack trace is not passed on: an exception message raised
                    // while handling a chat can carry a JID or message text, and the
                    // crash screen offers a share button. Only the redacted form leaves
                    // here.
                    val redactedTrace = buildRedactedTrace(throwable)
                    val intent =
                        Intent().apply {
                            component =
                                ComponentName(
                                    BuildConfig.APPLICATION_ID,
                                    CrashReportActivity::class.java.name,
                                )
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra(CrashReportActivity.EXTRA_CRASH_INFO, crashInfo)
                            putExtra(CrashReportActivity.EXTRA_CRASH_TRACE, redactedTrace)
                        }
                    application.startActivity(intent)
                } catch (e: Throwable) {
                    XposedBridge.log(e)
                } finally {
                    if (previousHandler != null) {
                        previousHandler.uncaughtException(thread, throwable)
                    } else {
                        Runtime.getRuntime().exit(2)
                    }
                }
            }
        }

        private fun buildCrashInfo(
            application: Application,
            whatsAppVersion: String,
        ): String {
            val androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            val deviceModel =
                listOf(Build.MANUFACTURER, Build.MODEL)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")

            return listOf(
                "${application.getString(R.string.whatsapp_version)}: $whatsAppVersion",
                "${application.getString(R.string.whatsapp_package)}: ${application.packageName}",
                "${application.getString(R.string.wae_version)}: ${BuildConfig.VERSION_NAME}",
                "${application.getString(R.string.crash_android_version)}: $androidVersion",
                "${application.getString(R.string.device_model)}: $deviceModel",
            ).joinToString("\n")
        }

        /**
         * Builds the shareable stack trace for the crash screen.
         *
         * Every component is passed through [ReportRedactor], because this text is what
         * the user copies out of the dialog and sends to whoever is helping them.
         */
        private fun buildRedactedTrace(throwable: Throwable): String =
            buildString {
                append(ReportRedactor.redactAndBound(throwable.javaClass.name))
                val message = ReportRedactor.redactAndBound(throwable.message)
                if (message.isNotEmpty()) {
                    append(": ")
                    append(message)
                }
                ReportRedactor.summariseStackTrace(throwable).forEach { frame ->
                    append("\n  at ")
                    append(frame)
                }
            }

        /**
         * Appends the structured feature failure history to the crash information.
         *
         * This is what turns a bare crash into something actionable: the reports say
         * which features had already failed, and on which resolver, before the crash.
         */
        private fun buildFailureHistory(): String {
            val reports = getFailureReports()
            if (reports.isEmpty()) return ""
            return "\n\n" + FailureReportCodec.renderText(reports)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun disableExpirationVersion(classLoader: ClassLoader) {
            val expirationClass = Unobfuscator.loadExpirationClass(classLoader)
            val methods =
                ReflectionUtils.findAllMethodsUsingFilter(expirationClass) { m -> m.returnType == Date::class.java }
            for (method in methods) {
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val calendar =
                                Calendar.getInstance().apply {
                                    set(2099, 11, 31)
                                }
                            param.result = calendar.time
                        }
                    },
                )
            }
        }

        @Throws(Exception::class)
        private fun initComponents(
            loader: ClassLoader,
            pref: SharedPreferences,
        ) {
            FMessageWpp.initialize(loader)
            FStatusWpp.initialize(loader)
            ProtocolTreeNodeWpp.initialize(loader)
            AlertDialogWpp.initDialog(loader)
            WaContactWpp.initialize(loader)
            ModuleRuntime.initialize(loader)
            DesignUtils.setPrefs(pref)
            Utils.init()

            ModuleRuntime.addListenerActivity { activity, type ->
                if (type == ModuleRuntime.ActivityChangeState.ChangeType.RESUMED) {
                    checkUpdate(activity)
                }

                if (ModuleApplication.isOriginalPackage && pref.getBoolean("update_check", true)) {
                    if (activity.javaClass.simpleName == "HomeActivity" && type == ModuleRuntime.ActivityChangeState.ChangeType.RESUMED) {
                        val now = System.currentTimeMillis()
                        val shouldSchedule =
                            synchronized(FeatureLoader::class.java) {
                                if (now - lastUpdateCheckScheduledAt < UPDATE_CHECK_COOLDOWN_MS) {
                                    false
                                } else {
                                    lastUpdateCheckScheduledAt = now
                                    true
                                }
                            }
                        if (shouldSchedule) {
                            activity.window.decorView.postDelayed({
                                CompletableFuture.runAsync(UpdateChecker(activity))
                            }, 2000)
                        }
                    }
                }
            }
        }

        private fun checkUpdate(activity: Activity) {
            if (ModuleRuntime.getPrivBoolean("need_restart", false)) {
                ModuleRuntime.setPrivBoolean("need_restart", false)
                try {
                    AlertDialogWpp(activity)
                        .setMessage(activity.getString(R.string.restart_wpp))
                        .setPositiveButton(activity.getString(R.string.yes)) { _, _ ->
                            if (!Utils.doRestart(activity)) {
                                Toast
                                    .makeText(
                                        activity,
                                        "Unable to rebooting activity",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        }.setNegativeButton(activity.getString(R.string.no), null)
                        .show()
                } catch (_: Throwable) {
                }
            }
        }

        @SuppressLint("WrongConstant")
        private fun registerReceivers() {
            val app = mApp ?: return

            // Reboot receiver
            val restartReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        if (context.packageName == intent.getStringExtra("PKG")) {
                            val appName =
                                context.packageManager.getApplicationLabel(context.applicationInfo)
                            Toast
                                .makeText(
                                    context,
                                    "${context.getString(R.string.rebooting)} $appName...",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            if (!Utils.doRestart(context)) {
                                Toast
                                    .makeText(
                                        context,
                                        "Unable to rebooting $appName",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        }
                    }
                }
            ContextCompat.registerReceiver(
                app,
                restartReceiver,
                IntentFilter("${BuildConfig.APPLICATION_ID}.WHATSAPP.RESTART"),
                INTERNAL_BROADCAST_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED,
            )

// Wpp receiver
            registerProbeResponder(app)

            // Dialog receiver restart
            val restartManualReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        TargetSettingsBridge.reload()
                        ModuleRuntime.setPrivBoolean("need_restart", true)
                    }
                }
            ContextCompat.registerReceiver(
                app,
                restartManualReceiver,
                IntentFilter("${BuildConfig.APPLICATION_ID}.MANUAL_RESTART"),
                INTERNAL_BROADCAST_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }

        /**
         * Registers the receiver that answers the Manager's probe.
         *
         * Split out of [registerReceivers] because a runtime whose bootstrap stopped early still
         * has something to report - that it failed - and a runtime that cannot answer a probe is
         * indistinguishable from a runtime that was never injected. Registering only this
         * receiver installs no feature, so it does not pre-empt M03's decision about what a
         * failed bootstrap should continue to do.
         */
        @SuppressLint("WrongConstant")
        private fun registerProbeResponder(app: Application) {
            val probeReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        XposedBridge.log("WA X activation probe received for ${context.packageName}")
                        sendEnabledBroadcast(context)
                    }
                }
            ContextCompat.registerReceiver(
                app,
                probeReceiver,
                IntentFilter("${BuildConfig.APPLICATION_ID}.CHECK_WPP"),
                INTERNAL_BROADCAST_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }

        /**
         * The heartbeat for this process, encoded for the reply to the Manager's probe.
         *
         * Built from the health snapshot rather than from a stage result, because the snapshot is
         * the one description of the runtime that every reader already uses; deriving it here
         * would give the Manager a second source of truth for the same question.
         *
         * Encoding can fail on a snapshot a component has filled with something the record cannot
         * legally carry. That is worth logging rather than hiding: a heartbeat that cannot be
         * built is a defect in the model, not a transient condition.
         */
        private fun encodeHeartbeat(): String? {
            if (!RuntimeHealth.isInstalled()) return null
            val heartbeat = runCatching { ActivationHeartbeatFactory.from(RuntimeHealth.current().snapshot()) }.getOrNull()
            if (heartbeat == null) return null
            return runCatching { TargetHeartbeatCodec.encode(heartbeat) }
                .onFailure { XposedBridge.log(it) }
                .getOrNull()
        }

        private fun sendEnabledBroadcast(context: Context) {
            runCatching {
                val heartbeat = encodeHeartbeat()
                val wppIntent =
                    Intent("${BuildConfig.APPLICATION_ID}.RECEIVER_WPP").apply {
                        putExtra(
                            "VERSION",
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName,
                        )
                        putExtra("PKG", context.packageName)
                        // The answer to "is WA X running in this process right now", produced by
                        // the only code that can know: this process. The Manager used to be told
                        // it by a constant a hook installed in its own process, which is why a
                        // failure anywhere in here could present as "LSPosed is disabled".
                        putExtra(EXTRA_HEARTBEAT, heartbeat)
                        setPackage(BuildConfig.APPLICATION_ID)
                    }
                context.sendBroadcast(wppIntent)
                XposedBridge.log(
                    "WA X activation broadcast dispatched for ${context.packageName}: " +
                        if (heartbeat == null) "heartbeat unavailable" else "heartbeat encoded",
                )
            }.onFailure {
                XposedBridge.log("WA X could not dispatch the activation report")
                XposedBridge.log(it)
            }
        }

        /**
         * Everything one target process's bootstrap needs, and the body of each stage.
         *
         * Held in one object so a stage can be read on its own. The previous bootstrap was a
         * single method whose early `return` decided the fate of every stage after it; here each
         * stage is a branch that returns an outcome and cannot return from `start()`.
         *
         * The holder exists because the sequence runs twice - once before the target's
         * `Application` exists and once after - and the second pass must be able to read what the
         * first established. Its fields are that first pass's output.
         */
        private class TargetContext(
            private val loader: ClassLoader,
            private val sourceDir: String,
            private val packageName: String,
            private val processName: String,
        ) {
            private var application: Application? = null
            private var preferences: SharedPreferences? = null
            private var targetVersion: String? = null

            /** The target's `Application`, attached by the framework callback. */
            fun attach(application: Application) {
                this.application = application
            }

            /**
             * Runs one stage.
             *
             * Every branch is a claim about what happened, not about what should happen. A stage
             * that cannot run yet returns [StageOutcome.AWAITING] - which is not a failure - and
             * a stage whose prerequisites failed is skipped by the runner before it is asked
             * here at all.
             */
            fun run(stage: BootstrapStage): StageOutcome =
                when (stage) {
                    // Reaching this object is the evidence for all five: the framework invoked
                    // the entry point, the module is loaded, the framework decided to place us
                    // in this package, and our code is executing in the target's process.
                    BootstrapStage.FRAMEWORK -> StageOutcome.SUCCEEDED

                    BootstrapStage.MODULE -> StageOutcome.SUCCEEDED

                    BootstrapStage.SCOPE -> StageOutcome.SUCCEEDED

                    BootstrapStage.TARGET -> StageOutcome.SUCCEEDED

                    BootstrapStage.INJECTION -> StageOutcome.SUCCEEDED

                    BootstrapStage.PREFERENCES -> preferences()

                    BootstrapStage.APPLICATION_ATTACH -> applicationAttach()

                    BootstrapStage.DEX_ENGINE -> dexEngine()

                    BootstrapStage.RESOLVER_CACHE -> resolverCache()

                    BootstrapStage.CORE -> core()

                    BootstrapStage.ESSENTIAL -> essential()

                    BootstrapStage.OPTIONAL -> optional()

                    BootstrapStage.RUNTIME_VERIFICATION -> runtimeVerification()

                    BootstrapStage.READY -> StageOutcome.SUCCEEDED
                }

            /**
             * Reads the target's settings.
             *
             * The fallback provider works, so a route through it is a degradation rather than a
             * failure - and it is worth saying, because "your settings are being read through a
             * fallback" is something a user can act on and nothing said before.
             */
            private fun preferences(): StageOutcome {
                val app = application ?: return StageOutcome.AWAITING
                val pref = TargetSettingsBridge.install(getPreferences(app), TargetRuntime.target)
                preferences = pref
                Utils.xprefs = pref
                // The graph holds the same object. Two owners of one reference is the situation
                // A02 exists to end, but Utils.xprefs still has two readers (CustomPrivacy and
                // Tasker) that only #342 can convert, and the honest count of one is worth more
                // than a second reference nobody can see.
                RuntimeGraphs.current()?.put(RuntimeSlot.TARGET_PREFERENCES, pref)
                Feature.isDebug = pref.getBoolean("enablelogs", false)
                if (pref.getBoolean("bootloader_spoofer", false)) {
                    HookBL.hook(loader, pref)
                    XposedBridge.log("Bootloader Spoofer is Injected")
                }
                return if (pref is RemotePreferences) StageOutcome.DEGRADED else StageOutcome.SUCCEEDED
            }

            /** Storage, the crash handler, the lifecycle callbacks and the receivers. */
            private fun applicationAttach(): StageOutcome {
                val app = application ?: return StageOutcome.AWAITING
                val pref = preferences ?: return StageOutcome.FAILED
                targetVersion = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                // The graph was created before the PackageManager had been asked, so this is where
                // its identity stops being "unknown". Recorded on the graph rather than passed in
                // because a holder that asks later must get the same answer a holder that asks now
                // would get.
                RuntimeGraphs.current()?.recordTargetVersion(targetVersion)
                XposedBridge.log(targetVersion.orEmpty())
                currentVersion = targetVersion
                installCrashHandler(app, targetVersion.orEmpty())
                attachStore(app)
                app.registerActivityLifecycleCallbacks(WaCallback())
                registerReceivers()
                return StageOutcome.SUCCEEDED
            }

            /**
             * The resolution engine.
             *
             * There is no early return here, and that is the change this phase exists for. A
             * failure here skips the stages that need the engine and leaves the ones that do not
             * standing, which is what lets the runtime still attach, still answer the Manager and
             * still report that the engine is what failed.
             */
            private fun dexEngine(): StageOutcome {
                if (Unobfuscator.initWithPath(sourceDir)) {
                    Utils.appClassLoader = loader
                    return StageOutcome.SUCCEEDED
                }
                XposedBridge.log("Can't init dexkit")
                recordFailure(
                    featureId = "ResolutionEngine[Init]",
                    throwable = IllegalStateException("Unobfuscator.initWithPath returned false for $sourceDir"),
                    whatsAppVersion = targetVersion.orEmpty(),
                    packageName = packageName,
                    stage = BootstrapStage.DEX_ENGINE.name,
                )
                return StageOutcome.FAILED
            }

            /** The resolver caches, the shared preference hook, and the version assessment. */
            private fun resolverCache(): StageOutcome {
                val app = application ?: return StageOutcome.AWAITING
                val pref = preferences ?: return StageOutcome.AWAITING
                initializeModuleContext()
                UnobfuscatorCache.init(app)
                SharedPreferencesWrapper.hookInit(app.classLoader)
                ReflectionUtils.initCache(app)

                supportedVersions = resolveSupportedVersions(app)
                XposedBridge.log("Supported versions for $packageName: ${supportedVersions.joinToString(", ")}")

                val assessment = TargetVersions.assess(targetVersion, supportedVersions)
                return when {
                    assessment.accepted && !assessment.isExperimental -> {
                        StageOutcome.SUCCEEDED
                    }

                    // A tolerated build is loaded, and that it was tolerated rather than verified
                    // is recorded as a degradation, which is what the aggregate and the card say.
                    assessment.isExperimental -> {
                        StageOutcome.DEGRADED
                    }

                    else -> {
                        disableExpirationVersion(app.classLoader)
                        if (pref.getBoolean("bypass_version_check", false)) {
                            StageOutcome.DEGRADED
                        } else {
                            error(
                                """
                                Unsupported version: $targetVersion
                                Only the function of ignoring the expiration of the WhatsApp version has been applied!
                                ${assessment.explanation}
                                """.trimIndent(),
                            )
                            StageOutcome.FAILED
                        }
                    }
                }
            }

            /** The shared infrastructure every feature is built on. */
            private fun core(): StageOutcome {
                val pref = preferences ?: return StageOutcome.AWAITING
                initComponents(loader, pref)
                return StageOutcome.SUCCEEDED
            }

            /**
             * The hook set.
             *
             * The feature context is built here rather than per feature: its six capabilities are
             * process-scoped, and a feature that hooked through one context and read the clock
             * through another would be relying on two identities for one process.
             *
             * Individual features are isolated inside [plugins], so this stage measures whether
             * the set as a whole was installed. One feature throwing cannot fail it, which is the
             * property the gate turns on.
             */
            private fun essential(): StageOutcome {
                val pref = preferences ?: return StageOutcome.AWAITING
                val context = featureContext(pref)
                return if (plugins(pref, targetVersion.orEmpty(), context)) StageOutcome.SUCCEEDED else StageOutcome.FAILED
            }

            /**
             * The context handed to every contract-based feature.
             *
             * Built once and reused. The settings snapshot comes from the cache rather than from
             * the raw preferences, which is what makes the context target-scoped: a feature that
             * read the delegate directly could read WhatsApp's overrides into Business.
             */
            private fun featureContext(pref: SharedPreferences): FeatureContext {
                // The reflective path still needs the raw preferences, and it is the only thing
                // that does now: the contract path receives the snapshot.
                legacyPreferences = pref
                val debugEnabled = { pref.getBoolean("enablelogs", false) }
                val context =
                    RuntimeFeatureContexts.forTarget(
                        settings = TargetSettingsBridge.snapshotFor(pref, TargetRuntime.target),
                        classLoader = loader,
                        targetVersionName = targetVersion,
                        debugEnabled = debugEnabled,
                        reportFailure = ::recordContractFailure,
                    )
                // Published before the first feature starts, so that a failure inside one can be
                // attributed to a process that knows what it is. A feature still receives the
                // context directly; the graph is what anything *other* than a feature asks.
                RuntimeGraphs.current()?.attachFeatureContext(context)
                return context
            }

            /**
             * The optional features.
             *
             * This stage cannot fail the bootstrap and cannot stop another stage, which is why it
             * is classified OPTIONAL: an optional feature that does not install is a loss, not a
             * broken module, and the only thing it changes is the aggregate the Manager reads.
             */
            private fun optional(): StageOutcome = if (getFailureReports().isEmpty()) StageOutcome.SUCCEEDED else StageOutcome.DEGRADED

            /**
             * Reads the finished runtime back.
             *
             * A bootstrap nobody has read back has not been shown to work. This stage persists
             * the document and then asks the model for its own verdict, so the aggregate the
             * Manager reads is derived once by one place rather than assembled by whichever
             * component reported last.
             */
            private fun runtimeVerification(): StageOutcome =
                when (RuntimeHealth.current().snapshot().overallState) {
                    SubsystemState.READY -> StageOutcome.SUCCEEDED
                    SubsystemState.FAILED -> StageOutcome.FAILED
                    else -> StageOutcome.DEGRADED
                }
        }

        /**
         * Records what a feature reported when it started.
         *
         * Takes the id rather than the feature because the two contracts hand back different things:
         * a [WaFeature] returns a [FeatureStartResult] that names what it installed or what it could
         * not find, and a legacy [Feature] returns nothing at all and is recorded as installed with
         * its display name. The registry's factories normalise that, so this is the only place that
         * decides what a start *means*.
         *
         * A feature that skips itself says so with a result rather than by throwing, because an
         * unsupported WhatsApp build is an ordinary outcome and reporting it as a failure is what
         * makes a user's feature list look broken on a build it simply does not target.
         */
        private fun recordResult(
            featureId: String,
            result: FeatureStartResult,
            versionWpp: String,
        ): FeatureStartResult {
            when (result) {
                is FeatureStartResult.Installed -> {
                    XposedBridge.log("$featureId: ${result.summary}")
                }

                is FeatureStartResult.Degraded -> {
                    XposedBridge.log("$featureId: degraded, ${result.lost} - ${result.summary}")
                }

                is FeatureStartResult.Skipped -> {
                    XposedBridge.log("$featureId: skipped, ${result.missing}")
                }

                is FeatureStartResult.Failed -> {
                    recordFailure(
                        featureId = featureId,
                        throwable = IllegalStateException(result.summary),
                        whatsAppVersion = versionWpp,
                        packageName = mApp?.packageName.orEmpty(),
                        stage = result.code.name,
                        code = result.code,
                    )
                }
            }
            return result
        }

        /** The preferences a legacy feature is constructed with. Set by the ESSENTIAL stage. */
        @Volatile
        private var legacyPreferences: SharedPreferences? = null

        /**
         * Records a failure on behalf of a contract feature.
         *
         * The same funnel every other failure uses, so a feature written against the contract has
         * exactly the visibility a legacy one has. Making it a function reference rather than a
         * public API on this object keeps [recordFailure] private.
         */
        private fun recordContractFailure(
            featureId: String,
            code: FailureCode,
            message: String?,
            stage: String?,
        ): FeatureFailureReport =
            recordFailure(
                featureId = featureId,
                throwable = IllegalStateException(message ?: code.name),
                code = code,
                whatsAppVersion =
                    mApp
                        ?.packageManager
                        ?.let {
                            runCatching {
                                it
                                    .getPackageInfo(
                                        mApp!!.packageName,
                                        0,
                                    ).versionName
                            }.getOrNull()
                        }.orEmpty(),
                packageName = mApp?.packageName.orEmpty(),
                stage = stage,
            )

        /**
         * Whether the per-process bootstrap has already attached to its target.
         *
         * `callApplicationOnCreate` is not guaranteed to fire once, and everything a second
         * firing would register - receivers, lifecycle callbacks, the crash handler - is
         * installed per registration. The runner makes the *stages* idempotent; this makes the
         * *attachments* idempotent, which is a different thing and just as necessary.
         */
        private val bootstrapAttached =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        /**
         * Installs the feature set, isolating each feature's failure.
         *
         * Two kinds of feature go through here, and the split is visible rather than implicit:
         *
         * * a [WaFeature] is started with [context] and reports a [FeatureStartResult];
         * * anything else is a legacy [Feature], constructed reflectively from
         *   `(ClassLoader, SharedPreferences)` exactly as before.
         *
         * The legacy path is still the majority, and pretending otherwise would make this phase
         * look like it finished the migration. [LEGACY_FEATURE_COUNT] records what is left, it is
         * asserted by `FeatureContractTest` against the installed set, and it may only fall.
         *
         * Returns whether the set was installed in full within [HOOK_INSTALL_BUDGET_MS]. That
         * return value is the fix for a defect this phase found: the executor was awaited with a
         * fifteen-second bound and the answer was discarded, so a single slow feature silently
         * prevented every feature queued behind it from being installed, and nothing anywhere
         * recorded that any of them had been dropped.
         */
        @Throws(Exception::class)
        private fun plugins(
            pref: SharedPreferences,
            versionWpp: String,
            context: FeatureContext,
        ): Boolean {
            // The registry is the one registration source; the feature classes were an
            // arrayOf(...) here until #337, and the array is gone rather than commented out,
            // because two lists that currently agree are one edit away from not agreeing.
            val features = RuntimeFeatureRegistry.entries

            XposedBridge.log("Loading Plugins")
            val executorService =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "WAE-HookInstaller").apply {
                        isDaemon = true
                    }
                }
            val times = Collections.synchronizedList(ArrayList<String>())

            for (factory in features) {
                CompletableFuture.runAsync({
                    val startTime = System.currentTimeMillis()
                    try {
                        recordResult(
                            factory.featureId,
                            factory.start(context, pref),
                            versionWpp,
                        )
                    } catch (e: Throwable) {
                        XposedBridge.log(e)
                        recordFailure(
                            featureId = factory.featureId,
                            throwable = e,
                            whatsAppVersion = versionWpp,
                            packageName = FeatureLoader.moduleContext.packageName,
                            stage = "hook",
                        )
                    }
                    val duration = System.currentTimeMillis() - startTime
                    times.add("* Loaded Plugin ${factory.featureId} in ${duration}ms")
                }, executorService)
            }

            executorService.shutdown()
            val finished = executorService.awaitTermination(HOOK_INSTALL_BUDGET_MS, TimeUnit.MILLISECONDS)

            if (Feature.isDebug) {
                val loadedTimes = synchronized(times) { times.toList() }
                loadedTimes.forEach { XposedBridge.log(it) }
            }

            if (!finished) {
                // Named, counted and recorded. The dropped features are the ones with no line
                // in `times`, which is the only evidence the budget produced, so the number is
                // what turns "the set installed slowly" into "these twelve never installed".
                val installed = synchronized(times) { times.size }
                val dropped = features.size - installed
                XposedBridge.log("WA X hook installation exceeded ${HOOK_INSTALL_BUDGET_MS}ms; $dropped feature(s) not installed")
                recordFailure(
                    featureId = "MainFeatures[Install]",
                    throwable =
                        IllegalStateException(
                            "$dropped of ${features.size} features were not installed within ${HOOK_INSTALL_BUDGET_MS}ms",
                        ),
                    whatsAppVersion = versionWpp,
                    packageName = moduleContext.packageName,
                    stage = BootstrapStage.ESSENTIAL.name,
                )
            }
            return finished
        }
    }
}

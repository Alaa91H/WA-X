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
import com.wax.module.ModuleApplication
import com.wax.module.BuildConfig
import com.wax.module.R
import com.wax.module.UpdateChecker
import com.wax.module.ModuleEntryPoint
import com.wax.module.activities.CrashReportActivity
import com.wax.module.compat.TargetVersions
import com.wax.module.diagnostics.FailureReportCodec
import com.wax.module.diagnostics.FailureReportStore
import com.wax.module.diagnostics.FeatureFailureReport
import com.wax.module.diagnostics.ReportRedactor
import com.wax.module.platform.SupportedPackages
import com.wax.module.platform.TargetPackageRegistry
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
import com.wax.module.xposed.features.providers.MenuStatusProvider
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

class FeatureLoader {

    companion object {
        @JvmField
        var mApp: Application? = null

        lateinit var moduleContext: Context

        const val PACKAGE_WPP = SupportedPackages.WHATSAPP
        const val PACKAGE_BUSINESS = SupportedPackages.WHATSAPP_BUSINESS

        private val FALLBACK_SUPPORTED_VERSIONS_WPP = listOf(
            "2.26.32.xx",
            "2.26.34.xx",
            "2.26.35.xx",
            "2.26.36.xx",
            "2.26.37.xx",
            "2.26.38.xx",
            "2.26.39.xx",
            "2.26.40.xx"
        )

        private val FALLBACK_SUPPORTED_VERSIONS_BUSINESS = listOf(
            "2.26.32.xx",
            "2.26.34.xx",
            "2.26.35.xx",
            "2.26.36.xx",
            "2.26.37.xx",
            "2.26.38.xx",
            "2.26.39.xx"
        )

        private val failureReports =
            Collections.synchronizedList(ArrayList<FeatureFailureReport>())

        @Volatile
        private var reportStore: FailureReportStore? = null

        /** Records a structured failure report and returns it. */
        private fun recordFailure(
            featureId: String,
            throwable: Throwable,
            whatsAppVersion: String,
            packageName: String,
            resolver: String? = null,
            stage: String? = null
        ): FeatureFailureReport {
            val report = FeatureFailureReport.fromThrowable(
                featureId = featureId,
                throwable = throwable,
                moduleVersion = BuildConfig.VERSION_NAME,
                whatsappVersion = whatsAppVersion,
                packageName = packageName,
                resolver = resolver,
                stage = stage,
                timestampMillis = System.currentTimeMillis(),
                threadName = Thread.currentThread().name
            )
            failureReports.add(report)
            XposedBridge.log("FeatureFailure ${report.toSummaryLine()}")
            runCatching { reportStore?.append(report) }
            return report
        }

        /** The failure reports collected so far this session, newest last. */
        @JvmStatic
        fun getFailureReports(): List<FeatureFailureReport> =
            synchronized(failureReports) { failureReports.toList() }

        /** Attaches persistence once an application context exists. */
        private fun attachStore(application: Application) {
            if (reportStore != null) return
            reportStore = runCatching { FailureReportStore(application) }.getOrNull()
        }
        private var supportedVersions: List<String> = emptyList()
        private var currentVersion: String? = null
        private var crashHandlerInstalled = false
        private const val UPDATE_CHECK_COOLDOWN_MS = 6 * 60 * 60 * 1000L
        private val INTERNAL_BROADCAST_PERMISSION =
            BuildConfig.APPLICATION_ID + ".permission.INTERNAL_BROADCAST"
        private var lastUpdateCheckScheduledAt = 0L

        @JvmStatic
        fun start(loader: ClassLoader, sourceDir: String) {
            if (!Unobfuscator.initWithPath(sourceDir)) {
                XposedBridge.log("Can't init dexkit")
                return
            }

            Utils.appClassLoader = loader

            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java, "callApplicationOnCreate", Application::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        mApp = param.args[0] as Application
                        val application = mApp!!
                        val pref = getPreferences(application)
                        Feature.DEBUG = pref.getBoolean("enablelogs", false)
                        Utils.xprefs = pref

                        if (pref.getBoolean("bootloader_spoofer", false)) {
                            HookBL.hook(loader, pref)
                            XposedBridge.log("Bootloader Spoofer is Injected")
                        }

                        val packageManager = application.packageManager
                        val packageInfo = packageManager.getPackageInfo(application.packageName, 0)
                        XposedBridge.log(packageInfo.versionName)
                        currentVersion = packageInfo.versionName
                        installCrashHandler(application, packageInfo.versionName.orEmpty())
                        attachStore(application)

                        supportedVersions =
                            resolveSupportedVersions(application)
                        XposedBridge.log(
                            "Supported versions for ${application.packageName}: " +
                                    supportedVersions.joinToString(", ")
                        )
                        application.registerActivityLifecycleCallbacks(WaCallback())
                        registerReceivers()

                        try {
                            initializeModuleContext()
                            val timeMillis = System.currentTimeMillis()
                            UnobfuscatorCache.init(application)
                            SharedPreferencesWrapper.hookInit(application.classLoader)
                            ReflectionUtils.initCache(application)

                            val isSupported = TargetVersions.isSupported(
                                packageInfo.versionName,
                                supportedVersions
                            )

                            if (!isSupported) {
                                disableExpirationVersion(application.classLoader)
                                if (!pref.getBoolean("bypass_version_check", false)) {
                                    val errorMsg = """
                                        Unsupported version: ${packageInfo.versionName}
                                        Only the function of ignoring the expiration of the WhatsApp version has been applied!
                                    """.trimIndent()
                                    throw Exception(errorMsg)
                                }
                            }

                            initComponents(loader, pref)
                            plugins(loader, pref, packageInfo.versionName!!)
                            sendEnabledBroadcast(application)

                            val totalTime = System.currentTimeMillis() - timeMillis
                            XposedBridge.log("Loaded Hooks in ${totalTime}ms")

                        } catch (e: Throwable) {
                            XposedBridge.log(e)
                            recordFailure(
                                featureId = "MainFeatures[Critical]",
                                throwable = e,
                                whatsAppVersion = packageInfo.versionName.orEmpty(),
                                packageName = application.packageName,
                                stage = "startup"
                            )
                        }
                    }
                })

            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onCreate", Bundle::class.java,
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
                                    "${activity.getString(R.string.version_error)}$msg\n\nCurrent Version: $currentVersion\nSupported Versions:\n${
                                        supportedVersions.joinToString(
                                            "\n"
                                        )
                                    }"
                                )
                                .setPositiveButton(activity.getString(R.string.copy_to_clipboard)) { dialog, _ ->
                                    val clipboard =
                                        mApp?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText(
                                        "text",
                                        shareableText
                                    )
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(
                                        mApp,
                                        R.string.copied_to_clipboard,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    dialog.dismiss()
                                }
                                .show()
                        }
                    }
                })
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
                BuildConfig.APPLICATION_ID + "_preferences"
            )
        }

        private fun resolveSupportedVersions(application: Application): List<String> {
            val resIdArray = if (application.packageName == PACKAGE_WPP)
                R.array.supported_versions_wpp
            else
                R.array.supported_versions_business

            val fromResources = try {
                application.resources.getStringArray(resIdArray)
                    ?.toList()
                    .orEmpty()
            } catch (e: Throwable) {
                XposedBridge.log("Can't read supported versions from resources: ${e.message}")
                emptyList()
            }

            val fallback = if (application.packageName == PACKAGE_WPP)
                FALLBACK_SUPPORTED_VERSIONS_WPP
            else
                FALLBACK_SUPPORTED_VERSIONS_BUSINESS

            val resolved = TargetVersions.resolve(fromResources, fallback)
            if (TargetVersions.normalise(fromResources).isEmpty()) {
                XposedBridge.log("Using built-in supported versions list: ${resolved.joinToString(", ")}")
            }
            return resolved
        }

        private fun initializeModuleContext() {
            try {
                val context = mApp!!.createPackageContext(
                    BuildConfig.APPLICATION_ID,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                )
                moduleContext = android.view.ContextThemeWrapper(context, R.style.AppTheme)
            } catch (_: PackageManager.NameNotFoundException) {
                throw PackageManager.NameNotFoundException(Utils.application.getString(R.string.alert_module_notfound))
            }
        }

        private fun installCrashHandler(application: Application, whatsAppVersion: String) {
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
                    val intent = Intent().apply {
                        component = ComponentName(
                            BuildConfig.APPLICATION_ID,
                            CrashReportActivity::class.java.name
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

        private fun buildCrashInfo(application: Application, whatsAppVersion: String): String {
            val androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            val deviceModel = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")

            return listOf(
                "${application.getString(R.string.whatsapp_version)}: $whatsAppVersion",
                "${application.getString(R.string.whatsapp_package)}: ${application.packageName}",
                "${application.getString(R.string.wae_version)}: ${BuildConfig.VERSION_NAME}",
                "${application.getString(R.string.crash_android_version)}: $androidVersion",
                "${application.getString(R.string.device_model)}: $deviceModel"
            ).joinToString("\n")
        }

        /**
         * Builds the shareable stack trace for the crash screen.
         *
         * Every component is passed through [ReportRedactor], because this text is what
         * the user copies out of the dialog and sends to whoever is helping them.
         */
        private fun buildRedactedTrace(throwable: Throwable): String = buildString {
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
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val calendar = Calendar.getInstance().apply {
                            set(2099, 11, 31)
                        }
                        param.result = calendar.time
                    }
                })
            }
        }

        @Throws(Exception::class)
        private fun initComponents(loader: ClassLoader, pref: SharedPreferences) {
            FMessageWpp.initialize(loader)
            FStatusWpp.initialize(loader)
            ProtocolTreeNodeWpp.initialize(loader)
            AlertDialogWpp.initDialog(loader)
            WaContactWpp.initialize(loader)
            ModuleRuntime.initialize(loader, pref)
            DesignUtils.setPrefs(pref)
            Utils.init()

            ModuleRuntime.addListenerActivity { activity, type ->
                if (type == ModuleRuntime.ActivityChangeState.ChangeType.RESUMED) {
                    checkUpdate(activity)
                }


                if (ModuleApplication.isOriginalPackage && pref.getBoolean("update_check", true)) {
                    if (activity.javaClass.simpleName == "HomeActivity" && type == ModuleRuntime.ActivityChangeState.ChangeType.RESUMED) {
                        val now = System.currentTimeMillis()
                        val shouldSchedule = synchronized(FeatureLoader::class.java) {
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
                                Toast.makeText(
                                    activity,
                                    "Unable to rebooting activity",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .setNegativeButton(activity.getString(R.string.no), null)
                        .show()
                } catch (_: Throwable) {
                }
            }
        }

        @SuppressLint("WrongConstant")
        private fun registerReceivers() {
            val app = mApp ?: return

            // Reboot receiver
            val restartReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (context.packageName == intent.getStringExtra("PKG")) {
                        val appName =
                            context.packageManager.getApplicationLabel(context.applicationInfo)
                        Toast.makeText(
                            context,
                            "${context.getString(R.string.rebooting)} $appName...",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (!Utils.doRestart(context)) {
                            Toast.makeText(
                                context,
                                "Unable to rebooting $appName",
                                Toast.LENGTH_SHORT
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
                ContextCompat.RECEIVER_EXPORTED
            )

            // Wpp receiver
            val wppReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    sendEnabledBroadcast(context)
                }
            }
            ContextCompat.registerReceiver(
                app,
                wppReceiver,
                IntentFilter("${BuildConfig.APPLICATION_ID}.CHECK_WPP"),
                INTERNAL_BROADCAST_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED
            )

            // Dialog receiver restart
            val restartManualReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    ModuleRuntime.setPrivBoolean("need_restart", true)
                }
            }
            ContextCompat.registerReceiver(
                app,
                restartManualReceiver,
                IntentFilter("${BuildConfig.APPLICATION_ID}.MANUAL_RESTART"),
                INTERNAL_BROADCAST_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED
            )
        }

        private fun sendEnabledBroadcast(context: Context) {
            try {
                val wppIntent = Intent("${BuildConfig.APPLICATION_ID}.RECEIVER_WPP").apply {
                    putExtra(
                        "VERSION",
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    )
                    putExtra("PKG", context.packageName)
                    setPackage(BuildConfig.APPLICATION_ID)
                }
                context.sendBroadcast(wppIntent)
            } catch (_: Exception) {
            }
        }

        @Throws(Exception::class)
        private fun plugins(loader: ClassLoader, pref: SharedPreferences, versionWpp: String) {
            val classes = arrayOf(
                DebugFeature::class.java,
                MinorFixes::class.java,
                ContactItemListener::class.java,
                ConversationItemListener::class.java,
                MenuStatusProvider::class.java,
                ShowEditMessage::class.java,
                AntiRevoke::class.java,
                CustomToolbar::class.java,
                CustomView::class.java,
                SeenTick::class.java,
                BubbleColors::class.java,
                CallPrivacy::class.java,
                ActivityController::class.java,
                CustomThemeV2::class.java,
                FloatingBottomBar::class.java,
                ChatLimit::class.java,
                SeparateGroup::class.java,
                ShowOnline::class.java,
                DndMode::class.java,
                FreezeLastSeen::class.java,
                TypingPrivacy::class.java,
                HideChat::class.java,
                HideSeen::class.java,
                HideSeenView::class.java,
                TagMessage::class.java,
                HideTabs::class.java,
                IGStatus::class.java,
                MediaQuality::class.java,
                NewChat::class.java,
                Others::class.java,
                PinnedLimit::class.java,
                CustomTime::class.java,
                ShareLimit::class.java,
                StatusDownload::class.java,
                ViewOnce::class.java,
                CallType::class.java,
                MediaPreview::class.java,
                FilterGroups::class.java,
                Tasker::class.java,
                DeleteStatus::class.java,
                DownloadViewOnce::class.java,
                Channels::class.java,
                DownloadProfile::class.java,
                ChatFilters::class.java,
                GroupAdmin::class.java,
                Stickers::class.java,
                CopyStatus::class.java,
                CopySelectionMessage::class.java,
                TextStatusComposer::class.java,
                ToastViewer::class.java,
                MenuHome::class.java,
                AntiWa::class.java,
                CustomPrivacy::class.java,
                AudioTranscript::class.java,
                GoogleTranslate::class.java,
                ContactVerify::class.java,
                LockedChatsEnhancer::class.java,
                CallRecording::class.java,
                BackupRestore::class.java,
                JumpFirstMessage::class.java,
                AboutContactPicker::class.java,
                DefaultEmoji::class.java,
                CaptureDevice::class.java,
                ContextMenuActionProvider::class.java
            )

            XposedBridge.log("Loading Plugins")
            val executorService = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "WAE-HookInstaller").apply {
                    isDaemon = true
                }
            }
            val times = Collections.synchronizedList(ArrayList<String>())

            for (clazz in classes) {
                CompletableFuture.runAsync({
                    val startTime = System.currentTimeMillis()
                    try {
                        val constructor = clazz.getConstructor(
                            ClassLoader::class.java,
                            SharedPreferences::class.java
                        )
                        val plugin = constructor.newInstance(loader, pref) as Feature
                        plugin.doHook()
                    } catch (e: Throwable) {
                        XposedBridge.log(e)
                        recordFailure(
                            featureId = clazz.simpleName,
                            throwable = e,
                            whatsAppVersion = versionWpp,
                            packageName = FeatureLoader.moduleContext.packageName,
                            stage = "hook"
                        )
                    }
                    val duration = System.currentTimeMillis() - startTime
                    times.add("* Loaded Plugin ${clazz.simpleName} in ${duration}ms")
                }, executorService)
            }

            executorService.shutdown()
            executorService.awaitTermination(15, TimeUnit.SECONDS)

            if (Feature.DEBUG) {
                val loadedTimes = synchronized(times) { times.toList() }
                loadedTimes.forEach { XposedBridge.log(it) }
            }
        }
    }
}

package com.wax.module.modern

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * API 102 port of the legacy ActivityController contact-picker relay.
 *
 * The WA X Manager asks the target for contacts by starting the target's
 * notification-settings Activity with `contact_mode`. This feature opens the
 * target's own About screen picker, and for exactly that window the app-lock
 * auth check is bypassed (the legacy `disableAuth` flag), reset on activity
 * end and on any activity result.
 *
 * Targets are resolved from repo-derived evidence only: the locked-auth
 * method from the `"privacy_fingerprint_enabled"` / `"app_lock_auth_needed"`
 * anchors the legacy resolver uses, and the notification-settings Activity
 * from the proven `SettingsNotifications` name suffix set. No obfuscated
 * name and no hardcoded numeric resource is used.
 *
 * Always-on infrastructure: the Manager decides when to ask, so there is no
 * user toggle and no in-WhatsApp control.
 */
object ModernActivityControllerFeature {
    const val FEATURE_ID = "activity_controller"
    const val ANCHOR_LOCKED_AUTH = "privacy_fingerprint_enabled"
    const val ANCHOR_AUTH_NEEDED = "app_lock_auth_needed"
    const val EXTRA_CONTACT_MODE = "contact_mode"
    const val EXTRA_PICKER_MODE = "picker_mode"
    const val EXTRA_KEY = "key"
    const val EXTRA_CONTACTS = "contacts"
    const val EXTRA_PICKER_CONTACTS = "picker_contacts"
    const val SETTINGS_NOTIFICATIONS_SUFFIX = "SettingsNotifications"
    const val REQUEST_CONTACT_PICKER: Int = 0xff2515
    private const val TAG = "WA-X ActivityCtrl102"

    enum class Outcome {
        INSTALLED,
        ALREADY_INSTALLED,
        APPLICATION_UNAVAILABLE,
        AUTH_RESOLVER_MISSING,
        AUTH_RESOLVER_AMBIGUOUS,
        SETTINGS_ACTIVITY_UNRESOLVED,
        ERROR,
    }

    private val disableAuth = AtomicBoolean(false)
    private val pickerKey = AtomicReference<String?>(null)
    private var lifecycleRegistered = false

    /** True only while a WA X contact picker round trip is open. */
    @JvmStatic
    fun isAuthBypassed(): Boolean = disableAuth.get()

    @JvmStatic
    fun isSettingsNotificationsActivity(activityName: String?): Boolean =
        !activityName.isNullOrEmpty() && activityName.endsWith(SETTINGS_NOTIFICATIONS_SUFFIX)

    /**
     * Which result extras the relay must add.
     *
     * Pure on purpose: the picker round trip can only be exercised inside
     * WhatsApp, but the rule "never overwrite what the picker already set" is
     * the part that can silently corrupt a result, so it is decided here and
     * unit-tested without any Android object.
     */
    @JvmStatic
    fun extrasToFill(existing: Set<String>, key: String?): List<String> {
        val missing = ArrayList<String>(3)
        if (EXTRA_KEY !in existing && key != null) missing.add(EXTRA_KEY)
        if (EXTRA_CONTACTS !in existing) missing.add(EXTRA_CONTACTS)
        if (EXTRA_PICKER_CONTACTS !in existing) missing.add(EXTRA_PICKER_CONTACTS)
        return missing
    }

    /** Applies [extrasToFill] to the real result Intent. */
    @JvmStatic
    fun applyPickerResult(resultIntent: Intent, key: String?) {
        val existing = HashSet<String>()
        for (name in listOf(EXTRA_KEY, EXTRA_CONTACTS, EXTRA_PICKER_CONTACTS)) {
            if (resultIntent.hasExtra(name)) existing.add(name)
        }
        for (name in extrasToFill(existing, key)) {
            when (name) {
                EXTRA_KEY -> resultIntent.putExtra(EXTRA_KEY, key)
                EXTRA_CONTACTS -> resultIntent.putStringArrayListExtra(EXTRA_CONTACTS, arrayListOf())
                else -> resultIntent.putExtra(EXTRA_PICKER_CONTACTS, arrayListOf<Any?>())
            }
        }
    }

    @JvmStatic
    fun buildPickerIntent(targetPackage: String, aboutActivity: String, key: String?): Intent =
        Intent().apply {
            setClassName(targetPackage, aboutActivity)
            putExtra(EXTRA_PICKER_MODE, true)
            putExtra(EXTRA_KEY, key ?: "")
            putStringArrayListExtra(EXTRA_CONTACTS, arrayListOf())
        }

    @JvmStatic
    fun install(
        target: android.content.Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
    ): Outcome {
        val application = target.applicationContext as? Application
            ?: return Outcome.APPLICATION_UNAVAILABLE
        val classLoader = target.classLoader
        val targetPackage = target.packageName

        val authMethod = try {
            DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
                dex.findMethod {
                    matcher {
                        addUsingString(ANCHOR_LOCKED_AUTH, StringMatchType.Contains)
                        addUsingString(ANCHOR_AUTH_NEEDED, StringMatchType.Contains)
                    }
                }
            }
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "App-lock auth resolver unavailable", failure)
            return Outcome.AUTH_RESOLVER_MISSING
        }
        when {
            authMethod.isEmpty() -> return Outcome.AUTH_RESOLVER_MISSING
            authMethod.size != 1 -> return Outcome.AUTH_RESOLVER_AMBIGUOUS
        }
        val authCheck = try {
            authMethod[0].getMethodInstance(classLoader)
        } catch (resolveFailure: Throwable) {
            Log.w(TAG, "App-lock auth method unresolvable", resolveFailure)
            return Outcome.AUTH_RESOLVER_MISSING
        }
        val settingsActivity = resolveSettingsNotifications(target, targetPackage)
            ?: return Outcome.SETTINGS_ACTIVITY_UNRESOLVED
        val aboutActivity = resolveAboutActivity(target, targetPackage)
            ?: return Outcome.SETTINGS_ACTIVITY_UNRESOLVED

        synchronized(this) {
            if (!lifecycleRegistered) {
                application.registerActivityLifecycleCallbacks(LifecycleTracker())
                lifecycleRegistered = true
            }
        }

        val onCreate = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
        val onActivityResult = Activity::class.java.getDeclaredMethod(
            "onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java,
        )
        val installedCount = try {
            hooks.installFeature(FEATURE_ID, listOf(
                ModernHookRegistry.Registration("activity.app_lock_auth") {
                    val handle = ModernHookBridge(framework).intercept(
                        authCheck, "wax.modern.activity_controller.app_lock_auth") { chain ->
                        if (disableAuth.get()) {
                            // Legacy semantics: force the boolean auth check false.
                            false
                        } else {
                            chain.proceed()
                        }
                    }
                    ModernHookRegistry.Handle { handle.unhook() }
                },
                ModernHookRegistry.Registration("activity.on_create") {
                    val handle = ModernHookBridge(framework).intercept(
                        onCreate, "wax.modern.activity_controller.on_create") { chain ->
                        val result = chain.proceed()
                        val activity = chain.thisObject as? Activity
                        if (activity != null && settingsActivity.isAssignableFrom(activity.javaClass)
                            && activity.intent.getBooleanExtra(EXTRA_CONTACT_MODE, false)
                        ) {
                            openPicker(activity, targetPackage, aboutActivity.name)
                        }
                        result
                    }
                    ModernHookRegistry.Handle { handle.unhook() }
                },
                ModernHookRegistry.Registration("activity.on_result") {
                    val handle = ModernHookBridge(framework).intercept(
                        onActivityResult, "wax.modern.activity_controller.on_activity_result") { chain ->
                        disableAuth.set(false)
                        val result = chain.proceed()
                        val activity = chain.thisObject as? Activity
                        if (activity != null && settingsActivity.isAssignableFrom(activity.javaClass)) {
                            val requestCode = chain.args.firstOrNull() as? Int
                            val data = chain.args.getOrNull(2) as? Intent
                            if (requestCode == REQUEST_CONTACT_PICKER && data != null) {
                                applyPickerResult(data, pickerKey.get())
                                activity.setResult(Activity.RESULT_OK, data)
                            }
                            activity.finish()
                        }
                        result
                    }
                    ModernHookRegistry.Handle { handle.unhook() }
                },
            ))
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Activity controller unavailable", failure)
            return Outcome.ERROR
        }
        return if (installedCount > 0) Outcome.INSTALLED else Outcome.ALREADY_INSTALLED
    }

    private fun openPicker(activity: Activity, targetPackage: String, aboutActivity: String) {
        val key = activity.intent.getStringExtra(EXTRA_KEY)
        pickerKey.set(key)
        disableAuth.set(true)
        val contacts = activity.intent.getStringArrayListExtra(EXTRA_CONTACTS)
        val intent = Intent(buildPickerIntent(targetPackage, aboutActivity, key)).apply {
            if (contacts != null) putStringArrayListExtra(EXTRA_CONTACTS, ArrayList(contacts))
        }
        try {
            activity.startActivityForResult(intent, REQUEST_CONTACT_PICKER)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Contact picker could not start", failure)
            disableAuth.set(false)
        }
    }

    /** Declared-Activity lookup first, then the proven name suffix. */
    private fun resolveSettingsNotifications(
        target: android.content.Context,
        targetPackage: String,
    ): Class<*>? = resolveDeclaredActivity(target, targetPackage) { name ->
        name.endsWith(SETTINGS_NOTIFICATIONS_SUFFIX)
    }

    private fun resolveAboutActivity(
        target: android.content.Context,
        targetPackage: String,
    ): Class<*>? = resolveDeclaredActivity(target, targetPackage) { name ->
        name.endsWith(".settings.About") || name.endsWith(".settings.ui.About") ||
            name.endsWith(".About")
    }

    private inline fun resolveDeclaredActivity(
        target: android.content.Context,
        targetPackage: String,
        matches: (String) -> Boolean,
    ): Class<*>? {
        try {
            val info = target.packageManager.getPackageInfo(
                targetPackage, PackageManager.GET_ACTIVITIES)
            info.activities?.forEach { activityInfo ->
                val name = activityInfo.name
                if (matches(name)) {
                    return try {
                        Class.forName(name, false, target.classLoader)
                    } catch (ignored: Throwable) {
                        null
                    }
                }
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "Declared activities unreadable", failure)
        }
        return null
    }

    /**
     * Closes the app-lock bypass window. The legacy code reset the flag when
     * the notification-settings Activity ended; this closes it whenever any
     * Activity is destroyed while the window is open, because the window only
     * exists during a picker round trip and a stale bypass must never outlive
     * the flow that opened it.
     */
    private fun ModernActivityControllerFeature.releaseAuthWindow() {
        disableAuth.set(false)
        pickerKey.set(null)
    }

    private class LifecycleTracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {}

        override fun onActivityDestroyed(activity: Activity) {
            ModernActivityControllerFeature.releaseAuthWindow()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

        override fun onActivityStarted(activity: Activity) {}

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivityStopped(activity: Activity) {}

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }
}
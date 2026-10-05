package com.wax.module.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.wax.module.platform.SupportedPackages
import com.wax.module.platform.TargetPackageRegistry
import java.util.ArrayList

object WhatsAppContactPickerLauncher {
    const val EXTRA_PICKER_MODE = "picker_mode"
    const val EXTRA_CONTACT_MODE = "contact_mode"

    private val whatsappPackages = TargetPackageRegistry.packageNames.toList()
    private val aboutActivityCandidates =
        listOf(
            "com.whatsapp.settings.About",
            "com.whatsapp.settings.ui.About",
        )
    private val settingsNotificationsCandidates =
        listOf(
            "com.whatsapp.SettingsNotifications",
            "com.whatsapp.settings.SettingsNotifications",
            "com.whatsapp.settings.ui.SettingsNotifications",
        )

    /**
     * Activity-name suffixes accepted as the About screen when no declared candidate
     * resolves.
     *
     * Order is irrelevant because this is a membership test, but the set itself is part
     * of the contract: WhatsApp has moved this class between releases, so the fallbacks
     * are deliberately broader than the preferred candidates above.
     */
    private val aboutActivitySuffixes = listOf(".settings.About", ".settings.ui.About", ".About")

    /** Suffix accepted as the notification-settings screen when no candidate resolves. */
    private const val SETTINGS_NOTIFICATIONS_SUFFIX = "SettingsNotifications"

    /** Returns true when [activityName] looks like the About screen. */
    @JvmStatic
    fun isAboutActivity(activityName: String?): Boolean {
        if (activityName.isNullOrEmpty()) return false
        return aboutActivitySuffixes.any { activityName.endsWith(it) }
    }

    /** Returns true when [activityName] looks like the notification-settings screen. */
    @JvmStatic
    fun isSettingsNotificationsActivity(activityName: String?): Boolean {
        if (activityName.isNullOrEmpty()) return false
        return activityName.endsWith(SETTINGS_NOTIFICATIONS_SUFFIX)
    }

    @JvmStatic
    fun getInstalledWhatsAppPackages(context: Context): ArrayList<String> {
        val installedPackages = arrayListOf<String>()
        val packageManager = context.packageManager
        for (packageName in whatsappPackages) {
            try {
                packageManager.getPackageInfo(packageName, 0)
                installedPackages.add(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return installedPackages
    }

    @JvmStatic
    fun getPackageLabel(packageName: String): CharSequence =
        if (packageName == SupportedPackages.WHATSAPP_BUSINESS) "WhatsApp Business" else "WhatsApp"

    @JvmStatic
    @Throws(Exception::class)
    fun createPickerIntent(
        context: Context,
        packageName: String,
        key: String,
        selectedJids: ArrayList<String>?,
    ): Intent =
        Intent().apply {
            setClassName(packageName, resolveSettingsNotificationsClassName(context, packageName))
            putExtra(EXTRA_CONTACT_MODE, true)
            putExtra("key", key)
            putStringArrayListExtra("contacts", selectedJids?.let(::ArrayList) ?: arrayListOf())
        }

    @JvmStatic
    @Throws(Exception::class)
    fun createAboutPickerIntent(
        context: Context,
        packageName: String,
        key: String,
        selectedJids: ArrayList<String>?,
    ): Intent =
        Intent().apply {
            setClassName(packageName, resolveAboutActivityClassName(context, packageName))
            putExtra(EXTRA_PICKER_MODE, true)
            putExtra("key", key)
            putStringArrayListExtra("contacts", selectedJids?.let(::ArrayList) ?: arrayListOf())
        }

    @Throws(Exception::class)
    private fun resolveAboutActivityClassName(
        context: Context,
        packageName: String,
    ): String {
        val packageManager = context.packageManager
        for (candidate in aboutActivityCandidates) {
            try {
                packageManager.getActivityInfo(ComponentName(packageName, candidate), 0)
                return candidate
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }

        val packageInfo: PackageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
        packageInfo.activities?.forEach { activityInfo ->
            val name = activityInfo.name
            if (isAboutActivity(name)) return name
        }
        throw Exception("Class About not found")
    }

    @Throws(Exception::class)
    private fun resolveSettingsNotificationsClassName(
        context: Context,
        packageName: String,
    ): String {
        val packageManager = context.packageManager
        for (candidate in settingsNotificationsCandidates) {
            try {
                packageManager.getActivityInfo(ComponentName(packageName, candidate), 0)
                return candidate
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }

        val packageInfo: PackageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
        packageInfo.activities?.forEach { activityInfo ->
            val name = activityInfo.name
            if (isSettingsNotificationsActivity(name)) return name
        }
        throw Exception("Class SettingsNotifications not found")
    }
}

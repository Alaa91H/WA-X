package com.wax.module

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wax.module.activities.CrashReportActivity
import com.wax.module.xposed.utils.Utils
import rikka.material.app.LocaleDelegate.Companion.defaultLocale
import java.io.File

class ModuleApplication : Application() {
    @SuppressLint("ApplySharedPref")
    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        var sharedPreferences: SharedPreferences? = null

        try {
            sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            val mode = sharedPreferences.getString("thememode", "0")!!.toInt()
            setThemeMode(mode)
            changeLanguage(this)
        } catch (e: Exception) {
            Utils.showToast("[PREFS] Error accessing app data: ${e.message}")
        }
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val intent = Intent(this, CrashReportActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                intent.putExtra(CrashReportActivity.EXTRA_CRASH_INFO, buildCrashInfo())
                intent.putExtra(
                    CrashReportActivity.EXTRA_CRASH_TRACE,
                    Log.getStackTraceString(throwable),
                )
                startActivity(intent)
            } catch (_: Throwable) {
            } finally {
                if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, throwable)
                } else {
                    Runtime.getRuntime().exit(2)
                }
            }
        }
    }

    private fun buildCrashInfo(): String {
        val androidVersion = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
        val deviceModel = (Build.MANUFACTURER + " " + Build.MODEL).trim()
        return "WAE version: " + BuildConfig.VERSION_NAME + "\n" +
            "WAE package: " + packageName + "\n" +
            getString(R.string.crash_android_version) + ": " + androidVersion + "\n" +
            getString(R.string.device_model) + ": " + deviceModel
    }

    fun restartApp(packageWpp: String) {
        val intent =
            Intent(BuildConfig.APPLICATION_ID + ".WHATSAPP.RESTART").apply {
                setPackage(packageWpp)
                putExtra("PKG", packageWpp)
            }
        sendBroadcast(intent)
    }

    fun isXposedEnabled(): Boolean = System.currentTimeMillis() == 0L

    companion object {
        lateinit var instance: ModuleApplication

        @JvmStatic
        fun showRequestStoragePermission(activity: Activity) {
            val builder = MaterialAlertDialogBuilder(activity)
            builder.setTitle(R.string.storage_permission)
            builder.setMessage(R.string.permission_storage)
            builder.setPositiveButton(R.string.allow) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.data = Uri.fromParts("package", activity.packageName, null)
                    activity.startActivity(intent)
                } else {
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                        ),
                        0,
                    )
                }
            }
            builder.setNegativeButton(R.string.deny) { dialog, _ -> dialog.dismiss() }
            builder.show()
        }

        @JvmStatic
        fun setThemeMode(mode: Int) {
            when (mode) {
                0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }

        /**
         * Applies the chosen interface language.
         *
         * With no choice stored this is the system language, which is also the default
         * state: nothing is written until the user picks something, so following the
         * system keeps following it when the system language changes.
         */
        @JvmStatic
        fun changeLanguage(context: Context) {
            val locale = AppLanguage.localeFor(context)
            defaultLocale = locale
            val res = context.resources
            val config = AppLanguage.configurationFor(context, locale)
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
        }

        @JvmStatic
        val moduleFolder: File
            get() {
                val download =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val folder = File(download, "WA X")
                if (!folder.exists()) folder.mkdirs()
                return folder
            }

        @Suppress("SimplifyBooleanWithConstants", "KotlinConstantConditions")
        @JvmStatic
        val isOriginalPackage: Boolean
            get() = BuildConfig.APPLICATION_ID == "com.wax.module"
    }
}

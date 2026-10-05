package com.wax.module.xposed.features.others

import android.app.Activity
import android.content.Intent
import android.view.Menu
import android.widget.Toast
import com.wax.module.R
import com.wax.module.xposed.core.Feature
import com.wax.module.xposed.core.components.AlertDialogWpp
import com.wax.module.xposed.core.devkit.Unobfuscator.findFirstClassUsingName
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import android.content.SharedPreferences 
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.util.Locale

class BackupRestore(loader: ClassLoader, preferences:SharedPreferences) :
    Feature(loader, preferences) {

    override fun getPluginName(): String {
        return "BackupRestore"
    }

    companion object {
        /** Menu item id used to detect and tag the injected entry. */
        const val MENU_ITEM_ID: Int = 10001

        /** Intent action understood by WhatsApp's restore-from-backup activity. */
        const val ACTION_RESTORE_ONE_TIME_SETUP: String = "action_show_restore_one_time_setup"

        private val SIMPLE_NAME_TOKENS = listOf("drive", "google")

        /**
         * Whether [simpleName] identifies the Google Drive backup activity.
         *
         * The activity's simple class name has moved between releases, so it is matched
         * on containing both tokens rather than on an exact name.
         */
        @JvmStatic
        fun isGoogleDriveActivity(simpleName: String?): Boolean {
            if (simpleName.isNullOrEmpty()) return false
            val lower = simpleName.lowercase(Locale.ROOT)
            return SIMPLE_NAME_TOKENS.all { lower.contains(it) }
        }
    }

    override fun doHook() {
        if (!prefs.getBoolean("force_restore_backup_feature", false)) return

        val restoreFromBackupClass = findFirstClassUsingName(
            classLoader,
            StringMatchType.EndsWith,
            "RestoreFromBackupActivity"
        )

        XposedBridge.hookAllMethods(
            Activity::class.java,
            "onPrepareOptionsMenu",
            object : XC_MethodHook() {

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isGoogleDriveActivity(param.thisObject.javaClass.simpleName)) return
                    val menu = param.args[0] as Menu
                    if (menu.findItem(MENU_ITEM_ID) != null) return
                    val menuItem = menu.add(0, MENU_ITEM_ID, 0, R.string.force_restore_backup_experimental)
                    val activity = param.thisObject as Activity
                    menuItem.setOnMenuItemClickListener {
                        AlertDialogWpp(activity)
                            .setTitle(R.string.force_restore_backup)
                            .setMessage(activity.getString(R.string.warning_restore))
                            .setPositiveButton(
                                activity.getString(R.string.yes)
                            ) { _,_ ->
                                try {
                                    val intent = Intent(activity, restoreFromBackupClass)
                                    intent.action = ACTION_RESTORE_ONE_TIME_SETUP
                                    activity.startActivityForResult(intent, MENU_ITEM_ID)
                                } catch (e: Exception) {
                                    XposedBridge.log(e)
                                    Utils.showToast(
                                        "Error launching restore activity: " + e.message,
                                        Toast.LENGTH_LONG
                                    )
                                }
                            }
                            .setNegativeButton(activity.getString(R.string.no), null)
                            .show()
                        true
                    }
                }
            })
    }
}

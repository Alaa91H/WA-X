package com.wax.module.xposed.features.general

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import com.wax.module.R
import com.wax.module.xposed.core.Feature
import com.wax.module.xposed.core.components.AlertDialogWpp
import com.wax.module.xposed.core.components.FMessageWpp.UserJid
import com.wax.module.xposed.core.components.SharedPreferencesWrapper
import com.wax.module.xposed.core.devkit.UnobfuscatorCache
import com.wax.module.xposed.utils.ReflectionUtils
import de.robv.android.xposed.XC_MethodHook
import android.content.SharedPreferences
import com.wax.module.xposed.core.components.WaContactWpp
import com.wax.module.xposed.core.devkit.Unobfuscator
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class CallType(loader: ClassLoader, preferences:SharedPreferences) :
    Feature(loader, preferences) {
    override fun doHook() {
        if (!prefs.getBoolean("calltype", false)) return

        SharedPreferencesWrapper.addHook { key, value ->
            if (key == "call_confirmation_dialog_count") {
                99
            }else {
                value
            }
        }

        val startCallMethod = Unobfuscator.loadStartOutgoingCallMethod(classLoader)

        XposedBridge.hookMethod(startCallMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = param.args[0] as? Context ?: return
                val contactObj = param.args[1] ?: return
                val isVideo = param.args[3] as? Boolean ?: false
                if (isVideo) return

                val waContact = WaContactWpp(contactObj)
                val userJid = waContact.userJid
                val phoneNumber = userJid.phoneNumber
                if (phoneNumber.isNullOrEmpty()) return
                val originalArgs = param.args.copyOf()
                param.result = null
                val mAlertDialog = AlertDialogWpp(context)
                mAlertDialog.setTitle(UnobfuscatorCache.getInstance().getString("selectcalltype"))
                mAlertDialog.setItems(
                    arrayOf(
                        context.getString(R.string.phone_call),
                        context.getString(R.string.whatsapp_call)
                    )
                ) { dialog: DialogInterface?, which: Int ->
                    dialog?.dismiss()
                    when (which) {
                        0 -> {
                            val intent = Intent()
                            intent.action = Intent.ACTION_DIAL
                            intent.data = ("tel:+" + userJid.phoneNumber).toUri()
                            context.startActivity(intent)
                        }

                        1 -> {
                            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, originalArgs)
                        }
                    }
                }
                mAlertDialog.show()
            }
        })
    }

    override fun getPluginName(): String {
        return "Call Type"
    }
}

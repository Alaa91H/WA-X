package com.wax.module.xposed.features.general

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.wax.module.xposed.core.Feature
import com.wax.module.xposed.core.ModuleRuntime
import com.wax.module.xposed.core.components.FMessageWpp
import com.wax.module.xposed.core.devkit.Unobfuscator
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

class Tasker(loader: ClassLoader, preferences: SharedPreferences) : Feature(loader, preferences) {

    override fun getPluginName(): String = "Tasker"

    @Throws(Throwable::class)
    override fun doHook() {
        if (!prefs.getBoolean(PREF_TASKER_ENABLED, false)) return

        val authToken = prefs.getString(PREF_TASKER_AUTH_TOKEN, "").orEmpty()
        if (authToken.isBlank()) {
            log("Tasker integration disabled: authentication token is empty")
            return
        }

        hookReceiveMessage(authToken)
        registerSenderMessage(authToken)
    }

    private fun registerSenderMessage(authToken: String) {
        val filter = IntentFilter(ACTION_MESSAGE_SENT)
        ContextCompat.registerReceiver(
            Utils.application,
            SenderMessageBroadcastReceiver(authToken),
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    @Throws(Throwable::class)
    private fun hookReceiveMessage(authToken: String) {
        val method = Unobfuscator.loadReceiptMethod(classLoader)

        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[4] == "sender" || param.args[1] == null || param.args[3] == null) return
                val fMsg = FMessageWpp.Key(param.args[3]).fMessage ?: return
                val userJid = fMsg.key.remoteJid
                val number = userJid.phoneNumber ?: return
                val msg = fMsg.messageStr ?: return
                if (TextUtils.isEmpty(msg) || userJid.isStatus) return

                Utils.databaseExecutor.execute {
                    val name = ModuleRuntime.getContactName(userJid)
                    Handler(Utils.application.mainLooper).post {
                        val intent = Intent(ACTION_MESSAGE_RECEIVED).apply {
                            setPackage(TASKER_PACKAGE)
                            putExtra(EXTRA_AUTH_TOKEN, authToken)
                            putExtra("number", number)
                            putExtra("name", name)
                            putExtra("message", msg)
                        }
                        Utils.application.sendBroadcast(intent)
                    }
                }
            }
        })
    }

    class SenderMessageBroadcastReceiver(
        private val authToken: String
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra(EXTRA_AUTH_TOKEN) != authToken) {
                XposedBridge.log("WA X Tasker: rejected unauthorized MESSAGE_SENT broadcast")
                return
            }

            var number = intent.getStringExtra("number")
            if (number == null) {
                number = intent.getLongExtra("number", 0).toString()
                number = if (number == "0") null else number
            }
            val message = intent.getStringExtra("message")
            if (number == null || message == null) return

            number = number.replace("\\D".toRegex(), "")
            if (number.isBlank() || message.isBlank()) return
            ModuleRuntime.sendMessage(number, message)
        }
    }

    companion object {
        private const val PREF_TASKER_ENABLED = "tasker"
        private const val PREF_TASKER_AUTH_TOKEN = "tasker_auth_token"
        private const val EXTRA_AUTH_TOKEN = "token"
        private const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"

        private const val ACTION_MESSAGE_SENT = "com.wax.module.MESSAGE_SENT"
        private const val ACTION_MESSAGE_RECEIVED = "com.wax.module.MESSAGE_RECEIVED"
        private const val ACTION_EVENT = "com.wax.module.EVENT"

        @JvmStatic
        fun sendTaskerEvent(name: String?, number: String?, event: String) {
            if (!Utils.xprefs.getBoolean(PREF_TASKER_ENABLED, false)) return
            val authToken = Utils.xprefs.getString(PREF_TASKER_AUTH_TOKEN, "").orEmpty()
            if (authToken.isBlank()) return

            val intent = Intent(ACTION_EVENT).apply {
                setPackage(TASKER_PACKAGE)
                putExtra(EXTRA_AUTH_TOKEN, authToken)
                putExtra("name", name)
                putExtra("number", number)
                putExtra("event", event)
            }
            Utils.application.sendBroadcast(intent)
        }
    }
}

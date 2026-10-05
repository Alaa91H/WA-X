package com.wax.module.xposed.features.general

import android.content.ContentValues
import android.content.SharedPreferences
import android.os.Bundle
import com.wax.module.xposed.core.Feature
import com.wax.module.xposed.core.ModuleRuntime.homeActivityClass
import com.wax.module.xposed.core.db.MessageStore.Companion.getInstance
import com.wax.module.xposed.core.devkit.Unobfuscator.loadChatLimitDelete2Method
import com.wax.module.xposed.core.devkit.Unobfuscator.loadChatLimitDeleteMethod
import com.wax.module.xposed.core.devkit.Unobfuscator.loadEphemeralInsertdb
import com.wax.module.xposed.core.devkit.Unobfuscator.loadFmessageTimestampField
import com.wax.module.xposed.core.devkit.Unobfuscator.loadSeeMoreConstructor
import com.wax.module.xposed.utils.ReflectionUtils
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class ChatLimit(
    loader: ClassLoader,
    preferences: SharedPreferences,
) : Feature(loader, preferences) {
    override fun doHook() {
        val antiDisappearing = prefs.getBoolean("antidisappearing", false)
        val revokeallmessages = prefs.getBoolean("revokeallmessages", false)

        val chatLimitDeleteMethod = loadChatLimitDeleteMethod(classLoader)
        val chatLimitDelete2Method = loadChatLimitDelete2Method(classLoader)
        val fmessageTimestampMethod = loadFmessageTimestampField(classLoader)

        val epUpdateMethod = loadEphemeralInsertdb(classLoader)

        XposedHelpers.findAndHookMethod(
            homeActivityClass,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    if (antiDisappearing) {
                        Utils.databaseExecutor.execute {
                            getInstance().executeWritableSQL("UPDATE message_ephemeral SET expire_timestamp = 2553512370000")
                        }
                    }
                }
            },
        )

        XposedBridge.hookMethod(
            epUpdateMethod,
            object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (antiDisappearing) {
                        val contentValues = param.result as ContentValues
                        contentValues.put("expire_timestamp", 2553512370000L)
                    }
                }
            },
        )

        if (revokeallmessages) {
            XposedBridge.hookMethod(
                chatLimitDelete2Method,
                object : XC_MethodHook() {
                    private var unhooked: Unhook? = null

                    @Throws(Throwable::class)
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val list =
                            ReflectionUtils.findInstancesOfType(
                                param.args,
                                MutableSet::class.java,
                            )
                        if (list.isEmpty()) return
                        val listMessages = list[0]!!.second
                        var isExpired = false
                        for (fmessageObj in listMessages) {
                            val timestamp = fmessageTimestampMethod.getLong(fmessageObj)
                            // verify message is expired (max: 3 days)
                            if (System.currentTimeMillis() - timestamp > 3 * 24 * 60 * 60 * 1000) {
                                isExpired = true
                                break
                            }
                        }
                        if (!isExpired) {
                            unhooked =
                                XposedBridge.hookMethod(
                                    chatLimitDeleteMethod,
                                    object : XC_MethodHook() {
                                        override fun afterHookedMethod(param: MethodHookParam) {
                                            if (ReflectionUtils.isCalledFromMethod(chatLimitDelete2Method)) {
                                                param.setResult(0L)
                                            }
                                        }
                                    },
                                )
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam?) {
                        if (unhooked != null) {
                            unhooked!!.unhook()
                        }
                    }
                },
            )
        }

        val seeMoreMethod = loadSeeMoreConstructor(classLoader)

        XposedBridge.hookMethod(
            seeMoreMethod,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!prefs.getBoolean("removeseemore", false)) return
                    param.args[1] = Int.MAX_VALUE
                }
            },
        )
    }

    override fun getPluginName(): String = "Chat Limit"
}

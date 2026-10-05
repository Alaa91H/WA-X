package com.wax.module.xposed.features.privacy

import com.wax.module.xposed.core.Feature
import com.wax.module.xposed.core.components.FMessageWpp.UserJid
import com.wax.module.xposed.core.components.WaContactWpp
import com.wax.module.xposed.core.devkit.Unobfuscator.loadChatCacheClass
import com.wax.module.xposed.core.devkit.Unobfuscator.loadLoadedContactsMethod
import com.wax.module.xposed.core.devkit.Unobfuscator.loadLockedChatsMethod
import com.wax.module.xposed.core.devkit.Unobfuscator.loadNotificationMethod
import com.wax.module.xposed.utils.ReflectionUtils
import de.robv.android.xposed.XC_MethodHook
import android.content.SharedPreferences 
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field

class LockedChatsEnhancer(classLoader: ClassLoader, preferences:SharedPreferences) :
    Feature(classLoader, preferences) {
    private var chatCache: Any? = null

    override fun doHook() {
        if (!prefs.getBoolean("lockedchats_enhancer", false)) return

        val jidNotifications = loadNotificationMethod(classLoader)
        val lockedChatsMethod = loadLockedChatsMethod(classLoader)
        val suppressLockedChats = ThreadLocal.withInitial { false }

        XposedBridge.hookMethod(jidNotifications, object : XC_MethodHook() {

            override fun beforeHookedMethod(param: MethodHookParam) {
                suppressLockedChats.set(true)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                suppressLockedChats.remove()
            }
        })

        XposedBridge.hookMethod(lockedChatsMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (suppressLockedChats.get() == true) {
                    param.setResult(ArrayList<Any?>())
                }
            }
        })

        val chatCacheClass = loadChatCacheClass(classLoader)
        val lockedChatsFields = ReflectionUtils.findAllFieldsUsingFilter(chatCacheClass) {
            f -> f.type == HashSet::class.java
        }

        XposedBridge.hookAllConstructors(chatCacheClass, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                chatCache = param.thisObject
            }
        })

        val loadedContacts = loadLoadedContactsMethod(classLoader)

        XposedBridge.hookMethod(loadedContacts, object : XC_MethodHook() {

            override fun beforeHookedMethod(param: MethodHookParam) {
                val holder = param.args.firstOrNull() ?: return
                val list = XposedHelpers.getObjectField(holder, "A01") as? List<*> ?: return
                val cache = chatCache ?: return
                val lockedChatsField = lockedChatsFields.getOrNull(1) ?: return
                val lockedChats = lockedChatsField.get(cache) as? HashSet<*> ?: return
                val lockedNumbers = lockedChats.mapNotNull { userJid ->
                    UserJid(userJid).phoneNumber
                }.toHashSet()

                val filteredList = list.filterNot { item ->
                    if (!WaContactWpp.TYPE.isInstance(item)) return@filterNot false
                    val phoneNumber = WaContactWpp(item).userJid.phoneNumber
                    phoneNumber != null && phoneNumber in lockedNumbers
                }
                XposedHelpers.setObjectField(holder, "A01", filteredList)
            }
        })
    }

    public override fun getPluginName(): String {
        return "Locked Chats Enhancer"
    }
}

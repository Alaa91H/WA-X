package com.wax.module.xposed.features.privacy

import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.wax.module.xposed.core.Feature
import com.wax.module.xposed.core.components.FMessageWpp
import com.wax.module.xposed.core.devkit.Unobfuscator.getMethodDescriptor
import com.wax.module.xposed.core.devkit.Unobfuscator.loadForwardClassMethod
import com.wax.module.xposed.core.devkit.Unobfuscator.loadForwardTagMethod
import com.wax.module.xposed.features.listeners.ConversationItemListener
import com.wax.module.xposed.features.listeners.ConversationItemListener.OnConversationItemListener
import com.wax.module.xposed.utils.DesignUtils
import com.wax.module.xposed.utils.ReflectionUtils
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

class TagMessage(
    loader: ClassLoader,
    preferences: SharedPreferences,
) : Feature(loader, preferences) {
    override fun doHook() {
        val method = loadForwardTagMethod(classLoader)
        logDebug(getMethodDescriptor(method))
        val forwardClass = loadForwardClassMethod(classLoader)
        logDebug("ForwardClass: " + forwardClass.name)

        XposedBridge.hookMethod(
            method,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!prefs.getBoolean("hidetag", false)) return
                    val arg = param.args[0] as Long
                    if (arg == 1L) {
                        if (ReflectionUtils.isCalledFromClass(forwardClass)) {
                            param.args[0] = 0
                        }
                    }
                }
            },
        )

        if (prefs.getBoolean("broadcast_tag", false)) {
            hookBroadcastView()
        }
    }

    private fun hookBroadcastView() {
        ConversationItemListener.conversationListeners.add(
            object : OnConversationItemListener() {
                override fun onItemBind(
                    fMessage: FMessageWpp,
                    view: ViewGroup,
                    position: Int,
                    convertView: View?,
                ) {
                    if (fMessage.key.isFromMe) return
                    val dateTextView = view.findViewById<TextView>(Utils.getID("date", "id")) ?: return
                    val dateWrapper = dateTextView.parent as ViewGroup
                    val id = Utils.getID("broadcast_icon", "id")
                    val res = dateWrapper.findViewById<View?>(id)
                    if (fMessage.isBroadcast && res == null) {
                        val broadcast = ImageView(dateWrapper.context)
                        broadcast.id = id
                        broadcast.setImageDrawable(DesignUtils.getDrawableByName("broadcast_status_icon"))
                        dateWrapper.addView(broadcast, 0)
                    } else if (!fMessage.isBroadcast && res != null) {
                        dateWrapper.removeView(res)
                    }
                }
            },
        )
    }

    override fun getPluginName(): String = "Tag Message"
}

package com.wax.module.xposed.features.providers

import android.app.Activity
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import androidx.annotation.DrawableRes
import androidx.core.view.children
import com.google.android.material.button.MaterialButton
import com.wax.module.xposed.core.Feature
import com.wax.module.xposed.core.ModuleRuntime
import com.wax.module.xposed.core.components.FMessageWpp
import com.wax.module.xposed.core.devkit.Unobfuscator
import com.wax.module.xposed.utils.DesignUtils
import com.wax.module.xposed.utils.ModuleContextWrapper
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.CopyOnWriteArraySet
import com.google.android.material.R as MaterialR

class ContextMenuActionProvider(
    classLoader: ClassLoader,
    xprefs: SharedPreferences,
) : Feature(classLoader, xprefs) {
    fun interface Provider {
        fun createAction(
            activity: Activity,
            popupWindow: PopupWindow,
            fMessage: FMessageWpp,
        ): ContextMenuAction?
    }

    data class ContextMenuAction(
        val title: String,
        @param:DrawableRes val icon: Int = -1,
        val autoDismiss: Boolean = true,
        val onClick: () -> Unit,
    )

    companion object {
        private val providers = CopyOnWriteArraySet<Provider>()

        fun register(provider: Provider) {
            providers += provider
        }

        fun unregister(provider: Provider) {
            providers -= provider
        }
    }

    override fun doHook() {
        val popupWindowMessage =
            runCatching {
                Unobfuscator.loadPopupWindowMessageClass(classLoader)
            }.getOrElse {
                logDebug("Context-menu popup class unavailable", it)
                return
            }

        XposedBridge.hookAllConstructors(
            popupWindowMessage,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (providers.isEmpty()) return
                    val activity =
                        ModuleRuntime.getCurrentActivity() ?: run {
                            return
                        }
                    val mainPopupWindow = param.thisObject as? PopupWindow ?: return
                    val viewGroup = mainPopupWindow.contentView as? ViewGroup ?: return

                    val fMessageObj = param.args.firstOrNull { FMessageWpp.type.isInstance(it) } ?: return
                    val fMessage = FMessageWpp(fMessageObj)

                    val layout =
                        viewGroup.findViewById<LinearLayout>(Utils.getID("reactions_tray_layout", "id"))
                            ?: return
                    layout.orientation = LinearLayout.VERTICAL
                    val parentItems = layout.children.toList()
                    layout.removeAllViews()
                    val newLLContainer =
                        LinearLayout(viewGroup.context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            parentItems.forEach {
                                addView(it)
                            }
                        }
                    layout.addView(newLLContainer)
                    val buttonLayout =
                        LinearLayout(viewGroup.context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                )
                        }
                    val scrollView =
                        ScrollView(viewGroup.context).apply {
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                )
                            addView(buttonLayout)
                        }
                    layout.addView(scrollView)

                    for (provider in providers) {
                        try {
                            val action =
                                provider.createAction(activity, mainPopupWindow, fMessage) ?: continue
                            val button =
                                buildActionPill(
                                    activity = activity,
                                    action = action,
                                    onActionClick = {
                                        if (action.autoDismiss) {
                                            runCatching { mainPopupWindow.dismiss() }
                                        }
                                        action.onClick()
                                    },
                                )
                            buttonLayout.addView(button)
                        } catch (e: Exception) {
                            logDebug(e)
                        }
                    }
                }
            },
        )
    }

    private fun buildActionPill(
        activity: Activity,
        action: ContextMenuAction,
        onActionClick: () -> Unit,
    ): MaterialButton {
        val ctx = ModuleContextWrapper(activity)
        val textColor = DesignUtils.getPrimaryTextColor()
        val strokeColor =
            Color.argb(80, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
        return MaterialButton(
            ctx,
            null,
            MaterialR.attr.materialButtonOutlinedStyle,
        ).apply {
            text = action.title
            if (action.icon != -1) {
                setIconResource(action.icon)
            }
            setTextColor(textColor)
            setStrokeColor(ColorStateList.valueOf(strokeColor))
            cornerRadius = Utils.dipToPixels(50f)
            setOnClickListener { onActionClick() }
        }
    }

    override fun getPluginName(): String = "ContextMenuActionProvider"
}

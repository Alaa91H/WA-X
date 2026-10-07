package com.wax.module.xposed.features.general

import android.content.SharedPreferences
import android.os.Build
import android.view.Menu
import android.view.MenuItem
import com.wax.module.R
import com.wax.module.xposed.core.Feature
import com.wax.module.xposed.core.ModuleRuntime
import com.wax.module.xposed.core.db.MessageStore
import com.wax.module.xposed.core.devkit.Unobfuscator
import com.wax.module.xposed.features.providers.MenuStatusProvider
import org.luckypray.dexkit.query.enums.StringMatchType

class DeleteStatus(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) : Feature(classLoader, preferences) {
    @Throws(Throwable::class)
    override fun doHook() {
        val statusPlaybackActivityClass =
            Unobfuscator.findFirstClassUsingName(
                classLoader,
                StringMatchType.EndsWith,
                "StatusPlaybackActivity",
            )

        val item =
            object : MenuStatusProvider.Provider {
                override fun addMenu(
                    menu: Menu,
                    statusData: MenuStatusProvider.StatusData,
                ): MenuItem? {
                    if (menu.findItem(R.string.delete_for_me) != null) return null
                    if (statusData.currentItem.isFromMe) return null
                    return menu.add(0, R.string.delete_for_me, 0, R.string.delete_for_me)
                }

                override fun onClick(
                    item: MenuItem,
                    statusData: MenuStatusProvider.StatusData,
                ) {
                    val activity = ModuleRuntime.getCurrentActivity()
                    val messageId = statusData.currentItem.messageID

                    MessageStore.getInstance().deleteStatusByMessageKey(messageId) { success ->
                        if (success && activity != null && statusPlaybackActivityClass.isInstance(activity)) {
                            activity.runOnUiThread {
                                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                                val itemList = statusData.getCurrentItemList()
                                val isLastItem = statusData.currentIndex >= itemList.size - 1

                                if (itemList.size <= 1 || isLastItem) {
                                    activity.finish()
                                } else {
                                    activity.recreate()
                                    applyRecreateTransition(activity)
                                }
                            }
                        }
                    }
                }
            }
        MenuStatusProvider.register(item)
    }

    private fun applyRecreateTransition(activity: android.app.Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                android.app.Activity.OVERRIDE_TRANSITION_OPEN,
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            )
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    override fun getPluginName(): String = "Delete Status"
}

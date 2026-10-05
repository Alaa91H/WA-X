package com.wax.module.xposed.utils

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.view.ContextThemeWrapper
import com.wax.module.R
import com.wax.module.xposed.core.FeatureLoader

class ModuleContextWrapper(
    private val base: Context,
) : ContextThemeWrapper(base, R.style.AppTheme) {
    private var customTheme: Resources.Theme? = null

    override fun getApplicationContext(): Context = base.applicationContext ?: base

    override fun getClassLoader(): ClassLoader =
        ModuleContextWrapper::class.java.classLoader
            ?: super.getClassLoader()

    override fun getResources(): Resources = runCatching { FeatureLoader.moduleContext.resources }.getOrElse { base.resources }

    override fun getAssets(): AssetManager = runCatching { FeatureLoader.moduleContext.assets }.getOrElse { base.assets }

    override fun getTheme(): Resources.Theme {
        if (customTheme == null) {
            try {
                val theme = resources.newTheme()
                theme.applyStyle(R.style.AppTheme, true)
                customTheme = theme
            } catch (_: Throwable) {
                return super.getTheme()
            }
        }
        return customTheme ?: super.getTheme()
    }
}

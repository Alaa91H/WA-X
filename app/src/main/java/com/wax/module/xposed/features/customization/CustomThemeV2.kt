package com.wax.module.xposed.features.customization

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import com.wax.module.utils.ColorReplacement.replaceColors
import com.wax.module.utils.DrawableColors.replaceColor
import com.wax.module.utils.IColors
import com.wax.module.views.WallpaperView
import com.wax.module.xposed.core.Feature
import com.wax.module.xposed.core.ModuleRuntime
import com.wax.module.xposed.core.devkit.Unobfuscator
import com.wax.module.xposed.utils.DesignUtils
import com.wax.module.xposed.utils.ReflectionUtils
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.Properties
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class CustomThemeV2(
    loader: ClassLoader,
    preferences: SharedPreferences,
) : Feature(loader, preferences) {
    companion object {
        private const val FIELD_WALLPAPER_TOOLBAR = "wae_wallpaper_toolbar"
        private const val DEFAULT_WALLPAPER_ALPHA = 30

        private data class Rgb(
            val red: Int,
            val green: Int,
            val blue: Int,
        )

        @JvmStatic
        private fun processColors(
            color: String,
            mapColors: HashMap<String, String>,
        ) {
            val opaqueColor = normalizeOpaqueColor(color) ?: return
            val rgb = parseRgb(opaqueColor) ?: return

            mapColors.keys.toList().forEach { original ->
                // Bound once, before the assignment: reading `mapColors[original]` inside
                // the `when` gives `String?`, which is not what this map holds, and reading
                // it after the assignment would use the value being written rather than the
                // one the entry already had.
                val existing = mapColors[original] ?: return@forEach
                mapColors[original] =
                    when (original.length) {
                        9 -> applyOriginalAlpha(opaqueColor, rgb, existing)
                        7 -> opaqueColor.substring(3)
                        else -> existing
                    }
            }
        }

        private fun normalizeOpaqueColor(color: String): String? =
            when (color.length) {
                7 -> "#ff" + color.substring(1)
                9 -> "#ff" + color.substring(3)
                else -> null
            }

        private fun parseRgb(color: String): Rgb? =
            runCatching {
                Rgb(
                    red = color.substring(3, 5).toInt(16),
                    green = color.substring(5, 7).toInt(16),
                    blue = color.substring(7, 9).toInt(16),
                )
            }.getOrNull()

        private fun applyOriginalAlpha(
            opaqueColor: String,
            rgb: Rgb,
            originalValue: String?,
        ): String {
            if (originalValue == null || originalValue.length != 9 || originalValue.startsWith("#ff")) {
                return opaqueColor
            }

            val alpha = originalValue.substring(1, 3).toIntOrNull(16) ?: return opaqueColor
            val factor = alpha / 255.0f

            fun mix(channel: Int): Int =
                (channel * factor + 255 * (1 - factor))
                    .toInt()
                    .coerceIn(0, 255)

            return "#ff%02x%02x%02x".format(
                mix(rgb.red),
                mix(rgb.green),
                mix(rgb.blue),
            )
        }
    }

    private var wallAlpha: HashMap<String, String>? = null
    private var navAlpha: HashMap<String, String>? = null
    private var toolbarAlpha: HashMap<String, String>? = null
    private var properties: Properties? = null
    private val resolvedColors = ConcurrentHashMap<Int, Int>()
    private val processedResources = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    @Volatile
    private var colorsReady = false

    @Throws(Throwable::class)
    override fun doHook() {
        properties = Utils.getProperties(prefs, "custom_css", "custom_filters")
        hookTheme()
        hookWallpaper()
        XposedBridge.hookAllMethods(
            XposedHelpers.findClass("android.app.ActivityThread", classLoader),
            "handleRelaunchActivity",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    loadAndApplyColors()
                    loadAndApplyColorsWallpaper()
                }
            },
        )
    }

    private fun loadAndApplyColorsWallpaper() {
        val customWallpaper = prefs.getBoolean("wallpaper", false)
        if (!customWallpaper && properties?.containsKey("wallpaper") != true) return

        wallAlpha = transparencyMap(alphaFor("wallpaper_alpha", customWallpaper))
        navAlpha = transparencyMap(alphaFor("wallpaper_alpha_navigation", customWallpaper))
        toolbarAlpha = transparencyMap(alphaFor("wallpaper_alpha_toolbar", customWallpaper))
    }

    private fun alphaFor(
        key: String,
        customWallpaper: Boolean,
    ): Int =
        if (customWallpaper) {
            prefs.getInt(key, DEFAULT_WALLPAPER_ALPHA)
        } else {
            Utils.tryParseInt(properties?.getProperty(key), DEFAULT_WALLPAPER_ALPHA)
        }

    private fun transparencyMap(alphaPercent: Int): HashMap<String, String> =
        HashMap(IColors.colors).also { colors ->
            replaceTransparency(colors, (100 - alphaPercent) / 100.0f)
        }

    @Throws(Exception::class)
    private fun hookWallpaper() {
        if (!prefs.getBoolean("wallpaper", false)) return

        loadAndApplyColorsWallpaper()
        hookWallpaperIntoHomeActivity()
        hookActionModeBackground()
        hookToolbarWallpaperColor()
        hookFragmentWallpaperColors()
        hookNavigationWallpaperColors()
    }

    private fun hookWallpaperIntoHomeActivity() {
        XposedHelpers.findAndHookMethod(
            ModuleRuntime.homeActivityClass,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (canReadWallpaper(activity)) {
                        injectWallpaper(activity.findViewById(Utils.getID("root_view", "id")))
                    }
                }
            },
        )
    }

    private fun canReadWallpaper(activity: Activity): Boolean {
        // READ_MEDIA_IMAGES only exists from API 33. `Manifest.permission` values are
        // compile-time constants, so the older check below would work anyway - but asking a
        // 28..32 device for a permission it can never grant is a check that always answers
        // "denied", and saying so is the difference between a guard and a coincidence.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
                ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.READ_MEDIA_IMAGES,
                ) == PackageManager.PERMISSION_GRANTED
            if (granted) return true
        }
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hookActionModeBackground() {
        val actionModeBarId = Utils.getID("action_mode_bar", "id")
        XposedHelpers.findAndHookMethod(
            View::class.java,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    if (actionModeBarId > 0 && view.id == actionModeBarId) {
                        view.background = DesignUtils.getPrimarySurfaceColor().toDrawable()
                    }
                }
            },
        )
    }

    private fun hookToolbarWallpaperColor() {
        XposedHelpers.findAndHookMethod(
            View::class.java,
            "setBackgroundColor",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val colors = toolbarAlpha ?: return
                    val isWallpaperToolbar =
                        XposedHelpers.getAdditionalInstanceField(
                            param.thisObject,
                            FIELD_WALLPAPER_TOOLBAR,
                        ) == true
                    if (!isWallpaperToolbar) return

                    val color = colors[IColors.toString(param.args[0] as Int)] ?: return
                    param.args[0] = IColors.parseColor(color)
                }
            },
        )
    }

    private fun hookFragmentWallpaperColors() {
        val fragmentView = Unobfuscator.loadFragmentViewMethod(classLoader)
        XposedBridge.hookMethod(
            fragmentView,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!colorsReady || checkNotHomeActivity()) return
                    val colors = wallAlpha ?: return
                    replaceColors(param.result as ViewGroup, colors)
                }
            },
        )
    }

    private fun hookNavigationWallpaperColors() {
        val tabFrameClass = Unobfuscator.loadTabFrameClass(classLoader)
        XposedHelpers.findAndHookMethod(
            FrameLayout::class.java,
            "onMeasure",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!tabFrameClass.isInstance(param.thisObject)) return
                    if (!colorsReady || checkNotHomeActivity()) return

                    val colors = navAlpha ?: return
                    val background = (param.thisObject as ViewGroup).background
                    replaceColor(background, colors)
                }
            },
        )
    }

    @Throws(Throwable::class)
    fun hookTheme() {
        loadAndApplyColors()

        XposedBridge.hookAllMethods(
            AssetManager::class.java,
            "getResourceValue",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!colorsReady) return
                    val typedValue = param.args[2] as TypedValue
                    if (typedValue.type >= TypedValue.TYPE_FIRST_INT &&
                        typedValue.type <= TypedValue.TYPE_LAST_INT
                    ) {
                        if (typedValue.data == 0) return
                        val originalColor = typedValue.data
                        val mappedColor = IColors.getFromIntColor(originalColor, IColors.colors)
                        if (mappedColor == originalColor || checkNotApplyColor(originalColor)) return
                        typedValue.data = mappedColor
                    }
                }
            },
        )

        val resourceImpl = XposedHelpers.findClass("android.content.res.ResourcesImpl", classLoader)

        XposedBridge.hookAllMethods(
            resourceImpl,
            "loadDrawable",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!colorsReady) return
                    val drawable = param.result as? Drawable ?: return
                    if (processedResources.put(drawable, true) != null) return
                    replaceColor(drawable, IColors.colors)
                }
            },
        )

        XposedBridge.hookAllMethods(
            resourceImpl,
            "loadColorStateList",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!colorsReady) return
                    val colorStateList = param.result as? ColorStateList ?: return
                    if (processedResources.put(colorStateList, true) != null) return
                    val mColors =
                        XposedHelpers.getObjectField(colorStateList, "mColors") as IntArray
                    for (i in mColors.indices) {
                        mColors[i] = IColors.getFromIntColor(mColors[i], IColors.colors)
                    }
                }
            },
        )
        val intBgHook = IntBgColorHook()
        XposedHelpers.findAndHookMethod(Paint::class.java, "setColor", Int::class.javaPrimitiveType, intBgHook)
    }

    fun loadAndApplyColors() {
        colorsReady = false
        resolvedColors.clear()
        processedResources.clear()
        IColors.initColors()

        var primaryColorInt = prefs.getInt("primary_color", 0)
        var textColorInt = prefs.getInt("text_color", 0)
        var backgroundColorInt = prefs.getInt("background_color", 0)
        val changeColorEnabled = prefs.getBoolean("changecolor", false)
        val changeColorMode = prefs.getString("changecolor_mode", "manual")
        val useMonetColors =
            changeColorEnabled &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                changeColorMode == "monet"

        if (useMonetColors) {
            val primaryMonetColor =
                resolveMonetColor(
                    if (DesignUtils.isNightMode()) "system_accent1_300" else "system_accent1_600",
                )
            val textMonetColor =
                resolveMonetColor(
                    if (DesignUtils.isNightMode()) "system_neutral1_100" else "system_neutral1_900",
                )
            val backgroundMonetColor =
                resolveMonetColor(
                    if (DesignUtils.isNightMode()) "system_neutral1_900" else "system_neutral1_10",
                )

            if (primaryMonetColor != 0) primaryColorInt = primaryMonetColor
            if (textMonetColor != 0) textColorInt = textMonetColor
            if (backgroundMonetColor != 0) backgroundColorInt = backgroundMonetColor
        }

        var primaryColor =
            DesignUtils.checkSystemColor(properties?.getProperty("primary_color", "0"))
        var textColor =
            DesignUtils.checkSystemColor(properties?.getProperty("text_color", "0"))
        var backgroundColor =
            DesignUtils.checkSystemColor(properties?.getProperty("background_color", "0"))

        if (changeColorEnabled) {
            primaryColor = if (primaryColorInt == 0) "0" else IColors.toString(primaryColorInt)
            textColor = if (textColorInt == 0) "0" else IColors.toString(textColorInt)
            backgroundColor = if (backgroundColorInt == 0) "0" else IColors.toString(backgroundColorInt)
        }

        if (!DesignUtils.isNightMode()) {
            IColors.textColors.clear()
            IColors.textColors.putAll(IColors.backgroundColors)
            IColors.backgroundColors.clear()
        }

        if (changeColorEnabled || properties?.getProperty("change_colors") == "true") {
            if (primaryColor != "0" && DesignUtils.isValidColor(primaryColor)) {
                processColors(primaryColor, IColors.primaryColors)
                processColors(primaryColor, IColors.alphacolors)
            }

            if (textColor != "0" && DesignUtils.isValidColor(textColor)) {
                processColors(textColor, IColors.textColors)
            }

            if (backgroundColor != "0" && DesignUtils.isValidColor(backgroundColor)) {
                processColors(backgroundColor, IColors.backgroundColors)
            }

            val entries = IColors.alphacolors.entries
            val newAlphaColors = HashMap<String, String>()
            for (entry in entries) {
                val color = IColors.primaryColors[entry.key]
                if (color == null) {
                    newAlphaColors[entry.key] = entry.value
                    continue
                }
                val realColor = entry.value
                newAlphaColors[color] = realColor
            }
            IColors.alphacolors = newAlphaColors
        }

        IColors.colors.putAll(IColors.primaryColors)
        IColors.colors.putAll(IColors.textColors)
        IColors.colors.putAll(IColors.backgroundColors)
        IColors.primaryColors.clear()
        IColors.textColors.clear()

        if (!DesignUtils.isNightMode()) {
            IColors.backgroundColors.clear()
            IColors.backgroundColors["#ff1b8755"] = "#ffffffff"
            IColors.backgroundColors["#ffffffff"] = "#ffffffff"
            IColors.backgroundColors["ffffff"] = "ffffff"
        }
        colorsReady = true
    }

    @SuppressLint("DiscouragedApi")
    private fun resolveMonetColor(resourceName: String): Int {
        var colorRes = Resources.getSystem().getIdentifier(resourceName, "color", "android")
        if (colorRes == 0) {
            try {
                colorRes = android.R.color::class.java.getField(resourceName).getInt(null)
            } catch (_: Throwable) {
                return 0
            }
        }
        if (colorRes == 0) return 0
        return try {
            ContextCompat.getColor(Utils.application, colorRes)
        } catch (_: Throwable) {
            0
        }
    }

    private fun replaceTransparency(
        wallpaperColors: HashMap<String, String>?,
        mAlpha: Float,
    ) {
        if (wallpaperColors == null) return
        val clampedAlpha = mAlpha.coerceIn(0f, 1f)
        val alphaInt = (clampedAlpha * 255).roundToInt()
        var hexAlpha = Integer.toHexString(alphaInt)
        if (hexAlpha.length == 1) hexAlpha = "0$hexAlpha"
        val keysToIterate = HashSet(IColors.backgroundColors.keys)

        for (c in keysToIterate) {
            val oldColor = wallpaperColors.getOrDefault(c, IColors.backgroundColors[c])
            if (oldColor == null || oldColor.length < 9 || !oldColor.startsWith("#")) continue
            val newColor = "#$hexAlpha${oldColor.substring(3)}"
            wallpaperColors[c] = newColor
            wallpaperColors[oldColor] = newColor
        }
    }

    private fun injectWallpaper(view: View?) {
        val content = view as ViewGroup
        val rootView = content.getChildAt(0) as ViewGroup

        val header = content.findViewById<ViewGroup>(Utils.getID("header", "id"))
        header.background = null
        header.backgroundTintList = null
        val toolbarContainer =
            content.findViewById<ViewGroup>(Utils.getID("toolbar_container", "id"))
        if (toolbarContainer != null) {
            XposedHelpers.setAdditionalInstanceField(
                toolbarContainer,
                FIELD_WALLPAPER_TOOLBAR,
                true,
            )
            toolbarContainer.background = null
            toolbarContainer.backgroundTintList = null
        }
        val toolbar = content.findViewById<View>(Utils.getID("toolbar", "id"))
        val firstChild = header.getChildAt(0)
        if (firstChild != null && toolbar != firstChild) {
            firstChild.background = null
            firstChild.backgroundTintList = null
        }
        XposedHelpers.setAdditionalInstanceField(toolbar, FIELD_WALLPAPER_TOOLBAR, true)
        toolbar.background = null
        toolbar.backgroundTintList = null
        replaceColors(toolbar, toolbarAlpha)
        val frameLayout = WallpaperView(rootView.context, prefs, properties!!)
        rootView.addView(frameLayout, 0)
    }

    private fun checkNotHomeActivity(): Boolean {
        val homeClass = ModuleRuntime.homeActivityClass
        val currentActivity = ModuleRuntime.getCurrentActivity()
        return (currentActivity == null || !homeClass.isInstance(currentActivity))
    }

    private fun checkNotApplyColor(color: Int): Boolean {
        val activity = ModuleRuntime.getCurrentActivity()
        if (activity != null &&
            activity.javaClass.simpleName == "Conversation" &&
            ReflectionUtils.isCalledFromStrings("getValue") &&
            !ReflectionUtils.isCalledFromStrings("android.view")
        ) {
            return color != 0xff12181c.toInt()
        }
        return false
    }

    override fun getPluginName(): String = "Custom Theme V2"

    inner class IntBgColorHook : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!colorsReady) return
            val currentActivity = ModuleRuntime.getCurrentActivity()
            if (currentActivity == null || currentActivity.javaClass.simpleName == "Conversation") return

            val color = param.args[0] as Int
            val mappedColor =
                resolvedColors[color]
                    ?: IColors.getFromIntColor(color, IColors.colors).also {
                        resolvedColors[color] = it
                    }
            param.args[0] = mappedColor
        }
    }
}

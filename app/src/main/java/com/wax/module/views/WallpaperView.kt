package com.wax.module.views

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import com.wax.module.preference.ThemePreference
import com.wax.module.xposed.core.ModuleRuntime
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.XposedBridge.log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

@SuppressLint("ViewConstructor")
class WallpaperView(
    context: Context,
    private val prefs: SharedPreferences,
    private val properties: Properties,
) : FrameLayout(context) {
    init {
        initView(context)
    }

    private fun initView(context: Context) {
        val bgView =
            ImageView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setAdjustViewBounds(false)
            }
        addView(bgView)

        try {
            var image =
                ThemePreference.rootDirectory.absolutePath + "/" + prefs.getString("folder_theme", "") + "/" +
                    properties.getProperty("wallpaper_file")
            if (prefs.getBoolean("wallpaper", false)) {
                image = prefs.getString("wallpaper_file", "") ?: ""
            }
            val imagePath = image
            Utils.executor.execute {
                try {
                    val drawable = getDrawableImage(imagePath)
                    if (drawable != null) {
                        Handler(Looper.getMainLooper()).post {
                            bgView.setImageDrawable(drawable)
                        }
                    }
                } catch (e: Exception) {
                    log("Error loading wallpaper drawable: " + e.message)
                }
            }
        } catch (e: Exception) {
            log("Error initializing wallpaper view: " + e.message)
        }
    }

    private fun getDrawableImage(imagePath: String): BitmapDrawable? {
        val fileOut = context.filesDir.absolutePath + "/wallpaper.jpg"
        val file = File(imagePath)
        if (!file.exists()) return null
        val filePath = file.absolutePath
        val lastModified = file.lastModified()
        val cacheKey = "${filePath}_$lastModified"

        val cachedData = ModuleRuntime.getPrivString("wallpaper_data", "")

        if (cacheKey == cachedData && File(fileOut).exists()) {
            val bitmap = BitmapFactory.decodeFile(fileOut) ?: return null
            return bitmap.toDrawable(resources)
        }

        val bitmap =
            if (!file.canRead()) {
                val parcelFile = ModuleRuntime.getClientBridge()?.openFile(filePath, false) ?: return null
                BitmapFactory.decodeStream(FileInputStream(parcelFile.fileDescriptor))
            } else {
                BitmapFactory.decodeFile(file.absolutePath)
            } ?: return null

        val displayMetrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(displayMetrics)
        val width = if (displayMetrics.widthPixels > 0) displayMetrics.widthPixels else bitmap.width
        val height = if (displayMetrics.heightPixels > 0) displayMetrics.heightPixels else bitmap.height

        val scaledBitmap = bitmap.scale(width, height, true)

        try {
            FileOutputStream(fileOut).use { outputStream ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                outputStream.flush()
            }
        } catch (ignored: Exception) {
        }

        ModuleRuntime.setPrivString("wallpaper_data", cacheKey)
        if (scaledBitmap != bitmap) {
            bitmap.recycle()
        }

        return scaledBitmap.toDrawable(resources)
    }
}

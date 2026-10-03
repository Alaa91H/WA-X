package com.wmods.wppenhacer.xposed.bridge.service

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.wmods.wppenhacer.BuildConfig
import com.wmods.wppenhacer.xposed.bridge.WaeIIFace
import com.wmods.wppenhacer.xposed.core.FeatureLoader
import java.io.File
import java.io.FileNotFoundException

object HookBinder : WaeIIFace.Stub() {

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private fun enforceAllowedCaller() {
        val context = appContext ?: throw SecurityException("Bridge is not initialized")
        val callingUid = Binder.getCallingUid()

        if (callingUid == Process.myUid()) return

        val packages = context.packageManager.getPackagesForUid(callingUid).orEmpty()
        val isAllowed = packages.any { packageName ->
            packageName == FeatureLoader.PACKAGE_WPP ||
                packageName == FeatureLoader.PACKAGE_BUSINESS ||
                packageName == BuildConfig.APPLICATION_ID
        }

        if (!isAllowed) {
            throw SecurityException("Unauthorized WaEnhancer bridge caller uid=$callingUid")
        }
    }

    override fun openFile(path: String, create: Boolean): ParcelFileDescriptor? {
        enforceAllowedCaller()

        val file = File(path)
        if (!file.exists() && create) {
            try {
                file.parentFile?.mkdirs()
                file.createNewFile()
            } catch (_: Exception) {
                return null
            }
        }
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        } catch (_: FileNotFoundException) {
            null
        }
    }

    override fun createDir(path: String): Boolean {
        enforceAllowedCaller()
        val file = File(path)
        return file.isDirectory || file.mkdirs()
    }

    override fun exists(path: String): Boolean {
        enforceAllowedCaller()
        return File(path).exists()
    }

    override fun listFiles(path: String): List<File> {
        enforceAllowedCaller()
        return File(path).listFiles()?.toList() ?: emptyList()
    }
}

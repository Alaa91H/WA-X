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

    private fun resolveAllowedPath(path: String): File {
        enforceAllowedCaller()

        val target = File(path).canonicalFile
        val storageRoot = File("/storage").canonicalFile
        val rootPath = storageRoot.path
        val targetPath = target.path

        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
            throw SecurityException("Bridge path is outside shared external storage")
        }
        return target
    }

    override fun openFile(path: String, create: Boolean): ParcelFileDescriptor? {
        val file = resolveAllowedPath(path)
        if (create) {
            try {
                file.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) return null
                }
            } catch (_: Exception) {
                return null
            }
        }

        val mode = if (create) {
            ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE
        } else {
            ParcelFileDescriptor.MODE_READ_WRITE
        }

        return try {
            ParcelFileDescriptor.open(file, mode)
        } catch (_: FileNotFoundException) {
            null
        }
    }

    override fun createDir(path: String): Boolean {
        val file = resolveAllowedPath(path)
        return file.isDirectory || file.mkdirs()
    }

    override fun exists(path: String): Boolean {
        return resolveAllowedPath(path).exists()
    }

    override fun listFiles(path: String): List<File> {
        return resolveAllowedPath(path).listFiles()?.toList() ?: emptyList()
    }
}

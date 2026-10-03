package com.wmods.wppenhacer.xposed.bridge.service

import android.os.Binder
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import com.wmods.wppenhacer.App
import com.wmods.wppenhacer.xposed.bridge.WaeIIFace
import com.wmods.wppenhacer.xposed.core.FeatureLoader
import java.io.File
import java.io.FileNotFoundException

object HookBinder : WaeIIFace.Stub() {

    private val allowedCallerPackages = setOf(
        FeatureLoader.PACKAGE_WPP,
        FeatureLoader.PACKAGE_BUSINESS
    )

    private fun enforceAllowedCaller() {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid()) return

        val packages = App.instance.packageManager.getPackagesForUid(callingUid).orEmpty()
        if (packages.none { it in allowedCallerPackages }) {
            throw SecurityException("Unauthorized WaEnhancer bridge caller uid=$callingUid")
        }
    }

    private fun resolveAllowedPath(path: String): File {
        enforceAllowedCaller()

        val target = File(path).canonicalFile
        val externalRoot = Environment.getExternalStorageDirectory().canonicalFile
        val rootPath = externalRoot.path
        val targetPath = target.path

        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
            throw SecurityException("Bridge path is outside shared external storage")
        }
        return target
    }

    override fun openFile(path: String, create: Boolean): ParcelFileDescriptor? {
        val file = resolveAllowedPath(path)
        if (!file.exists() && create) {
            try {
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
        val file = resolveAllowedPath(path)
        return file.mkdirs() || file.isDirectory
    }

    override fun exists(path: String): Boolean {
        return resolveAllowedPath(path).exists()
    }

    override fun listFiles(path: String): List<File> {
        return resolveAllowedPath(path).listFiles()?.toList() ?: emptyList()
    }
}

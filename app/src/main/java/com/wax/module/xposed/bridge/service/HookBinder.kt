package com.wax.module.xposed.bridge.service

import android.os.Binder
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import com.wax.module.ModuleApplication
import com.wax.module.xposed.bridge.BridgeAccessPolicy
import com.wax.module.xposed.bridge.WaeIIFace
import java.io.File
import java.io.FileNotFoundException

object HookBinder : WaeIIFace.Stub() {
    private fun enforceAllowedCaller() {
        val callingUid = Binder.getCallingUid()
        val packages = ModuleApplication.instance.packageManager.getPackagesForUid(callingUid)
        val allowed =
            BridgeAccessPolicy.isAllowedTunnelCaller(
                packages = packages,
                isSelfUid = callingUid == Process.myUid(),
            )
        if (!allowed) {
            throw SecurityException("Unauthorized WA X bridge caller uid=$callingUid")
        }
    }

    private fun sharedStorageRoots(): List<File> =
        BridgeAccessPolicy.sharedStorageRoots(
            externalStorageDir = Environment.getExternalStorageDirectory(),
            externalFilesDirs = ModuleApplication.instance.getExternalFilesDirs(null).toList(),
        )

    private fun resolveAllowedPath(path: String): File {
        enforceAllowedCaller()

        val target = File(path).canonicalFile
        val targetPath = target.path
        val isSharedStorage =
            sharedStorageRoots().any { root ->
                BridgeAccessPolicy.isUnderRoot(targetPath, root.path)
            }

        if (!isSharedStorage) {
            throw SecurityException("Bridge path is outside shared external storage")
        }
        return target
    }

    override fun openFile(
        path: String,
        create: Boolean,
    ): ParcelFileDescriptor? {
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

    override fun exists(path: String): Boolean = resolveAllowedPath(path).exists()

    override fun listFiles(path: String): List<File> = resolveAllowedPath(path).listFiles()?.toList() ?: emptyList()
}

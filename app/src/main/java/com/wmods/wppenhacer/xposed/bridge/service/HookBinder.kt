package com.wmods.wppenhacer.xposed.bridge.service

import android.os.Environment
import android.os.ParcelFileDescriptor
import com.wmods.wppenhacer.App
import com.wmods.wppenhacer.xposed.bridge.BridgeAccess
import com.wmods.wppenhacer.xposed.bridge.WaeIIFace
import java.io.File
import java.io.FileNotFoundException

object HookBinder : WaeIIFace.Stub() {

    private fun enforceCaller() {
        BridgeAccess.enforceCaller(App.instance)
    }

    private fun externalFile(path: String): File {
        val file = File(path).canonicalFile
        val primaryExternal = Environment.getExternalStorageDirectory().canonicalFile.path
        val canonicalPath = file.path
        val allowed = canonicalPath == primaryExternal ||
                canonicalPath.startsWith(primaryExternal + File.separator) ||
                canonicalPath == "/storage" ||
                canonicalPath.startsWith("/storage/")
        if (!allowed) {
            throw SecurityException("WaEnhancer bridge only allows external-storage paths")
        }
        return file
    }

    override fun openFile(path: String, create: Boolean): ParcelFileDescriptor? {
        enforceCaller()
        val file = externalFile(path)
        if (create) {
            try {
                file.parentFile?.mkdirs()
            } catch (_: Exception) {
                return null
            }
        }
        return try {
            var mode = ParcelFileDescriptor.MODE_READ_WRITE
            if (create) {
                mode = mode or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            }
            ParcelFileDescriptor.open(file, mode)
        } catch (_: FileNotFoundException) {
            null
        }
    }

    override fun createDir(path: String): Boolean {
        enforceCaller()
        val file = externalFile(path)
        return file.mkdirs() || file.isDirectory
    }

    override fun exists(path: String): Boolean {
        enforceCaller()
        return externalFile(path).exists()
    }

    override fun listFiles(path: String): List<File> {
        enforceCaller()
        return externalFile(path).listFiles()?.toList() ?: emptyList()
    }
}

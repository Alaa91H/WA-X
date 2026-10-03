package com.wmods.wppenhacer.xposed.bridge.service

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

    override fun openFile(path: String, create: Boolean): ParcelFileDescriptor? {
        enforceCaller()
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
        enforceCaller()
        val file = File(path)
        return file.mkdirs() || file.isDirectory
    }

    override fun exists(path: String): Boolean {
        enforceCaller()
        return File(path).exists()
    }

    override fun listFiles(path: String): List<File> {
        enforceCaller()
        return File(path).listFiles()?.toList() ?: emptyList()
    }
}

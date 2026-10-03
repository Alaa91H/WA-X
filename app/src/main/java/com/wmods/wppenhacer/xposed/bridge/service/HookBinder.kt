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

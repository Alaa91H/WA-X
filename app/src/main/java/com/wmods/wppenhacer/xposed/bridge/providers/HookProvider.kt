package com.wmods.wppenhacer.xposed.bridge.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.wmods.wppenhacer.xposed.bridge.service.HookBinder
import com.wmods.wppenhacer.xposed.core.FeatureLoader

class HookProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(HookBinder::initialize)
        return true
    }

    private fun isCallerAllowed(): Boolean {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid() || callingUid == Process.SYSTEM_UID) return true

        val packages = context?.packageManager?.getPackagesForUid(callingUid).orEmpty()
        return packages.any {
            it == "com.android.providers.settings" ||
                it == FeatureLoader.PACKAGE_WPP ||
                it == FeatureLoader.PACKAGE_BUSINESS
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!isCallerAllowed()) {
            throw SecurityException("Unauthorized WaEnhancer hook provider caller")
        }
        context?.let(HookBinder::initialize)
        if (method == "getHookBinder") {
            val result = Bundle()
            result.putBinder("binder", HookBinder)
            return result
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<String?>?,
        selection: String?,
        selectionArgs: Array<String?>?,
        sortOrder: String?
    ): Cursor? {
        return null
    }

    override fun getType(uri: Uri): String {
        return ""
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int {
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String?>?
    ): Int {
        return 0
    }
}

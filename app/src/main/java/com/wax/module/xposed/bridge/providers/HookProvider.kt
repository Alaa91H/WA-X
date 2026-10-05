package com.wax.module.xposed.bridge.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.wax.module.BuildConfig
import com.wax.module.xposed.bridge.BridgeAccessPolicy
import com.wax.module.xposed.bridge.service.HookBinder

class HookProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun isCallerAllowed(): Boolean {
        val callingUid = Binder.getCallingUid()
        return BridgeAccessPolicy.isAllowedBinderRequester(
            packages = context?.packageManager?.getPackagesForUid(callingUid),
            isSelfUid = callingUid == Process.myUid(),
            isSystemUid = callingUid == Process.SYSTEM_UID,
            modulePackage = BuildConfig.APPLICATION_ID,
        )
    }

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (!isCallerAllowed()) {
            throw SecurityException("Unauthorized WA X hook provider caller")
        }
        if (method == "getHookBinder") {
            return Bundle().apply {
                putBinder("binder", HookBinder)
            }
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<String?>?,
        selection: String?,
        selectionArgs: Array<String?>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String = ""

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String?>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String?>?,
    ): Int = 0
}

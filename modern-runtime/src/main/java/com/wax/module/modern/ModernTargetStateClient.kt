package com.wax.module.modern

import android.content.Context
import android.net.Uri
import android.os.Bundle

/**
 * Target-process reader for the Manager's verified runtime state (#433).
 *
 * The embedded Control Center must show what the runtime actually reported,
 * not merely what the user last stored. Target-originated preferences are
 * read-only inside a hooked app, so the Manager answers through the same
 * UID-authenticated provider that accepts writes.
 *
 * The response carries two prefixed key families:
 * - `pref.<preferenceKey>`: the current requested booleans, so the control
 *   shows the stored state even before any hook has reported.
 * - `state.<evidenceKey>`: the last effective state the target reported.
 *
 * Binder calls must stay off the hooked UI thread; the caller decides when to
 * read. A missing or rejected response degrades to "not observed", never to a
 * false "working".
 */
object ModernTargetStateClient {
    /** Shared identity for authenticated state reads and change notifications. */
    @JvmField val STATES_URI: Uri = Uri.parse("content://com.wax.module.runtime.telemetry")
    private const val METHOD = "read-target-states-v1"

    @JvmStatic
    fun read(context: Context?, packageName: String?): Bundle {
        if (context == null || packageName == null) return Bundle()
        if (!ModernTargetPolicy.isTargetPackageForProcess(context.packageName, packageName)) {
            return Bundle()
        }
        return try {
            val extras = Bundle()
            extras.putString("target", packageName)
            val response = context.contentResolver.call(STATES_URI, METHOD, null, extras)
            response ?: Bundle()
        } catch (failure: RuntimeException) {
            Bundle()
        }
    }
}
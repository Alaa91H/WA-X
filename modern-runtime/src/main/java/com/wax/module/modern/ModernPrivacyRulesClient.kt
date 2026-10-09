package com.wax.module.modern

import android.content.Context
import android.net.Uri
import android.os.Bundle

/**
 * Reads one contact's privacy rules from the Manager, on behalf of the
 * injected target.
 *
 * The rules live in the Manager's private preferences; RemotePreferences are
 * read-only inside WhatsApp. Asking per contact keeps the transfer minimal —
 * two booleans for the number the hook is already inspecting — instead of
 * bulk-syncing the whole address book into the injected process.
 */
object ModernPrivacyRulesClient {
    private val PROVIDER: Uri = Uri.parse("content://com.wax.module.runtime.telemetry")
    private const val METHOD = "read-target-privacy-v1"

    @JvmStatic
    fun fetch(
        context: Context?,
        packageName: String?,
        number: String?,
    ): ModernTypingPrivacyFeature.PrivacyRule? {
        if (context == null || packageName == null || number.isNullOrBlank()) return null
        if (!ModernTargetPolicy.isTargetPackageForProcess(context.packageName, packageName)) {
            return null
        }
        return try {
            val extras = Bundle()
            extras.putString("target", packageName)
            extras.putString("number", number)
            val response = context.contentResolver.call(PROVIDER, METHOD, null, extras)
            if (response == null || !response.getBoolean("accepted", false)) return null
            ModernTypingPrivacyFeature.PrivacyRule(
                hideTyping = response.getBoolean("hide_typing", false),
                hideRecording = response.getBoolean("hide_recording", false),
            )
        } catch (failure: RuntimeException) {
            null
        }
    }
}
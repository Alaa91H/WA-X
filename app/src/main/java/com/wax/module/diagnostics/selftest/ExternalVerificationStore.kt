package com.wax.module.diagnostics.selftest

import android.content.Context
import androidx.core.content.edit

/**
 * User-confirmed external verification (#170).
 *
 * L5 — an effect another human can actually see — is the one evidence level
 * nothing inside WA X can reach on its own. The only honest way to record it is
 * to ask the user: they perform the action with a second account, they observe
 * the result, and they say so. This store is that record, and nothing else may
 * write to it.
 *
 * Two rules keep the claim honest:
 * - a confirmation is bound to the WhatsApp build it was made on, so evidence
 *   from another version can never be reused for this one;
 * - a confirmation is stored per feature, so confirming one behaviour does not
 *   silently verify the next.
 */
class ExternalVerificationStore(
    context: Context,
) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What the user confirmed, and when. */
    data class Confirmation(
        val featureId: String,
        val whatsappBuild: String,
        val confirmedAtUtcMillis: Long,
        val note: String,
    )

    fun confirm(
        featureId: String,
        whatsappBuild: String,
        note: String,
        nowUtcMillis: Long,
    ) {
        prefs.edit {
            putLong(key(featureId, whatsappBuild), nowUtcMillis)
            putString(noteKey(featureId, whatsappBuild), note)
        }
    }

    fun revoke(
        featureId: String,
        whatsappBuild: String,
    ) {
        prefs.edit {
            remove(key(featureId, whatsappBuild))
            remove(noteKey(featureId, whatsappBuild))
        }
    }

    fun confirmationFor(
        featureId: String,
        whatsappBuild: String,
    ): Confirmation? {
        val at = prefs.getLong(key(featureId, whatsappBuild), 0L)
        if (at <= 0L) return null
        return Confirmation(
            featureId = featureId,
            whatsappBuild = whatsappBuild,
            confirmedAtUtcMillis = at,
            note = prefs.getString(noteKey(featureId, whatsappBuild), "") ?: "",
        )
    }

    fun confirmations(whatsappBuild: String): List<Confirmation> =
        prefs.all.keys
            .filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX + whatsappBuild) }
            .mapNotNull { full ->
                val featureId = full.removePrefix(PREFIX).removeSuffix(SUFFIX + whatsappBuild)
                confirmationFor(featureId, whatsappBuild)
            }.sortedBy { it.featureId }

    private fun key(
        featureId: String,
        whatsappBuild: String,
    ) = PREFIX + featureId + SUFFIX + whatsappBuild

    private fun noteKey(
        featureId: String,
        whatsappBuild: String,
    ) = NOTE_PREFIX + featureId + SUFFIX + whatsappBuild

    companion object {
        private const val PREFS = "wax_diagnostics_external_verification"
        private const val PREFIX = "confirmed."
        private const val NOTE_PREFIX = "note."
        private const val SUFFIX = "@"

        /** The build label used when the scan could not read one. */
        const val UNKNOWN_BUILD = "unknown"
    }
}

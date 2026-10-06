package com.wax.module.media

import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.TargetApp

/**
 * Where WhatsApp should take media from when the user attaches something.
 *
 * The point of the feature is permission scope, not convenience: the Android photo picker hands
 * the app exactly the items the user selected, while going through the in-app gallery requires
 * broad read access to the whole media library. WA X prefers the narrower path by default and
 * makes the wider one something the user chose, per hooked application.
 */
enum class MediaSourceMode(
    val label: String,
) {
    /** WhatsApp's own gallery and picker. Requires broad media read access. */
    WHATSAPP_GALLERY("WhatsApp gallery"),

    /** The Android system photo picker. Only the selected items are shared. */
    ANDROID_PHOTO_PICKER("Android photo picker"),

    /** Ask which one to use each time. */
    ASK_EVERY_TIME("Ask every time"),
    ;

    /** Whether this mode needs access to the whole media library. */
    val needsBroadMediaAccess: Boolean get() = this == WHATSAPP_GALLERY
}

/** The resolved instruction for one target. */
data class MediaSourceDecision(
    val mode: MediaSourceMode,
    /** Whether the picker should be launched directly, without asking first. */
    val launchPickerDirectly: Boolean,
    /** Sentence for the settings summary. Explains the permission consequence. */
    val explanation: String,
) {
    /** One line for diagnostics. */
    fun toDisplayLine(): String = "media source ${mode.name.lowercase()} for ${if (launchPickerDirectly) "picker" else "chooser"}"
}

/**
 * The media-source choice for each hooked application.
 *
 * WhatsApp and Business hold independent values, as the prompt requires, because the two apps
 * are separate packages and a permission granted to one says nothing about the other. A missing
 * or unreadable entry resolves to the default rather than to an error: the picker is the safe
 * answer, so the default is the safe answer too.
 */
class MediaSourcePolicyStore(
    private val store: KeyValueStore,
) {
    /** The mode for [app]. */
    fun modeFor(app: TargetApp): MediaSourceMode {
        val stored = store.getString(keyFor(app)) ?: return DEFAULT
        return MediaSourceMode.entries.firstOrNull { it.name == stored } ?: DEFAULT
    }

    /** Sets the mode for [app]. The default is stored by removing the entry. */
    fun setMode(
        app: TargetApp,
        mode: MediaSourceMode,
    ) {
        if (mode == DEFAULT) {
            store.remove(keyFor(app))
            return
        }
        store.putString(keyFor(app), mode.name)
    }

    /** Resolves the mode for [app] into an instruction for the attach flow. */
    fun decide(app: TargetApp): MediaSourceDecision {
        val mode = modeFor(app)
        return MediaSourceDecision(
            mode = mode,
            launchPickerDirectly = mode == MediaSourceMode.ANDROID_PHOTO_PICKER,
            explanation =
                when (mode) {
                    MediaSourceMode.WHATSAPP_GALLERY -> {
                        "Attachments open in WhatsApp's gallery, which needs access to your whole media library."
                    }

                    MediaSourceMode.ANDROID_PHOTO_PICKER -> {
                        "Attachments open the system picker, which shares only the items you choose."
                    }

                    MediaSourceMode.ASK_EVERY_TIME -> {
                        "You choose between the gallery and the system picker for each attachment."
                    }
                },
        )
    }

    /** Every target that does not use the default. Used by diagnostics and settings export. */
    fun configuredTargets(): List<TargetApp> =
        store
            .keys(KEY_PREFIX)
            .mapNotNull { key -> TargetApp.fromCode(key.removePrefix(KEY_PREFIX)) }
            .distinct()

    /** Restores the default for every target. Used by tests and by a factory reset. */
    fun clear() {
        store.keys(KEY_PREFIX).forEach { store.remove(it) }
    }

    private fun keyFor(app: TargetApp): String = KEY_PREFIX + app.code

    companion object {
        /** Prefix for every stored media-source choice. */
        const val KEY_PREFIX: String = "wae.media.source."

        /** The picker is the default because it needs the narrowest permission. */
        val DEFAULT: MediaSourceMode = MediaSourceMode.ANDROID_PHOTO_PICKER
    }
}

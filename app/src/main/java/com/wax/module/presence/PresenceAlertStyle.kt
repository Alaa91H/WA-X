package com.wax.module.presence

import com.wax.module.notifications.AlertChannel

/**
 * How one activity's alert reaches the user.
 *
 * The presets are expressed in the notification platform's own vocabulary — [AlertChannel] —
 * rather than in a second set of names invented here. That is deliberate: the burst cooldown
 * that already exists silences those same three channels, so "the beep and the floating banner"
 * has to mean the same two things to both features or a user who silences one and enables the
 * other gets a combination neither documented. A style is therefore nothing more than a named
 * subset of the channels, and the engine reads the channels.
 *
 * There is no member that draws inside WhatsApp. Every style is delivered as WA X's own Android
 * notification, which is what lets the feature stay on while Stock WhatsApp Mode is on: the
 * floating banner is Android's heads-up presentation of that notification, so WhatsApp's own
 * interface is never touched and nothing has to replace it in Stock Mode.
 */
enum class PresenceAlertStyle(
    /** Shown in the settings screen. */
    val label: String,
    /** The channels this style delivers on. */
    val channels: Set<AlertChannel>,
) {
    /** No alert. Also the value an unconfigured activity resolves to. */
    NOTHING("Nothing", emptySet()),

    /** The beep only, with no banner. */
    BEEP("Beep", setOf(AlertChannel.SOUND)),

    /** The floating banner only, silently. */
    BANNER("Floating banner", setOf(AlertChannel.HEADS_UP)),

    /** Both: the beep and the floating banner. */
    BEEP_AND_BANNER("Beep and floating banner", setOf(AlertChannel.SOUND, AlertChannel.HEADS_UP)),

    /** Vibration only. */
    VIBRATE("Vibration", setOf(AlertChannel.VIBRATION)),

    /** Everything the device can do. */
    BEEP_BANNER_VIBRATE("Beep, floating banner and vibration", AlertChannel.entries.toSet()),
    ;

    /** Whether this style delivers nothing at all. */
    val isSilent: Boolean get() = channels.isEmpty()

    /** Whether this style makes a sound. */
    val hasSound: Boolean get() = AlertChannel.SOUND in channels

    /** Whether this style shows the floating banner. */
    val hasBanner: Boolean get() = AlertChannel.HEADS_UP in channels

    /** Whether this style vibrates. */
    val hasVibration: Boolean get() = AlertChannel.VIBRATION in channels

    companion object {
        /** Every style a settings screen may offer, in the order it should offer them. */
        val CHOICES: List<PresenceAlertStyle> = entries

        /** The style stored under [name], or null when the stored value is not one of these. */
        fun fromName(name: String?): PresenceAlertStyle? = entries.firstOrNull { it.name == name }
    }
}

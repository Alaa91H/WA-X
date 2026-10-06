package com.wax.module.platform

/**
 * Why a feature can or cannot run here.
 *
 * This is the whole availability vocabulary, and every value in it is a *technical* or
 * *safety* state. There is deliberately no paid state: the access contract is that a WA X
 * feature is limited by capability, version, target, permissions or an unfinished
 * implementation, never by whether the user paid for anything. A state that cannot be
 * explained by one of these values does not exist, which is what makes the contract
 * checkable instead of aspirational.
 *
 * The set is also the exact vocabulary the interface is allowed to show. Each value carries
 * the sentence to display, so a screen cannot word an unsupported version as "Premium", and
 * [NoPaywallContract.containsPaymentWording] can be run over every sentence the platform
 * will ever render.
 */
enum class FeatureAvailability(
    /** The sentence shown to the user. Must never imply a paid tier. */
    val explanation: String,
) {
    /** The feature can run now. */
    AVAILABLE("Available."),

    /** It works, but it is capability-gated and the user has not opted into Labs yet. */
    EXPERIMENTAL("Experimental — turn it on from Labs first."),

    /** It was never written for this target, or the target does not expose what it needs. */
    UNSUPPORTED_TARGET("Unavailable — not supported on this app."),

    /** This WhatsApp build is outside the feature's declared compatibility range. */
    UNSUPPORTED_VERSION("Unavailable — not supported on the installed WhatsApp version."),

    /** Something the feature needs is not present: a resolver, a permission, root, an API. */
    MISSING_CAPABILITY("Unavailable — this WhatsApp version does not expose what it needs."),

    /** Held back after a compatibility problem, by the canary or by the isolation engine. */
    TEMPORARILY_DISABLED("Temporarily disabled because of a compatibility problem."),

    /** Declared but not built. Kept honest instead of being shown as available. */
    NOT_IMPLEMENTED("Not implemented yet."),
    ;

    /** Whether the feature may load in this state. */
    val isAvailable: Boolean get() = this == AVAILABLE

    /**
     * Whether this state is a technical limitation rather than a user preference.
     *
     * Always true, and that is the point: the enum has no non-technical member, so this is a
     * statement of the contract that the type system enforces. It exists so a caller can
     * read `report.availability.isTechnical` and be explicit about why a feature is off,
     * rather than reaching for a paid/unpaid distinction that must not exist.
     */
    val isTechnical: Boolean get() = true
}

/**
 * What a feature does to WhatsApp's own interface.
 *
 * Stock WhatsApp Mode needs a mechanical answer to "is this feature allowed to be visible
 * inside WhatsApp right now", and an author's opinion is not good enough — a new feature
 * that injects a toolbar button would silently break the visual contract. Declaring the
 * impact makes the decision data, and [FeatureRegistry] refuses a declaration that changes
 * WhatsApp's interface without saying what replaces it in Stock Mode.
 */
enum class VisualImpact {
    /** Touches nothing the user can see. */
    NONE,

    /** Its interface lives outside WhatsApp: WA X, a share sheet, a shortcut, a tile. */
    EXTERNAL_ONLY,

    /** Changes WhatsApp's behaviour or native control state without adding or moving anything. */
    MODIFIES_NATIVE_STATE,

    /** Adds items of its own: menu entries, rows, buttons, tabs, banners. */
    INJECTS_UI,

    /** Removes native items the user would otherwise see. */
    HIDES_NATIVE_UI,

    /** Moves native items out of their stock order. */
    REORDERS_NATIVE_UI,

    /** Changes how native items look: colours, icons, spacing, labels. */
    RESTYLES_NATIVE_UI,
    ;

    /**
     * Whether this impact is observable inside WhatsApp.
     *
     * The first three values leave the interface stock and may stay active under Stock Mode.
     * The last four are exactly what Stock Mode has to suppress, so the split is the rule
     * itself rather than a judgement call at each call site.
     */
    val isVisibleInWhatsApp: Boolean
        get() =
            this == INJECTS_UI ||
                this == HIDES_NATIVE_UI ||
                this == REORDERS_NATIVE_UI ||
                this == RESTYLES_NATIVE_UI
}

/**
 * How much enabling a feature can cost the account.
 *
 * Not a severity for the user to worry about, but an input to the platform's rules: a HIGH
 * feature has to be capability-gated (the registry rejects one with no required resolver) so
 * a WhatsApp update can hold it back before it can do damage.
 */
enum class RiskLevel {
    /** Purely local, reversible, no protocol interaction. */
    LOW,

    /** Interacts with WhatsApp behaviour the user will notice if it goes wrong. */
    MEDIUM,

    /** Can affect account standing or message delivery if it misfires. */
    HIGH,
}

/** When a change to the feature takes effect. */
enum class RestartRequirement {
    /** Immediately. */
    NONE,

    /** The hooked WhatsApp process has to restart. */
    TARGET_RESTART,

    /** WA X itself has to restart for the change to be picked up. */
    MODULE_RESTART,

    /** Only a device reboot applies it, for example a boot-time hook. */
    DEVICE_REBOOT,
}

/**
 * How a feature behaves when one target holds more than one account.
 *
 * The distinction exists because "accounts" are not something the module can assume. A
 * setting that is device-wide must say so rather than pretending to be per-account and
 * leaking one account's choice onto another.
 */
enum class AccountSupport {
    /** The setting is device-wide by nature; accounts do not enter into it. */
    NOT_APPLICABLE,

    /** One value per target. Accounts inside the target share it, and the UI says so. */
    TARGET_SCOPED,

    /**
     * Resolved per detected account, so an automated action can never run on the wrong one.
     * The scope hierarchy required for a feature is declared with this value.
     */
    ACCOUNT_AWARE,
}

/**
 * What replaces a feature's injected interface while Stock WhatsApp Mode is on.
 *
 * A fallback that still modifies WhatsApp does not satisfy the Stock Mode contract, so the
 * values here are all outside the hooked app: either the feature simply stops being visible
 * and stays configurable from WA X, or it is reached through an Android surface WhatsApp
 * does not own.
 */
enum class StockModeFallback {
    /** The feature never touches WhatsApp's interface, so nothing has to replace it. */
    NONE_NEEDED,

    /** Its controls already live in the WA X application. */
    MANAGER_ONLY,

    /** Driven by policy the user configured earlier, with no in-app control at all. */
    POLICY_ONLY,

    /** Launched from the Android share sheet. */
    SHARE_SHEET,

    /** Launched from a launcher shortcut. */
    LAUNCHER_SHORTCUT,

    /** Toggled from a Quick Settings tile. */
    QUICK_SETTINGS_TILE,

    /** Reached from a normal Android notification action. */
    NOTIFICATION_ACTION,
}

/**
 * The access tier of a WA X-owned feature.
 *
 * There is exactly one member, and that is the enforcement. "All WA X features are free" is
 * not a convention here that a reviewer has to notice: introducing a Premium, Pro or
 * Supporter tier means editing this enum, which the catalog test, the no-paywall contract
 * audit and the repository scanner all reject. A boolean `isFree` would have been simpler and
 * would also have been the thing a future change silently flips.
 */
enum class FeatureAccessTier {
    /** The only tier. Every feature WA X owns is reachable by every user. */
    FREE,
}

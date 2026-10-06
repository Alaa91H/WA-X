package com.wax.module.stockmode

import com.wax.module.platform.FeatureMetadata
import com.wax.module.platform.StockModeFallback
import com.wax.module.platform.VisualImpact

/**
 * What happens to one feature's interface while Stock WhatsApp Mode is active.
 *
 * There are three answers rather than a boolean because "off" would be wrong for two of them:
 * a feature that never touched WhatsApp is not suppressed, it is simply unaffected, and one
 * whose controls already live in WA X keeps working exactly as before.
 */
enum class PresentationDecision {
    /** WhatsApp renders exactly as the installed official build does. */
    NATIVE_STOCK,

    /** The feature's interface is suppressed, and its fallback surface takes over. */
    SUPPRESSED,

    /** The feature's interface was never inside WhatsApp; nothing changes. */
    EXTERNAL,
}

/**
 * The decision for one feature, with the surface that replaces it.
 *
 * [fallback] is carried even when the decision is [PresentationDecision.NATIVE_STOCK], so the
 * interface can show the user what would happen if they turned Stock Mode on without having to
 * turn it on to find out.
 */
data class PresentationOutcome(
    val featureId: String,
    val decision: PresentationDecision,
    val fallback: StockModeFallback,
    /** The sentence to display, containing no branding and no paid wording. */
    val reason: String,
) {
    /** Whether the feature is allowed to change what the user sees inside WhatsApp. */
    val isVisibleInsideWhatsApp: Boolean get() = decision != PresentationDecision.SUPPRESSED

    /** One line for diagnostics. */
    fun toDisplayLine(): String = "$featureId ${decision.name} (fallback: ${fallback.name})"
}

/**
 * Stock Mode's state, and the stored customisation it is hiding.
 *
 * The contract is "suppressed, not deleted", and the only way to make that a property of the
 * code rather than a promise is to keep both maps in one value: [savedVisualPreferences] is
 * what the user configured, [effectiveVisualPreferences] is what the hooked app is allowed to
 * apply right now. Turning Stock Mode on and off again is then a pure round trip, and no code
 * path can express "the user's customisation is gone".
 */
data class StockModeState(
    /** Whether Stock Mode is active for this target. */
    val enabled: Boolean = false,
    /** Everything the user configured on the visual side, kept verbatim. */
    val savedVisualPreferences: Map<String, Boolean> = emptyMap(),
) {
    /**
     * The visual preferences the hooked app may apply right now.
     *
     * Empty while Stock Mode is on: the honest answer, because "apply nothing visual" is what
     * the contract asks for. Returning the saved map with a flag would leave every call site
     * to remember to check the flag.
     */
    val effectiveVisualPreferences: Map<String, Boolean>
        get() = if (enabled) emptyMap() else savedVisualPreferences

    /** Whether something is currently being hidden rather than removed. */
    val isSuppressingSavedPreferences: Boolean get() = enabled && savedVisualPreferences.isNotEmpty()

    /** The same state with [enabled] changed, keeping every saved preference. */
    fun toggled(enabled: Boolean): StockModeState = copy(enabled = enabled)
}

/**
 * The Stock Mode / zero-visible-modification contract, as a decision procedure.
 *
 * The rule is one line — an impact the user can observe inside WhatsApp is suppressed, and
 * everything else stays — but it has to be applied to every feature and it has to win against
 * the user's own visual customisation, so it is stated once here and read by the interface,
 * the feature loader and the parity tests.
 *
 * Presentation is the one area where Stock Mode has the highest precedence. A user with
 * Distraction-Free Mode, a custom toolbar and hidden tabs who enables Stock Mode sees the
 * official WhatsApp interface, because that is the only reading of "make it look stock" that
 * can be verified. Their preferences are still stored and come back when they leave.
 */
object StockModePolicy {
    /**
     * Decides the presentation for one feature.
     *
     * A feature that cannot be observed inside WhatsApp keeps its decision regardless of the
     * mode: it is either [PresentationDecision.NATIVE_STOCK] because it only changes behaviour
     * or [PresentationDecision.EXTERNAL] because its interface was always elsewhere.
     */
    fun decide(
        metadata: FeatureMetadata,
        stockModeEnabled: Boolean,
    ): PresentationOutcome =
        when {
            metadata.visualImpact == VisualImpact.EXTERNAL_ONLY ->
                PresentationOutcome(
                    featureId = metadata.id,
                    decision = PresentationDecision.EXTERNAL,
                    fallback = metadata.stockModeFallback,
                    reason = "Its controls are outside WhatsApp, so nothing has to change.",
                )

            !metadata.visualImpact.isVisibleInWhatsApp ->
                PresentationOutcome(
                    featureId = metadata.id,
                    decision = PresentationDecision.NATIVE_STOCK,
                    fallback = metadata.stockModeFallback,
                    reason = "It changes behaviour without changing what you see.",
                )

            stockModeEnabled ->
                PresentationOutcome(
                    featureId = metadata.id,
                    decision = PresentationDecision.SUPPRESSED,
                    fallback = metadata.stockModeFallback,
                    reason = "Hidden while Stock WhatsApp Mode is on; ${describeFallback(metadata.stockModeFallback)}.",
                )

            else ->
                PresentationOutcome(
                    featureId = metadata.id,
                    decision = PresentationDecision.NATIVE_STOCK,
                    fallback = metadata.stockModeFallback,
                    reason = "Your own visual customisation is active.",
                )
        }

    /** Decides presentation for every feature. */
    fun decideAll(
        features: List<FeatureMetadata>,
        stockModeEnabled: Boolean,
    ): List<PresentationOutcome> = features.map { decide(it, stockModeEnabled) }

    /**
     * Whether the feature's work still runs inside WhatsApp.
     *
     * A feature whose interface is suppressed may still do its job in the background; only a
     * visual injection stops. That is the "WA X must operate invisibly where technically
     * compatible" half of the contract, and it is why this is a separate question from
     * [PresentationOutcome.isVisibleInsideWhatsApp].
     */
    fun isActiveInWhatsApp(
        metadata: FeatureMetadata,
        stockModeEnabled: Boolean,
    ): Boolean = !stockModeEnabled || metadata.survivesStockMode

    /** The features that keep working while Stock Mode is on. */
    fun activeFeatures(
        features: List<FeatureMetadata>,
        stockModeEnabled: Boolean,
    ): List<FeatureMetadata> = features.filter { isActiveInWhatsApp(it, stockModeEnabled) }

    /** The features whose interface is hidden while Stock Mode is on, with their fallbacks. */
    fun suppressedFeatures(
        features: List<FeatureMetadata>,
        stockModeEnabled: Boolean,
    ): List<PresentationOutcome> = decideAll(features, stockModeEnabled).filter { it.decision == PresentationDecision.SUPPRESSED }

    /** One sentence naming where the user still reaches the control. */
    fun describeFallback(fallback: StockModeFallback): String =
        when (fallback) {
            StockModeFallback.NONE_NEEDED -> "nothing needs to replace it"
            StockModeFallback.MANAGER_ONLY -> "configure it from WA X"
            StockModeFallback.POLICY_ONLY -> "the policy you already configured keeps running"
            StockModeFallback.SHARE_SHEET -> "reach it from the Android share sheet"
            StockModeFallback.LAUNCHER_SHORTCUT -> "reach it from a launcher shortcut"
            StockModeFallback.QUICK_SETTINGS_TILE -> "toggle it from a Quick Settings tile"
            StockModeFallback.NOTIFICATION_ACTION -> "use its notification action"
        }
}

/** One screen whose structure the parity check compares. */
enum class UiSurface(
    val label: String,
) {
    CHAT_LIST("Chat list"),
    CONVERSATION("Conversation"),
    CONTACT_INFO("Contact profile"),
    GROUP_INFO("Group profile"),
    CALLS("Calls"),
    UPDATES("Updates / Status"),
    SETTINGS("Settings"),
    PRIVACY_SETTINGS("Privacy settings"),
    MEDIA_COMPOSER("Media composer"),
    OVERFLOW_MENU("Overflow menus"),
    SEARCH("Search"),
}

/**
 * The structure of one screen: the labels of the items it shows, in order.
 *
 * Only structure, never content. Message text, contact names and timestamps change on every
 * run and would make a parity check fail for reasons that have nothing to do with the module;
 * the labels of menu entries, rows and controls are exactly what WA X is forbidden to add,
 * remove, reorder or restyle.
 */
data class UiSurfaceSnapshot(
    val surface: UiSurface,
    val items: List<String>,
)

/** How an actual screen differed from the official build's. */
enum class ParityDifferenceKind {
    /** A screen the reference has is absent in the build under test. */
    MISSING_SURFACE,

    /** A screen the reference does not have was found. */
    UNEXPECTED_SURFACE,

    /** The build under test shows an item the official build does not. */
    ADDED_ITEM,

    /** An official item is missing. */
    REMOVED_ITEM,

    /** The same items, in a different order. */
    REORDERED_ITEMS,
}

/** One way a screen differs from the official build's. */
data class ParityDifference(
    val surface: UiSurface,
    val kind: ParityDifferenceKind,
    val item: String,
    val detail: String,
) {
    /** One line for the parity report. */
    fun toDisplayLine(): String = "${surface.label}: ${kind.name} — $detail"
}

/** The result of comparing a build against the official reference. */
data class ParityReport(
    val differences: List<ParityDifference>,
) {
    /** Whether the interface is indistinguishable from the official build's, structurally. */
    val isStockIdentical: Boolean get() = differences.isEmpty()

    /** Renders the report for a test failure message or the diagnostics screen. */
    fun describe(): String =
        if (isStockIdentical) {
            "Every checked screen matches the official build."
        } else {
            buildString {
                appendLine("Stock Mode parity: ${differences.size} difference(s)")
                differences.forEach { appendLine("* ${it.toDisplayLine()}") }
            }
        }
}

/**
 * Checks that enabling Stock Mode really did leave WhatsApp stock.
 *
 * Screenshot diffing is the obvious approach and the brittle one: anti-aliasing, fonts and
 * animation frames make it fail for reasons unrelated to the module. Comparing the ordered
 * labels of what each screen exposes is stable across devices and versions, and it catches
 * exactly the four things the contract forbids — an extra item, a missing item, a reordered
 * list and a surface WA X added.
 */
object StockModeParityChecker {
    /** Compares [actual] against the official [reference]. */
    fun compare(
        reference: List<UiSurfaceSnapshot>,
        actual: List<UiSurfaceSnapshot>,
    ): ParityReport {
        val differences = ArrayList<ParityDifference>()
        val actualBySurface = actual.associateBy { it.surface }
        reference.forEach { expected ->
            val found = actualBySurface[expected.surface]
            if (found == null) {
                differences.add(
                    ParityDifference(
                        expected.surface,
                        ParityDifferenceKind.MISSING_SURFACE,
                        expected.surface.label,
                        "the screen was not found",
                    ),
                )
                return@forEach
            }
            differences.addAll(compareItems(expected, found))
        }
        val referenceSurfaces = reference.map { it.surface }.toSet()
        actual
            .filterNot { it.surface in referenceSurfaces }
            .forEach { extra ->
                differences.add(
                    ParityDifference(
                        extra.surface,
                        ParityDifferenceKind.UNEXPECTED_SURFACE,
                        extra.surface.label,
                        "the official build has no such screen",
                    ),
                )
            }
        return ParityReport(differences)
    }

    private fun compareItems(
        expected: UiSurfaceSnapshot,
        found: UiSurfaceSnapshot,
    ): List<ParityDifference> {
        val differences = ArrayList<ParityDifference>()
        val expectedSet = expected.items.toSet()
        val foundSet = found.items.toSet()
        found.items.filterNot { it in expectedSet }.forEach { extra ->
            differences.add(
                ParityDifference(found.surface, ParityDifferenceKind.ADDED_ITEM, extra, "'$extra' is not in the official build"),
            )
        }
        expected.items.filterNot { it in foundSet }.forEach { missing ->
            differences.add(
                ParityDifference(found.surface, ParityDifferenceKind.REMOVED_ITEM, missing, "'$missing' is missing"),
            )
        }
        if (expectedSet == foundSet && expected.items != found.items) {
            differences.add(
                ParityDifference(
                    found.surface,
                    ParityDifferenceKind.REORDERED_ITEMS,
                    found.surface.label,
                    "order changed: expected ${expected.items}, found ${found.items}",
                ),
            )
        }
        return differences
    }

    /** The tokens that must never appear in anything WA X shows inside WhatsApp. */
    @JvmField
    val BRANDING_TOKENS: List<String> =
        listOf(
            "wa x",
            "wa-x",
            "wax ",
            "wa enhancer",
            "waenhancer",
            "lsposed",
            "xposed",
            "module",
        )

    /**
     * Finds branding in labels that are about to be shown inside the hooked application.
     *
     * @return the offending labels, so a test can print them and the diagnostics screen can
     *   show which surface leaked
     */
    fun brandingLeaks(labels: Iterable<String>): List<String> {
        val leaks = ArrayList<String>()
        labels.forEach { label ->
            val normalised = WHITESPACE.replace(label.lowercase(), " ").trim()
            if (BRANDING_TOKENS.any { normalised.contains(it) }) leaks.add(label)
        }
        return leaks
    }

    /** Whether a set of injected labels is free of branding. */
    fun isBrandingFree(labels: Iterable<String>): Boolean = brandingLeaks(labels).isEmpty()

    private val WHITESPACE = Regex("\\s+")
}

package com.wax.module.diagnostics.selftest

import com.wax.module.modern.ModernControlCenterCatalog

/**
 * The per-feature half of the machine-readable inventory (#170).
 *
 * [AtomicCheckInventory] covers the shared chain every feature sits behind:
 * environment, lifecycle, resolvers, preferences. This file covers the part that
 * makes the scan a *feature* scan — one hook check and one trigger check per
 * wired feature, each carrying the resolver chain it actually reads.
 *
 * The dependency edges are the point. A feature that reads contacts depends on
 * `contact_access` → `contact_data_class` → `jid_class` → `jid_raw_string`, so
 * the observed `CONTACT_DATA_CLASS_MISSING → JID_CLASS_UNRESOLVED →
 * JID_ACCESS_UNAVAILABLE` chain blocks exactly the contact-based features and
 * leaves the rest of the scan reportable. That is what lets the clusterer report
 * one root cause instead of a dozen symptoms.
 *
 * A feature that is switched off is `NOT_TESTED`, never a failing hook: nothing
 * about a disabled preference says the hook is broken, and reporting it as a
 * failure is exactly the confusion #170 asks to remove.
 */
object FeatureCheckInventory {
    /**
     * The resolver chain a feature actually reads, by feature id.
     *
     * Only features that genuinely resolve contacts or JIDs are listed. Anything
     * absent here depends on no resolver at all, which is the honest answer for
     * a preference-only or infrastructure feature.
     */
    private val RESOLVER_CHAIN: Map<String, List<String>> =
        mapOf(
            "typing_privacy" to
                listOf(
                    AtomicCheckInventory.CONTACT_CLASS,
                    AtomicCheckInventory.CONTACT_DATA_CLASS,
                    AtomicCheckInventory.JID_CLASS,
                    AtomicCheckInventory.JID_RAW_STRING,
                ),
            "contact_item_listener" to
                listOf(
                    AtomicCheckInventory.CONTACT_CLASS,
                    AtomicCheckInventory.CONTACT_DATA_CLASS,
                ),
            "conversation_item_listener" to
                listOf(
                    AtomicCheckInventory.MESSAGE_CLASS,
                    AtomicCheckInventory.MESSAGE_KEY_CLASS,
                ),
            "message_access" to
                listOf(
                    AtomicCheckInventory.MESSAGE_CLASS,
                    AtomicCheckInventory.MESSAGE_KEY_CLASS,
                ),
            "contact_access" to
                listOf(
                    AtomicCheckInventory.CONTACT_CLASS,
                    AtomicCheckInventory.CONTACT_DATA_CLASS,
                ),
            "jid_access" to
                listOf(
                    AtomicCheckInventory.CONTACT_CLASS,
                    AtomicCheckInventory.CONTACT_DATA_CLASS,
                    AtomicCheckInventory.JID_CLASS,
                    AtomicCheckInventory.JID_RAW_STRING,
                ),
        )

    /**
     * Everyday privacy behaviours whose effect only a second account can see.
     *
     * These are the ones the issue says must never claim `VERIFIED` without an
     * external observer, so their trigger check is marked accordingly and the
     * engine downgrades it to `NEEDS_EXTERNAL_VERIFICATION`.
     */
    private val EXTERNAL_ONLY: Set<String> =
        setOf(
            "typing_privacy",
        )

    /** One catalog entry plus the inventory edges that apply to it. */
    data class Feature(
        val id: String,
        val title: String,
        val preferenceKey: String,
        val resolvers: List<String>,
        val externalConfirmationRequired: Boolean,
        val alwaysOn: Boolean,
    )

    /** Every feature the scan reports on: the wired toggles plus the runtime. */
    fun features(): List<Feature> {
        val wired =
            ModernControlCenterCatalog.wired.map { entry ->
                Feature(
                    id = entry.id,
                    title = entry.label,
                    preferenceKey = entry.preferenceKey,
                    resolvers = RESOLVER_CHAIN[entry.id].orEmpty(),
                    externalConfirmationRequired = entry.id in EXTERNAL_ONLY,
                    alwaysOn = false,
                )
            }
        val alwaysOn =
            ModernControlCenterCatalog.alwaysOn.map { entry ->
                Feature(
                    id = entry.id,
                    title = entry.label,
                    preferenceKey = entry.preferenceKey,
                    resolvers = RESOLVER_CHAIN[entry.id].orEmpty(),
                    externalConfirmationRequired = entry.id in EXTERNAL_ONLY,
                    alwaysOn = true,
                )
            }
        return wired + alwaysOn
    }

    fun featureById(id: String): Feature? = features().firstOrNull { it.id == id }

    /**
     * The hook check for one feature.
     *
     * An always-on row has no preference of its own: it is part of the runtime
     * the others depend on, so it is expected to be installed unconditionally and
     * naming a preference key it does not have would be a lie in the report.
     */
    fun hookCheck(feature: Feature): AtomicCheckInventory.Definition {
        val base =
            AtomicCheckInventory.featureCheck(
                featureId = feature.id,
                title = feature.title,
                resolvers = feature.resolvers,
                preferenceKey = if (feature.alwaysOn) "always-on" else feature.preferenceKey,
                externalConfirmationRequired = false,
            )
        return if (feature.alwaysOn) {
            base.copy(expected = "${feature.title} is installed as runtime infrastructure")
        } else {
            base
        }
    }

    /**
     * The trigger check for one feature.
     *
     * It sits behind the hook check, so a hook that never registered cannot
     * produce a trigger result — the two are separate questions and the export
     * keeps them separate.
     */
    fun triggerCheck(feature: Feature): AtomicCheckInventory.Definition {
        val hookId = AtomicCheckInventory.HOOK_PREFIX + feature.id
        return AtomicCheckInventory.triggerCheck(feature.id, feature.title, hookId).copy(
            externalConfirmationRequired = feature.externalConfirmationRequired,
        )
    }

    /** The full ordered inventory: shared pipeline first, then per-feature. */
    fun full(): List<AtomicCheckInventory.Definition> {
        val perFeature = features().flatMap { listOf(hookCheck(it), triggerCheck(it)) }
        val byId = (AtomicCheckInventory.PIPELINE + perFeature).associateBy { it.id }
        return AtomicCheckInventory.orderWith(perFeature).mapNotNull { byId[it] }
    }
}

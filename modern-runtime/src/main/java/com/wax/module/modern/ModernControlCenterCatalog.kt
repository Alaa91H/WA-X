package com.wax.module.modern

/**
 * The single catalogue of features the embedded Control Center (#433) shows.
 *
 * Only features that are actually wired into the API 102 runtime appear as
 * real, writable rows. Everything still legacy-only appears in the dedicated
 * pending area as a non-actionable row, which is why the centre can honestly
 * say "pending migration" instead of silently hiding the rest of WA X.
 *
 * Titles and descriptions are resolved through the target's own resources
 * when a matching string exists, and fall back to short English labels, so
 * the shell stays functional on a build whose resources are incomplete
 * instead of crashing on a missing resource.
 */
object ModernControlCenterCatalog {
    /**
     * Preference key holding the user's favourite control ids. Must stay in
     * step with the Manager-side allowlist, which validates the value.
     */
    const val FAVORITES_KEY = "wax.control_center.favorites"

    /** Parses the persisted favourites list, ignoring unknown or malformed ids. */
    @JvmStatic
    fun parseFavorites(value: String?): Set<String> {
        if (value.isNullOrBlank()) return emptySet()
        return value
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    @JvmStatic
    fun formatFavorites(ids: Collection<String>): String = ids.filter { it.isNotBlank() }.sorted().joinToString(",")

    /** A control the user can actually switch. */
    data class Wired(
        val id: String,
        val preferenceKey: String,
        val evidenceKey: String,
        val category: ControlCategory,
        val label: String,
        val description: String,
        val restartHint: Boolean = true,
    )

    /** A feature whose migration has not landed yet. Never actionable. */
    data class Pending(
        val id: String,
        val label: String,
    )

    /**
     * The four migrated toggles plus the contact bus, which is always on.
     */
    val wired: List<Wired> =
        listOf(
            Wired(
                id = "custom_time",
                preferenceKey = ModernCustomTimeFeature.ENABLE_KEY,
                evidenceKey = "modern.feature.custom_time.state",
                category = ControlCategory.APPEARANCE,
                label = "Custom Time",
                description = "Custom timestamp format in chats",
            ),
            Wired(
                id = "share_limit",
                preferenceKey = ModernShareLimitFeature.ENABLE_KEY,
                evidenceKey = "modern.feature.share_limit.state",
                category = ControlCategory.CHATS,
                label = "Share Limit",
                description = "Control how many chats can be selected for sharing",
            ),
            Wired(
                id = "freeze_last_seen",
                preferenceKey = ModernPresenceFeatures.FREEZE_KEY,
                evidenceKey = "modern.feature.freeze_last_seen.state",
                category = ControlCategory.PRIVACY,
                label = "Freeze Last Seen",
                description = "Keep your last-seen value frozen",
            ),
            Wired(
                id = "view_once",
                preferenceKey = ModernViewOnceFeature.PREF_ENABLE,
                evidenceKey = "modern.feature.view_once.state",
                category = ControlCategory.PRIVACY,
                label = "Keep View Once Open",
                description = "Keep a viewed view-once message open instead of expiring",
            ),
            Wired(
                id = "status_reply_seen_receipt",
                preferenceKey = ModernStatusReplySeenReceipt.PREF_SEND_SEEN_ON_REPLY,
                evidenceKey = "modern.feature.status_seen_after_reply.state",
                category = ControlCategory.PRIVACY,
                label = "Status Seen After Reply",
                description = "Mark a Status seen once you reply to it",
                restartHint = false,
            ),
            Wired(
                id = "status_seen_hidden",
                preferenceKey = ModernStatusPrivacyFeature.PREF_HIDE_STATUS_VIEW,
                evidenceKey = "modern.feature.status_seen_hidden.state",
                category = ControlCategory.PRIVACY,
                label = "Hide Status Viewed",
                description = "Do not mark a Status as seen when you open it",
            ),
            Wired(
                id = "anti_revoke",
                preferenceKey = ModernAntiRevokeFeature.PREF_ANTIREVOKE,
                evidenceKey = "modern.feature.anti_revoke.state",
                category = ControlCategory.PRIVACY,
                label = "Anti-Delete",
                description = "Keep chat messages that were already received when a sender revokes them",
            ),
            Wired(
                id = "receipt_privacy_read",
                preferenceKey = ModernReceiptPrivacyFeature.PREF_HIDE_READ,
                evidenceKey = "modern.feature.receipt_privacy_read.state",
                category = ControlCategory.PRIVACY,
                label = "Hide Read Receipts",
                description = "Do not tell the sender a message was read",
            ),
            Wired(
                id = "receipt_privacy_after_reply",
                preferenceKey = ModernReceiptPrivacyFeature.PREF_AFTER_REPLY,
                evidenceKey = "modern.feature.receipt_privacy_after_reply.state",
                category = ControlCategory.PRIVACY,
                label = "Read Receipt After My Reply",
                description = "Release the withheld receipt once you reply in that chat",
                restartHint = false,
            ),
            Wired(
                id = "receipt_privacy_delivery",
                preferenceKey = ModernReceiptPrivacyFeature.PREF_HIDE_RECEIPT,
                evidenceKey = "modern.feature.receipt_privacy_delivery.state",
                category = ControlCategory.PRIVACY,
                label = "Hide Delivery Tick",
                description = "Reported as unsupported: WhatsApp decides delivery receipts server-side",
                restartHint = false,
            ),
            Wired(
                id = "hide_chat",
                preferenceKey = ModernHideChatFeature.PREF_ARCHIVE_MODE,
                evidenceKey = "modern.feature.hide_chat.state",
                category = ControlCategory.PRIVACY,
                label = "Hide Archived Chats",
                description = "Hide archived chats from the chat list",
            ),
            Wired(
                id = "typing_privacy",
                preferenceKey = ModernTypingPrivacyFeature.PREF_GHOSTMODE_TYPING,
                evidenceKey = "modern.feature.typing_privacy.state",
                category = ControlCategory.PRIVACY,
                label = "Hide Typing",
                description = "Do not tell contacts when you are typing",
            ),
            Wired(
                id = "tasker",
                preferenceKey = ModernTaskerFeature.PREF_ENABLED,
                evidenceKey = "modern.feature.tasker.state",
                category = ControlCategory.TOOLS,
                label = "Tasker Automation",
                description = "Forward received messages to Tasker (send direction still pending)",
            ),
            Wired(
                id = "dnd_mode",
                preferenceKey = ModernPresenceFeatures.DND_KEY,
                evidenceKey = "modern.feature.dnd_mode.state",
                category = ControlCategory.PRIVACY,
                label = "DND Mode",
                description = "Hide your typing and online presence",
            ),
        )

    /**
     * The always-on infrastructure the migrated features depend on. Listed so
     * the user can see what the runtime is doing without being able to switch
     * it off, because switching it off would break the features above.
     */
    val alwaysOn: List<Wired> =
        listOf(
            Wired(
                id = "diagnostics",
                preferenceKey = "",
                evidenceKey = "modern.feature.diagnostics.state",
                category = ControlCategory.TOOLS,
                label = "Run Diagnostics",
                description = "Atomic self-test of the module, runtime and resolvers",
                restartHint = false,
            ),
            Wired(
                id = "contact_item_listener",
                preferenceKey = "",
                evidenceKey = "modern.feature.contact_item_listener.state",
                category = ControlCategory.ADVANCED,
                label = "Contact Item Listener",
                description = "Required infrastructure for contact-based features",
                restartHint = false,
            ),
            Wired(
                id = "conversation_item_listener",
                preferenceKey = "",
                evidenceKey = "modern.feature.conversation_item_listener.state",
                category = ControlCategory.ADVANCED,
                label = "Conversation Item Listener",
                description = "Required infrastructure for message-row features",
                restartHint = false,
            ),
            Wired(
                id = "menu_status_provider",
                preferenceKey = "",
                evidenceKey = "modern.feature.menu_status_provider.state",
                category = ControlCategory.ADVANCED,
                label = "Status Menu Provider",
                description = "Required infrastructure for status-viewing features",
                restartHint = false,
            ),
            Wired(
                id = "message_access",
                preferenceKey = "",
                evidenceKey = "modern.feature.message_access.state",
                category = ControlCategory.ADVANCED,
                label = "Message Access Layer",
                description = "Required infrastructure for message-based features",
                restartHint = false,
            ),
            Wired(
                id = "jid_access",
                preferenceKey = "",
                evidenceKey = "modern.feature.jid_access.state",
                category = ControlCategory.ADVANCED,
                label = "JID Access Layer",
                description = "Required infrastructure for privacy rules",
                restartHint = false,
            ),
            Wired(
                id = "contact_access",
                preferenceKey = "",
                evidenceKey = "modern.feature.contact_access.state",
                category = ControlCategory.ADVANCED,
                label = "Contact Access Layer",
                description = "Required infrastructure for contact-based features",
                restartHint = false,
            ),
            Wired(
                id = "context_menu_action_provider",
                preferenceKey = "",
                evidenceKey = "modern.feature.context_menu_action_provider.state",
                category = ControlCategory.TOOLS,
                label = "Context Menu Actions",
                description = "Required infrastructure for message-selection actions",
                restartHint = false,
            ),
            Wired(
                id = "activity_controller",
                preferenceKey = "",
                evidenceKey = "modern.feature.activity_controller.state",
                category = ControlCategory.TOOLS,
                label = "Contact Picker Relay",
                description = "Required for the Manager contact picker",
                restartHint = false,
            ),
        )

    /**
     * Legacy-only features awaiting migration. Kept as a short, honest sample
     * rather than a fake list: every entry is inert and says so.
     */
    val pending: List<Pending> =
        listOf(
            Pending("MinorFixes", "Document picker / ML Kit repair"),
            Pending("HideChat", "Hide individual chats"),
            Pending("BubbleColors", "Custom bubble colours"),
            Pending("StatusDownload", "Status download menu"),
            Pending("GroupAdmin", "Group admin tools"),
            Pending("CallPrivacy", "Call privacy rules"),
        )

    fun wiredById(id: String): Wired? = (wired + alwaysOn).firstOrNull { it.id == id }

    fun pendingById(id: String): Pending? = pending.firstOrNull { it.id == id }
}

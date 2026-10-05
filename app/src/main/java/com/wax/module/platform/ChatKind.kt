package com.wax.module.platform

/**
 * Whether a chat is a direct conversation or a group.
 *
 * Shared vocabulary rather than one enum per feature: privacy overrides, notification
 * profiles and future per-chat settings all need the same distinction, and three
 * near-identical enums would eventually disagree about which one a stored key means. The
 * value name is part of the storage format, so entries are append-only.
 */
enum class ChatKind {
    CONTACT,
    GROUP,
}

package com.wax.module.outgoing

import com.wax.module.platform.ChatKind
import com.wax.module.platform.TargetApp

/**
 * The scope chain a policy walks, from least to most specific.
 *
 * It lives next to [PolicyScope] because every policy in the build that supports the
 * Global → Target → Account → List → Chat hierarchy has to walk the same chain in the same
 * order. A second copy in another feature is exactly how one of them ends up resolving a List
 * override before an account override: the order is written down once here and reused, so the
 * precedence the documentation promises and the precedence the code applies are the same list.
 */
object PolicyScopes {
    /**
     * The scopes that apply to one chat, in the order they must be applied.
     *
     * Absent context is simply not part of the chain. A client that does not expose account
     * identity produces no account scope at all, rather than an empty one that every consumer
     * would then have to know to ignore.
     */
    fun forChat(
        app: TargetApp,
        chatId: String,
        kind: ChatKind,
        accountId: String? = null,
        listId: String? = null,
    ): List<PolicyScope> =
        buildList {
            add(PolicyScope.Global)
            add(PolicyScope.Target(app))
            accountId?.let { add(PolicyScope.Account(app, it)) }
            listId?.let { add(PolicyScope.ListScope(app, it)) }
            add(PolicyScope.Chat(app, chatId, kind))
        }
}

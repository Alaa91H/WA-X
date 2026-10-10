package com.wax.module.modern

import java.lang.ref.WeakReference

/** One weakly held Control Center session per target package. */
internal class ControlCenterSessionRegistry<T : Any> {
    private data class Entry<T : Any>(
        val host: WeakReference<Any>,
        val session: WeakReference<T>,
    )

    private val entries = HashMap<String, Entry<T>>()

    @Synchronized
    fun acquire(
        key: String,
        hostActivity: Any,
        canReuse: (T) -> Boolean,
        create: () -> T,
        retire: (T) -> Unit,
    ): T {
        val entry = entries[key]
        val oldHost = entry?.host?.get()
        val oldSession = entry?.session?.get()
        if (oldHost === hostActivity && oldSession != null && canReuse(oldSession)) {
            return oldSession
        }
        if (oldSession != null) retire(oldSession)
        entries.remove(key)
        return create().also { session ->
            entries[key] = Entry(WeakReference(hostActivity), WeakReference(session))
        }
    }

    @Synchronized
    fun release(key: String, session: T) {
        if (entries[key]?.session?.get() === session) entries.remove(key)
    }

    @Synchronized
    fun activeSessionCount(): Int {
        entries.entries.removeAll { it.value.host.get() == null || it.value.session.get() == null }
        return entries.size
    }
}

/** Prevents duplicate Manager launches while allowing retry after a failed launch. */
internal class ActivityFallbackGate {
    private enum class State { PENDING, OPENED }

    private val states = java.util.WeakHashMap<Any, State>()

    @Synchronized
    fun tryBegin(activity: Any): Boolean {
        if (states.containsKey(activity)) return false
        states[activity] = State.PENDING
        return true
    }

    @Synchronized
    fun markOpened(activity: Any) {
        if (states[activity] == State.PENDING) states[activity] = State.OPENED
    }

    @Synchronized
    fun allowRetry(activity: Any) {
        if (states[activity] == State.PENDING) states.remove(activity)
    }

    @Synchronized
    fun release(activity: Any) {
        states.remove(activity)
    }
}

package com.wax.module.graph

import java.util.EnumMap

/**
 * Which target a process is running inside.
 *
 * A value rather than a pair of strings, because the two facts that get confused are the package
 * name and the version: WhatsApp and WhatsApp Business share a version line and differ by package,
 * and a cached preference set from the wrong one is a setting that applies to the wrong app. Any
 * code holding a [TargetIdentity] can therefore say which app it is looking at without asking a
 * global.
 */
data class TargetIdentity(
    val packageName: String,
    val versionName: String?,
    val sdkInt: Int,
    val classLoader: ClassLoader,
) {
    /**
     * The version, with the "this build could not read it" case kept rather than defaulted.
     *
     * Empty would read as a version; [UNKNOWN_VERSION] reads as what it is.
     */
    val versionLabel: String
        get() = versionName ?: UNKNOWN_VERSION

    companion object {
        const val UNKNOWN_VERSION: String = "unknown"
    }
}

/**
 * The well-known pieces of process state the graph holds.
 *
 * A slot, not a typed field, and that is the whole design constraint: the values that matter here
 * are an `Application`, a themed `Context` and a `SharedPreferences`, none of which this file may
 * name without becoming untestable on a JVM. Naming the *slot* keeps the graph pure; the adapter
 * layer supplies and casts.
 *
 * The cast is the price, and it is a cheap one next to what it buys. A slot mismatch surfaces as a
 * [ClassCastException] at the point of use rather than as a wrong value read from a field that was
 * never cleared, and there is exactly one graph per process to get it wrong in.
 */
enum class RuntimeSlot {
    /** The hooked app's own `Application`. Supplied once, at the first framework callback. */
    TARGET_APPLICATION,

    /** A themed `Context` for the module's own resources. Built after the application exists. */
    MODULE_CONTEXT,

    /** The target-scoped preferences. Target-scoped is the point; see [TargetIdentity]. */
    TARGET_PREFERENCES,
}

/**
 * One process's runtime graph: the owner of mutable state that used to have no owner.
 *
 * The problem this type exists for is not "globals are ugly". It is that a `@JvmStatic var` in the
 * injected process has no lifetime, no initialisation order and no way to be released, so in a
 * process that can be re-entered, or that can outlive the thing it holds, state from one life leaks
 * into the next. This type gives that state three things the globals did not have: one owner,
 * attachment that is idempotent and ordered, and a [close] that says what it released.
 *
 * Deliberately not thread-safe beyond what the callers already assume. Attachment happens from
 * bootstrap stages on one thread; [close] happens from teardown. A lock here would imply a
 * concurrency contract the runtime does not actually have, and a lock that is never contended is a
 * lock that hides the next real race.
 */
class RuntimeGraph(
    /**
     * The identity of the target, fixed at construction apart from [recordTargetVersion].
     *
     * A graph cannot change targets. A process is one app, so the graph for it is created knowing
     * which app, and a later attempt to swap that is a programming error rather than a state to
     * absorb.
     */
    initialTarget: TargetIdentity,
) {
    private var identity: TargetIdentity = initialTarget

    /** The identity of the target this graph belongs to. */
    val target: TargetIdentity
        get() = identity
    private var featureContextValue: Any? = null
    private var closed: Boolean = false

    /**
     * Records the target's version once it becomes readable, and never changes it again.
     *
     * A graph is created at the first framework callback, which is before the `PackageManager` has
     * been asked, so at construction the version genuinely is not known. Without this the version
     * would be [TargetIdentity.UNKNOWN_VERSION] for every holder for the life of the process, and a
     * field that is permanently a placeholder is not a field.
     *
     * One-way on purpose: a version that has been read does not become unreadable, and a graph
     * that accepted a second version would be a graph whose identity depends on write order.
     */
    fun recordTargetVersion(versionName: String?) {
        if (identity.versionName != null) return
        if (versionName == null) return
        identity = identity.copy(versionName = versionName)
    }

    private var releaseReport: Released = Released(identity.packageName, emptyList(), 0)
    private val slots: MutableMap<RuntimeSlot, Any> = EnumMap(RuntimeSlot::class.java)
    private val listeners: MutableList<(String) -> Unit> = ArrayList()

    /**
     * Publishes the context handed to contract features.
     *
     * Attached by the loader once the six capabilities exist, and before the first feature runs.
     * The type is erased to [Any] for the same reason the slots are: `FeatureContext` lives in the
     * contract package and this file must not depend on it, so the graph stays usable without it.
     */
    fun attachFeatureContext(context: Any) {
        check(!closed) { "the runtime graph for ${target.packageName} is closed" }
        featureContextValue = context
    }

    /**
     * The published context, or null when no feature has been started yet.
     *
     * Returning null rather than throwing is deliberate: "the graph has no context" is the state of
     * every process between bootstrap and the feature stage, and callers that cannot operate
     * without it should say so themselves, at their own call site, where the context of the failure
     * exists.
     */
    fun featureContext(): Any? = featureContextValue

    /**
     * Puts a value in a slot, replacing whatever was there.
     *
     * Replacing rather than refusing is what the stages need: the module context is rebuilt when
     * the resolver cache comes up, and a graph that refused would force every caller to check.
     */
    fun put(
        slot: RuntimeSlot,
        value: Any,
    ) {
        check(!closed) { "the runtime graph for ${target.packageName} is closed" }
        slots[slot] = value
    }

    /**
     * The value in a slot, or null when nothing has been attached.
     *
     * [T] is reified, and that is not a convenience: with an erased type parameter `value as T` is
     * a cast to `Any` that cannot fail, so a caller reading the wrong type would silently get the
     * wrong object. Reifying makes the check real, and the failure message names both the slot and
     * the process, because "class cast exception" on its own inside a hooked process tells nobody
     * anything.
     */
    inline fun <reified T : Any> get(slot: RuntimeSlot): T? {
        val value = raw(slot) ?: return null
        if (value !is T) {
            throw IllegalStateException(
                "runtime slot ${slot.name} holds a ${value.javaClass.name}, which is not the type " +
                    "its owner put there (process: ${target.packageName} ${target.versionLabel})",
            )
        }
        return value
    }

    /** The value in a slot, or a failure naming the slot. For code that genuinely cannot proceed. */
    inline fun <reified T : Any> require(slot: RuntimeSlot): T =
        get(slot) ?: throw IllegalStateException(
            "runtime slot ${slot.name} is empty in ${target.packageName} ${target.versionLabel}; " +
                "the stage that fills it has not run, or failed",
        )

    /** True when the slot holds something. Lets a stage ask without catching a failure. */
    fun has(slot: RuntimeSlot): Boolean = slots.containsKey(slot)

    /**
     * The value in a slot without a type check.
     *
     * Published for the reified [get] and [require] rather than widening [slots] itself, so the map
     * stays private to this file.
     */
    @PublishedApi
    internal fun raw(slot: RuntimeSlot): Any? = slots[slot]

    /**
     * Registers a callback for when the graph is closed.
     *
     * Listeners exist for the one job a global cannot do: letting a holder of process state learn
     * that the process state has ended. They are called once, in reverse registration order, which
     * is the order that lets a later registration depend on an earlier one still being alive.
     */
    fun onClose(listener: (String) -> Unit) {
        check(!closed) { "the runtime graph for ${target.packageName} is closed" }
        listeners.add(listener)
    }

    /**
     * Ends the graph's life and reports what it released.
     *
     * Returns a [Released] rather than nothing, because "closed" and "closed cleanly" are different
     * facts and the second one is the one worth asserting. A slot that was populated when the graph
     * closed is reported by name; a slot that was empty is not, because nothing leaked.
     *
     * Idempotent. The second call returns the same report instead of throwing, because teardown
     * paths run twice more often than anyone intends, and a crash in cleanup must not hide the
     * failure that triggered it.
     */
    fun close(): Released {
        if (closed) return releaseReport
        closed = true
        val held = slots.keys.map { it.name }.sorted()
        slots.clear()
        featureContextValue = null
        releaseReport = Released(target.packageName, held, listeners.size)
        // Reverse order: a listener registered later may rely on an earlier one still existing.
        for (index in listeners.indices.reversed()) {
            listeners[index](target.packageName)
        }
        listeners.clear()
        return releaseReport
    }

    /** True once [close] has run. */
    val isClosed: Boolean
        get() = closed

    /** What a [close] released. The evidence that the lifetime is real. */
    data class Released(
        val packageName: String,
        val slotsReleased: List<String>,
        val listenersNotified: Int,
    ) {
        /**
         * A line for a log or a test.
         *
         * Says the package, because "released" with no subject is not a fact.
         */
        override fun toString(): String =
            "released ${slotsReleased.size} slot(s) [${slotsReleased.joinToString(", ")}] and " +
                "notified $listenersNotified listener(s) for $packageName"
    }
}

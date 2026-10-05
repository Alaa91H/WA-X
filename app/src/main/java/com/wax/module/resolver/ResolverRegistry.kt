package com.wax.module.resolver

import java.util.Collections

/**
 * What one resolver produced, kept for diagnostics.
 *
 * This is the raw material for T15's user-facing resolver view and for T11's decision
 * auditing: without it, a feature that silently stopped working is indistinguishable from
 * one the user never enabled.
 *
 * @param resolverId the resolver's stable id
 * @param target what was being looked up
 * @param outcome the resolution result
 * @param timestampMillis when it was recorded
 */
data class ResolverRecord(
    val resolverId: String,
    val target: String,
    val outcome: Resolution<*>,
    val timestampMillis: Long
) {

    /** Whether this outcome permits installing a hook. */
    val isInstallable: Boolean get() = outcome.isInstallable

    /** One line suitable for a diagnostics list. */
    fun toDisplayLine(): String =
        "$resolverId -> ${outcome.confidence.name}: ${outcome.reason}"
}

/**
 * Collects resolver outcomes for the current session.
 *
 * Deliberately an in-memory singleton rather than a disk-backed store: T03's
 * [com.wax.module.diagnostics.FailureReportStore] already owns persistence for
 * failures, and a resolver that merely found nothing is not a failure worth writing to
 * storage on every launch.
 */
object ResolverRegistry {

    private val records = Collections.synchronizedList(ArrayList<ResolverRecord>())

    /** Records one outcome. */
    fun record(resolverId: String, target: String, outcome: Resolution<*>, now: Long = System.currentTimeMillis()) {
        records.add(ResolverRecord(resolverId, target, outcome, now))
    }

    /** Everything recorded so far, oldest first. */
    fun all(): List<ResolverRecord> = synchronized(records) { records.toList() }

    /** The latest outcome for [resolverId], or null when it never ran. */
    fun latestFor(resolverId: String): ResolverRecord? =
        synchronized(records) { records.lastOrNull { it.resolverId == resolverId } }

    /** Outcomes that must not be installed: ambiguous, absent or incompatible. */
    fun unusable(): List<ResolverRecord> = all().filter { !it.isInstallable }

    /**
     * A per-resolver summary: the latest outcome and how many times it ran.
     *
     * A resolver that alternates between EXACT and AMBIGUOUS is more interesting than one
     * that is uniformly bad, so the count is kept alongside the last outcome.
     */
    fun summary(): List<ResolverSummary> {
        val byResolver = LinkedHashMap<String, MutableList<ResolverRecord>>()
        for (record in all()) {
            byResolver.getOrPut(record.resolverId) { ArrayList() }.add(record)
        }
        return byResolver.map { (id, history) ->
            val last = history.last()
            ResolverSummary(
                resolverId = id,
                target = last.target,
                confidence = last.outcome.confidence,
                reason = last.outcome.reason,
                attempts = history.size,
                installable = last.isInstallable
            )
        }
    }

    /** Drops all recorded outcomes. Used between tests and on a fresh target load. */
    fun clear() {
        synchronized(records) { records.clear() }
    }
}

/** One resolver's latest state, as shown in diagnostics. */
data class ResolverSummary(
    val resolverId: String,
    val target: String,
    val confidence: Confidence,
    val reason: String,
    val attempts: Int,
    val installable: Boolean
) {
    /** One line suitable for a diagnostics list. */
    fun toDisplayLine(): String =
        "$resolverId [$confidence] $reason (x$attempts)"
}
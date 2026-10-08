package com.wax.module.xposed.graph

import com.wax.module.graph.RuntimeGraph

/**
 * The one global this project now has on purpose.
 *
 * A process-scoped graph needs exactly one entry point, and the temptation is to add a second one
 * whenever the first is awkward to reach. This is that first one, and
 * `tools/quality/check_legacy_global_writes.py` fails when the count of graph holders grows past
 * one, so the cost of adding another is a deliberate act rather than an accident.
 *
 * It lives in the injected layer and not next to [RuntimeGraph], because a holder that a plain JVM
 * test can reach is a holder a plain JVM test can trip over. The graph is testable without this;
 * the wiring that publishes into it is only testable on a device, and pretending otherwise by
 * putting both in the same file would make the first test weaker.
 */
object RuntimeGraphs {
    @Volatile
    private var graph: RuntimeGraph? = null

    /** The graph for this process, or null before bootstrap has attached one. */
    fun current(): RuntimeGraph? = graph

    /**
     * Publishes the process's graph.
     *
     * Returns the graph that is in effect afterwards. A second call for the same target is not an
     * error: bootstrap re-runs in a process that survives a re-attach, and replacing the graph would
     * throw away the slots everything already holds. A call for a *different* target is refused,
     * because a process is one app and silently swapping identities is how WhatsApp state ends up
     * governing WhatsApp Business.
     */
    fun attach(newGraph: RuntimeGraph): RuntimeGraph {
        val existing = graph
        if (existing != null && !existing.isClosed && existing.target.packageName == newGraph.target.packageName) {
            return existing
        }
        graph = newGraph
        return newGraph
    }

    /**
     * Closes and clears the graph, returning what it released.
     *
     * Null when there was nothing to close, which is the normal case for a process that never
     * reached bootstrap and must not be an error at teardown.
     */
    fun close(): RuntimeGraph.Released? {
        val existing = graph ?: return null
        graph = null
        return existing.close()
    }
}

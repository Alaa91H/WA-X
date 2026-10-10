package com.wax.module.diagnostics.selftest

/**
 * One causal chain, not one entry per symptom.
 *
 * The observed device failure
 * `CONTACT_DATA_CLASS_MISSING -> JID_CLASS_UNRESOLVED -> JID_ACCESS_UNAVAILABLE`
 * is one broken resolver chain reported by three different surfaces. Reporting
 * them as three independent problems would send a user hunting three bugs
 * where there is one, so results are clustered by their first failed
 * dependency and everything downstream is attached to it.
 */
object RootCauseClusterer {
    data class Cluster(
        val rootCauseId: String,
        val rootTitle: String,
        val symptomIds: List<String>,
        val affectedFeatures: List<String>,
        val remediation: String,
    )

    /**
     * Groups failed and blocked checks under the first dependency that
     * actually failed. A check with no failed ancestor forms its own cluster.
     */
    fun cluster(results: List<AtomicCheckResult>): List<Cluster> {
        val byId = results.associateBy { it.id }
        val failing = results.filter { it.status == DiagnosticStatus.FAIL }
        val rootIds = linkedSetOf<String>()
        val symptoms = mutableMapOf<String, MutableList<String>>()
        val features = mutableMapOf<String, MutableSet<String>>()

        for (result in results) {
            if (result.status != DiagnosticStatus.FAIL && result.status != DiagnosticStatus.BLOCKED) {
                continue
            }
            val root = firstFailedAncestor(result, byId)
            if (root == null) continue
            rootIds.add(root.id)
            symptoms.getOrPut(root.id) { mutableListOf() }.add(result.id)
            features.getOrPut(root.id) { mutableSetOf() }.addAll(result.dependsOn)
        }
        // A root cause is its own symptom so the cluster is never empty, but a
        // failure that already sits under another failure is a symptom, not a
        // second root: one broken chain must stay one cluster.
        failing.forEach { rootIds.add(it.id) }
        val alreadySymptoms = symptoms.values.flatten().toSet()
        rootIds.removeAll(alreadySymptoms)

        return rootIds.map { rootId ->
            val root = byId.getValue(rootId)
            Cluster(
                rootCauseId = root.id,
                rootTitle = root.title,
                symptomIds = (symptoms[rootId].orEmpty() + rootId).distinct(),
                affectedFeatures = features[rootId].orEmpty().distinct(),
                remediation = root.remediation,
            )
        }.sortedBy { it.rootCauseId }
    }

    /**
     * The earliest failed ancestor of [result], i.e. the root of its chain.
     *
     * It keeps climbing past a failed dependency instead of stopping at it:
     * a JID check that depends on a JID-class check that depends on a missing
     * contact-data class is one broken chain, not two unrelated symptoms.
     */
    private fun firstFailedAncestor(
        result: AtomicCheckResult,
        byId: Map<String, AtomicCheckResult>,
        visiting: MutableSet<String> = mutableSetOf(),
    ): AtomicCheckResult? {
        if (!visiting.add(result.id)) return null
        for (dependencyId in result.dependsOn) {
            val dependency = byId[dependencyId] ?: continue
            // A blocked or not-tested check is still on the chain, so the walk
            // passes through it: the root is the first *failure*, wherever in
            // the chain it sits. Visiting guards against a dependency cycle.
            val upstream = firstFailedAncestor(dependency, byId, visiting)
            val root =
                if (dependency.status == DiagnosticStatus.FAIL) {
                    upstream ?: dependency
                } else {
                    upstream
                }
            if (root != null) return root
        }
        return null
    }

    /**
     * The first failed dependency in issue order, i.e. the one to fix first.
     *
     * Ordered by the registry's own pipeline sequence rather than alphabetically,
     * because "first failed dependency" must mean first *in the chain*.
     */
    fun firstFailedDependency(
        results: List<AtomicCheckResult>,
        orderedIds: List<String>,
    ): AtomicCheckResult? {
        val byId = results.associateBy { it.id }
        return orderedIds.asSequence()
            .mapNotNull { byId[it] }
            .firstOrNull { it.status == DiagnosticStatus.FAIL }
    }
}

/** Counters shown next to a report; never a fabricated health score. */
data class DiagnosticSummary(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val blocked: Int,
    val notTested: Int,
    val unsupported: Int,
    val needsExternalVerification: Int,
    val clusters: List<RootCauseClusterer.Cluster>,
) {
    val inconclusive: Int get() = notTested + needsExternalVerification

    fun toJson(): String =
        buildString {
            append('{')
            append("\"total\":").append(total).append(',')
            append("\"passed\":").append(passed).append(',')
            append("\"failed\":").append(failed).append(',')
            append("\"blocked\":").append(blocked).append(',')
            append("\"not_tested\":").append(notTested).append(',')
            append("\"unsupported\":").append(unsupported).append(',')
            append("\"needs_external_verification\":").append(needsExternalVerification).append(',')
            append("\"inconclusive\":").append(inconclusive).append(',')
            append("\"clusters\":[")
            append(clusters.joinToString(",") { cluster ->
                buildString {
                    append('{')
                    append("\"root_cause\":")
                    appendQuoted(cluster.rootCauseId)
                    append(",\"title\":")
                    appendQuoted(cluster.rootTitle)
                    append(",\"symptoms\":")
                    append(jsonArray(cluster.symptomIds))
                    append(",\"affected_features\":")
                    append(jsonArray(cluster.affectedFeatures))
                    append(",\"remediation\":")
                    appendQuoted(cluster.remediation)
                    append('}')
                }
            })
            append("]}")
        }
        }

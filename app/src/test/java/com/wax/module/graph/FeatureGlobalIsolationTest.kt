package com.wax.module.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A02's strict gate.
 *
 * > No newly migrated feature reads mutable global runtime state directly.
 *
 * The gate is about the *migrated* features, because that is the claim being made: a feature written
 * against [com.wax.module.contract.WaFeature] gets its dependencies from its context and reads
 * nothing ambient. Checking it in a test rather than only in a checker means a violation fails the
 * ordinary unit-test run, which is the run everybody actually watches.
 *
 * The other two rules here are the ones that keep the graph from being the new global:
 *
 * - a feature may not reach the graph either. If it can, "pass your dependencies in" has quietly
 *   become "look them up", and the feature is untestable again.
 * - the graph package may not read a legacy global. It is the replacement; a container that reads
 *   the thing it replaces has changed nothing except where the call site is.
 */
class FeatureGlobalIsolationTest {
    private val sourceRoot: File =
        File("src/main/java/com/wax/module").takeIf { it.isDirectory } ?: File("app/src/main/java/com/wax/module")

    /**
     * The mutable globals, by the name they are written under.
     *
     * The same list the CI checker holds, and it is a list of names rather than of types because the
     * failure this gate exists to prevent is a *read* of one of these, wherever it is written.
     */
    private val legacyGlobals =
        listOf(
            "FeatureLoader.mApp",
            "FeatureLoader.moduleContext",
            "Utils.xprefs",
            "Utils.appClassLoader",
            "Feature.isDebug",
            "ModuleRuntime.mCurrentActivity",
            "ModuleEntryPoint.pref",
            "RuntimeGraphs",
        )

    private fun sourcesUnder(relative: String): List<File> {
        val directory = File(sourceRoot, relative)
        assertTrue("$relative should exist", directory.isDirectory)
        return directory.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    }

    private fun migratableFeatures(): List<File> =
        sourcesUnder("xposed/features").filter { file ->
            val text = file.readText()
            Regex("""\)\s*:\s*WaFeature\b|: WaFeature\b|,\s*WaFeature\b""").containsMatchIn(text)
        }

    @Test
    fun `there is at least one migrated feature to check`() {
        // A gate over an empty set passes forever. If the migration is finished, this fails first
        // and the gate has to be reconsidered on purpose rather than being deleted by accident.
        assertTrue(
            "no WaFeature implementation found under ${sourceRoot.path}; the gate has nothing to check",
            migratableFeatures().isNotEmpty(),
        )
    }

    @Test
    fun `a migrated feature reads no mutable global runtime state`() {
        val offenders = mutableListOf<String>()
        for (feature in migratableFeatures()) {
            for (name in legacyGlobals) {
                val simple = name.substringAfterLast('.')
                val pattern = Regex("""\b${Regex.escape(name)}\b|\b${Regex.escape(simple)}\s*[=.](?!=)""")
                if (pattern.containsMatchIn(stripped(feature))) {
                    offenders += "${feature.name} reads $name"
                }
            }
        }
        assertEquals(
            "A migrated feature reads mutable global state, so it can only be tested on a device: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `a migrated feature does not look the process graph up either`() {
        // Reaching the graph is the same failure wearing a new name: the feature's dependencies stop
        // being arguments and start being whatever the process happens to hold.
        val offenders = migratableFeatures().filter { it.readText().contains("RuntimeGraph") }.map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the graph package reads no legacy global`() {
        val offenders = mutableListOf<String>()
        val graphSources = sourcesUnder("graph") + sourcesUnder("xposed/graph")
        assertTrue("the graph package should exist", graphSources.isNotEmpty())
        for (file in graphSources) {
            for (name in legacyGlobals) {
                if (name == "RuntimeGraphs") {
                    // The holder is the one permitted global in the project, and naming itself in
                    // its own file is the definition rather than a violation.
                    continue
                }
                val simple = name.substringAfterLast('.')
                val pattern = Regex("""\b${Regex.escape(name)}\b|\b${Regex.escape(simple)}\s*[=.](?!=)""")
                if (pattern.containsMatchIn(stripped(file))) {
                    offenders += "${file.name} reads $name"
                }
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the runtime graph names no platform type`() {
        // If this fails, the graph has become untestable off a device, which is the property the
        // whole design exists to keep.
        val forbidden = listOf("android.", "androidx.", "de.robv.android.xposed", "org.luckypray.dexkit")
        val offenders = mutableListOf<String>()
        for (file in sourcesUnder("graph")) {
            val text = stripped(file)
            for (prefix in forbidden) {
                if (text.contains(prefix)) {
                    offenders += "${file.name} names $prefix"
                }
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    private fun stripped(file: File): String =
        file
            .readLines()
            .joinToString("\n") { line ->
                line.substringBefore("//").substringBefore("/*").substringBefore("*")
            }
}

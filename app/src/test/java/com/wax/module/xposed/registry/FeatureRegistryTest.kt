package com.wax.module.xposed.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A03's strict gate.
 *
 * > Exactly one authoritative feature registration source; reflective feature construction = 0.
 *
 * Both halves are asserted here rather than only in a Python checker, so they fail in the unit-test
 * run that already exists. The parity assertions are the interesting ones: this module used to have
 * two registration sources - a hand-maintained `arrayOf(...)` in the loader and the derived list in
 * the compatibility matrix - which happened to agree and were never checked against each other.
 */
class FeatureRegistryTest {
    private fun read(relativePath: String): String =
        listOf("../$relativePath", "$relativePath", "app/$relativePath")
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("$relativePath is not readable from ${File(".").absolutePath}")

    private val entries = RuntimeFeatureRegistry.entries

    @Test
    fun `the registry has features in it`() {
        assertTrue("the registry is empty, so every rule below passes vacuously", entries.isNotEmpty())
    }

    @Test
    fun `every feature id is unique`() {
        // A duplicate id is not a cosmetic problem: the diagnostics dialog, the failure report and
        // the compatibility cell all key on it, so two features sharing one are indistinguishable in
        // every place that matters.
        val duplicates = entries.groupBy { it.featureId }.filterValues { it.size > 1 }.keys
        assertEquals(emptyList<String>(), duplicates.toList())
    }

    @Test
    fun `every feature id is a real class simple name`() {
        // The id is the class's simple name by construction, so a hand-written label cannot drift
        // away from the class it names.
        val source = read("src/main/java/com/wax/module/xposed/registry/RuntimeFeatureRegistry.kt")
        entries.forEach { factory ->
            val referenced = source.contains("${factory.featureId}(") || source.contains(" { ${factory.featureId}()")
            assertTrue(
                "${factory.featureId} is registered but not constructed in the registry file",
                referenced,
            )
        }
    }

    @Test
    fun `every entry carries a factory`() {
        // Each factory is a lambda that names a constructor, so the id being unique plus the source
        // containing the construction together mean no entry can be a placeholder.
        val source = read("src/main/java/com/wax/module/xposed/registry/RuntimeFeatureRegistry.kt")
        val factoryCount = Regex("FeatureFactory\\.(Contract|Legacy)\\(").findAll(source).count()
        assertEquals(entries.size, factoryCount)
    }

    @Test
    fun `the registry and the derived compatibility facts describe the same features`() {
        // This is the duplication this package removes. The compatibility matrix is computed from
        // this list, so a disagreement here means the module installs features the matrix does not
        // know about, or the matrix promises coverage for features that were never installed.
        val derived = derivedFeatureIds()
        assertEquals(derived, entries.map { it.featureId })
    }

    @Test
    fun `the compatibility extractor reads the registry rather than a feature array`() {
        // If the extractor went back to parsing the loader, the two lists would start drifting again
        // with nothing to notice. This asserts the *direction of the dependency*, not just its result.
        val extractor = read("tools/compatibility/extract_features.py")
        assertTrue(
            "extract_features.py must read RuntimeFeatureRegistry.kt",
            extractor.contains("RuntimeFeatureRegistry.kt"),
        )
        assertTrue(
            "extract_features.py must not read the loader's registration path any more",
            !extractor.contains("xposed/core/FeatureLoader.kt"),
        )
    }

    @Test
    fun `no feature is constructed reflectively`() {
        // The three sites this removes were `getConstructor(ClassLoader, SharedPreferences)` and
        // `getDeclaredConstructor()`, each of which fails at runtime with a message naming neither
        // the feature nor the reason.
        val loader = read("src/main/java/com/wax/module/xposed/core/FeatureLoader.kt")
        assertTrue("the loader still builds a feature reflectively", !loader.contains("getConstructor("))
        assertTrue("the loader still builds a feature reflectively", !loader.contains("getDeclaredConstructor()"))
        val registry = read("src/main/java/com/wax/module/xposed/registry/RuntimeFeatureRegistry.kt")
        assertTrue("the registry must not construct reflectively", !registry.contains("::class.java"))
    }

    @Test
    fun `the migrated feature is registered as a contract feature`() {
        // DebugFeature is the one feature written against WaFeature. If its kind were wrong the
        // loader would call a no-arg path on a legacy constructor, which compiles and fails at
        // runtime - so it is asserted by name rather than left to the registry's ordering.
        val debug = entries.single { it.featureId == "DebugFeature" }
        assertTrue("DebugFeature should be a contract feature, was ${debug::class.simpleName}", debug is FeatureFactory.Contract)
        val legacy = entries.filter { it is FeatureFactory.Legacy }
        assertEquals(entries.size - 1, legacy.size)
    }

    private fun derivedFeatureIds(): List<String> {
        val json = read("tools/compatibility/compatibility.json")
        val marker = "\"derived\""
        val derived = json.substringAfter(marker)
        val ids = mutableListOf<String>()
        val pattern = Regex("\"id\":\\s*\"([A-Za-z0-9_]+)\"")
        pattern.findAll(derived).forEach { ids.add(it.groupValues[1]) }
        assertTrue("the compatibility matrix lists no features, which cannot be right", ids.isNotEmpty())
        return ids
    }
}

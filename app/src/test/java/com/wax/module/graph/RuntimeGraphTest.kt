package com.wax.module.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The graph's lifetime, asserted.
 *
 * Everything here runs on a plain JVM because nothing in the graph names a platform type. That is
 * not a stylistic choice: a container whose own semantics can only be checked by booting WhatsApp
 * gets checked by booting WhatsApp, which is to say not at all.
 */
class RuntimeGraphTest {
    private fun graph(
        packageName: String = "com.whatsapp",
        versionName: String? = "2.25.1",
    ) = RuntimeGraph(TargetIdentity(packageName, versionName, 34, this::class.java.classLoader!!))

    @Test
    fun `a new graph holds nothing`() {
        val graph = graph()

        assertNull(graph.featureContext())
        assertNull(graph.get<String>(RuntimeSlot.TARGET_APPLICATION))
        assertFalse(graph.has(RuntimeSlot.MODULE_CONTEXT))
        assertFalse(graph.isClosed)
    }

    @Test
    fun `a slot returns what its owner put there`() {
        val graph = graph()

        graph.put(RuntimeSlot.MODULE_CONTEXT, "a themed context")

        assertEquals("a themed context", graph.get<String>(RuntimeSlot.MODULE_CONTEXT))
        assertTrue(graph.has(RuntimeSlot.MODULE_CONTEXT))
    }

    @Test
    fun `replacing a slot replaces it`() {
        // The module context is genuinely rebuilt when the resolver cache comes up, so a graph that
        // refused the second value would force every caller to check first.
        val graph = graph()

        graph.put(RuntimeSlot.MODULE_CONTEXT, "first")
        graph.put(RuntimeSlot.MODULE_CONTEXT, "second")

        assertEquals("second", graph.get<String>(RuntimeSlot.MODULE_CONTEXT))
    }

    @Test
    fun `a slot read as the wrong type names itself`() {
        val graph = graph()
        graph.put(RuntimeSlot.MODULE_CONTEXT, 42)

        val failure =
            runCatching { graph.get<String>(RuntimeSlot.MODULE_CONTEXT) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        val message = failure?.message.orEmpty()
        assertTrue("the message must name the slot: $message", message.contains("MODULE_CONTEXT"))
        assertTrue("the message must name the process: $message", message.contains("com.whatsapp"))
    }

    @Test
    fun `requiring an empty slot fails with the stage that was missed`() {
        val graph = graph()

        val failure =
            runCatching { graph.require<String>(RuntimeSlot.TARGET_PREFERENCES) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("TARGET_PREFERENCES"))
    }

    @Test
    fun `the version is recorded once and never changes`() {
        // The graph is built at the first framework callback, before the PackageManager has been
        // asked. Without this the version would read "unknown" for the life of the process.
        val graph = graph(versionName = null)

        assertEquals(TargetIdentity.UNKNOWN_VERSION, graph.target.versionLabel)

        graph.recordTargetVersion("2.25.1")

        assertEquals("2.25.1", graph.target.versionName)
        graph.recordTargetVersion("9.9.9")
        assertEquals("2.25.1", graph.target.versionName)
    }

    @Test
    fun `a recorded null version does not overwrite a later one`() {
        val graph = graph(versionName = null)

        graph.recordTargetVersion(null)
        assertEquals(TargetIdentity.UNKNOWN_VERSION, graph.target.versionLabel)

        graph.recordTargetVersion("2.25.1")
        assertEquals("2.25.1", graph.target.versionName)
    }

    @Test
    fun `closing releases every slot and reports which`() {
        val graph = graph()
        graph.put(RuntimeSlot.TARGET_APPLICATION, "the app")
        graph.put(RuntimeSlot.TARGET_PREFERENCES, "the preferences")

        val released = graph.close()

        assertEquals(listOf("TARGET_APPLICATION", "TARGET_PREFERENCES"), released.slotsReleased)
        assertNull(graph.get<String>(RuntimeSlot.TARGET_APPLICATION))
        assertNull(graph.featureContext())
        assertTrue(graph.isClosed)
    }

    @Test
    fun `closing reports nothing for a slot that was never filled`() {
        // "Released" has to mean something, or the release report is decoration: an empty slot never
        // leaked, so naming it would overstate what the close did.
        val graph = graph()
        graph.put(RuntimeSlot.TARGET_APPLICATION, "the app")

        val released = graph.close()

        assertEquals(listOf("TARGET_APPLICATION"), released.slotsReleased)
    }

    @Test
    fun `closing notifies listeners in reverse order and once each`() {
        val graph = graph()
        val order = mutableListOf<String>()
        graph.onClose { order.add("first") }
        graph.onClose { order.add("second") }

        graph.close()
        graph.close()

        assertEquals(listOf("second", "first"), order)
    }

    @Test
    fun `a closed graph refuses new state`() {
        val graph = graph()
        graph.close()

        assertThrowsIllegalState { graph.put(RuntimeSlot.TARGET_APPLICATION, "the app") }
        assertThrowsIllegalState { graph.attachFeatureContext("a context") }
        assertThrowsIllegalState { graph.onClose { } }
    }

    @Test
    fun `two graphs for two packages hold different state`() {
        val whatsApp = graph("com.whatsapp")
        val business = graph("com.whatsapp.w4b")
        whatsApp.put(RuntimeSlot.TARGET_PREFERENCES, "wa settings")
        business.put(RuntimeSlot.TARGET_PREFERENCES, "business settings")

        assertEquals("wa settings", whatsApp.get<String>(RuntimeSlot.TARGET_PREFERENCES))
        assertEquals("business settings", business.get<String>(RuntimeSlot.TARGET_PREFERENCES))
        assertEquals("com.whatsapp", whatsApp.target.packageName)
        assertEquals("com.whatsapp.w4b", business.target.packageName)
    }

    private fun assertThrowsIllegalState(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("expected an IllegalStateException, got $failure", failure is IllegalStateException)
    }
}

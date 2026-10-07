package com.wax.module.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The store's four guarantees, each asserted against a real file.
 *
 * Storage is the part of a diagnostics system that is only exercised in the situations
 * where it matters — a crash mid-write, a corrupt file, two targets writing at once — so it
 * is tested here with real files rather than with a fake.
 */
class RuntimeHealthStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sessions =
        RuntimeSessions(
            bootId = "boot-1",
            moduleSessionId = "module-1",
            targetSessionId = "target-1",
        )

    private fun snapshot(
        packageName: String = "com.whatsapp",
        processName: String = "com.whatsapp",
        state: SubsystemState = SubsystemState.READY,
        timestampMillis: Long = 1_000L,
    ): RuntimeHealthSnapshot =
        RuntimeHealthSnapshot.of(
            sessions = sessions,
            timestampMillis = timestampMillis,
            packageName = packageName,
            processName = processName,
            pid = 4242,
            moduleVersion = "1.2.0-beta.1",
            states = RuntimeSubsystem.independent.associateWith { state },
            overallState = state,
        )

    private fun store(): RuntimeHealthStore = RuntimeHealthStore(folder.newFolder("health"))

    /** The file a store resolves for a target, asserted rather than asserted-about. */
    private fun fileOf(
        store: RuntimeHealthStore,
        targetKey: String,
    ): File = requireNotNull(store.fileFor(targetKey)) { "the store must resolve a file for $targetKey" }

    /** The directory a store writes into. */
    private fun directoryOf(
        store: RuntimeHealthStore,
        targetKey: String,
    ): File = requireNotNull(fileOf(store, targetKey).parentFile) { "a health file always has a parent" }

    /** Where a document that could not be read is moved to. */
    private fun quarantineOf(file: File): File =
        File(requireNotNull(file.parentFile) { "a health file always has a parent" }, file.name + RuntimeHealthStore.QUARANTINE_SUFFIX)

    @Test
    fun aDocumentSurvivesARestart() {
        val first = store()
        val written = snapshot()
        first.write(written, lastKnownGood = written, history = emptyList())

        // A new store over the same directory is what the next process sees.
        val second = RuntimeHealthStore(directoryOf(first, written.targetKey))
        val read = second.read(written.targetKey)

        assertEquals(1_000L, read.current?.timestampMillis)
        assertEquals("com.whatsapp", read.current?.packageName)
        assertEquals(SubsystemState.READY, read.current?.overallState)
        assertEquals(1_000L, read.lastKnownGood?.timestampMillis)
    }

    @Test
    fun theTemporaryFileIsNotLeftBehind() {
        val store = store()
        val written = snapshot()
        store.write(written, lastKnownGood = null, history = emptyList())

        val directory = directoryOf(store, written.targetKey)
        val leftovers = directory.listFiles()?.filter { it.name.endsWith(RuntimeHealthStore.TEMPORARY_SUFFIX) }.orEmpty()
        assertTrue("a completed write must not leave $leftovers", leftovers.isEmpty())
    }

    @Test
    fun oneTargetsDocumentIsNeverAnothers() {
        val store = store()
        val whatsapp = snapshot(packageName = "com.whatsapp")
        val business = snapshot(packageName = "com.whatsapp.w4b", state = SubsystemState.DEGRADED)
        store.write(whatsapp, lastKnownGood = null, history = emptyList())
        store.write(business, lastKnownGood = null, history = emptyList())

        assertEquals(SubsystemState.READY, store.read(whatsapp.targetKey).current?.overallState)
        assertEquals(SubsystemState.DEGRADED, store.read(business.targetKey).current?.overallState)
        assertFalse(
            "the two targets must not share a file",
            fileOf(store, whatsapp.targetKey).name == fileOf(store, business.targetKey).name,
        )
    }

    @Test
    fun historyIsBoundedOnWrite() {
        val store = store()
        val written = snapshot()
        val events =
            (1..RuntimeHealthStore.MAX_HISTORY + 20).map { index ->
                HealthEvent(
                    subsystem = RuntimeSubsystem.DEXKIT,
                    componentId = "stage-$index",
                    status = HealthEventStatus.SUCCESS,
                    startedAtMillis = index.toLong(),
                    durationMillis = 1L,
                    sessions = sessions,
                )
            }
        store.write(written, lastKnownGood = null, history = events)

        val read = store.read(written.targetKey)
        assertEquals(RuntimeHealthStore.MAX_HISTORY, read.history.size)
        assertEquals(
            "the newest events are the ones kept",
            "stage-${RuntimeHealthStore.MAX_HISTORY + 20}",
            read.history.last().componentId,
        )
    }

    @Test
    fun aCorruptDocumentIsQuarantinedOnceAndReadAsEmpty() {
        val store = store()
        val written = snapshot()
        val file = fileOf(store, written.targetKey)
        directoryOf(store, written.targetKey).mkdirs()
        file.writeText("{ this is not json")

        val first = store.read(written.targetKey)
        assertNull(first.current)
        assertTrue(first.history.isEmpty())

        val quarantined = quarantineOf(file)
        assertTrue("the unreadable document must be kept, not deleted", quarantined.exists())
        assertEquals("{ this is not json", quarantined.readText())
        assertFalse("the live file must be out of the way", file.exists())

        // Reading again must not quarantine anything further: the incident is recorded once.
        assertNull(store.read(written.targetKey).current)
        assertTrue(quarantined.exists())
    }

    @Test
    fun aDocumentThatDecodesToNothingIsAlsoQuarantined() {
        val store = store()
        val written = snapshot()
        val file = fileOf(store, written.targetKey)
        directoryOf(store, written.targetKey).mkdirs()
        // Valid JSON, no current snapshot, no history: a shape that can only come from a
        // writer that failed, which is a fault worth keeping rather than treating as empty.
        file.writeText("""{"schema":"wax.m01.runtime-health/1"}""")

        assertNull(store.read(written.targetKey).current)
        assertTrue(quarantineOf(file).exists())
    }

    @Test
    fun aWriteRecreatesTheDocumentAfterQuarantine() {
        val store = store()
        val written = snapshot()
        val file = fileOf(store, written.targetKey)
        directoryOf(store, written.targetKey).mkdirs()
        file.writeText("not json at all")
        assertNull(store.read(written.targetKey).current)

        store.write(written, lastKnownGood = written, history = emptyList())
        val read = store.read(written.targetKey)
        assertNotNull(read.current)
        assertEquals(SubsystemState.READY, read.current?.overallState)
    }

    @Test
    fun aVolatileStoreKeepsStateInMemoryAndWritesNoFile() {
        val store = RuntimeHealthStore(directory = null)
        val written = snapshot()
        store.write(written, lastKnownGood = written, history = emptyList())

        assertNull(store.fileFor(written.targetKey))
        assertEquals(SubsystemState.READY, store.read(written.targetKey).current?.overallState)

        store.clear(written.targetKey)
        assertNull(store.read(written.targetKey).current)
    }

    @Test
    fun clearingRemovesBothTheDocumentAndItsQuarantinedCopy() {
        val store = store()
        val written = snapshot()
        val file = fileOf(store, written.targetKey)
        directoryOf(store, written.targetKey).mkdirs()
        file.writeText("broken")
        store.read(written.targetKey)
        store.clear(written.targetKey)

        assertFalse(file.exists())
        assertFalse(quarantineOf(file).exists())
    }
}

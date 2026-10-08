package com.wax.module.activation

import com.wax.module.health.RuntimeFailureCode
import com.wax.module.health.SubsystemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Manager's filed heartbeats: persistence, isolation, and what happens when a file is bad.
 *
 * Two properties are load-bearing and both are tested here rather than assumed. A record must
 * outlive the process that wrote it, because the age of the record is the only thing that
 * distinguishes "was running" from "is running"; and a bad file must read as *absent* rather
 * than as a parse error, because a diagnostics file that throws on read turns a reporting
 * problem into a crash in the interface that was trying to report it.
 */
class ActivationStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun heartbeat(
        packageName: String = "com.whatsapp",
        processName: String = "com.whatsapp",
        state: SubsystemState = SubsystemState.READY,
        code: RuntimeFailureCode? = null,
        timestamp: Long = 1_000L,
        pid: Int = 100,
    ) = TargetHeartbeat(
        packageName = packageName,
        processName = processName,
        pid = pid,
        bootId = "boot-1",
        moduleSessionId = "module-1",
        targetSessionId = "target-$pid",
        stage = "hooks.essential",
        state = state,
        failureCode = code,
        timestampMillis = timestamp,
        moduleVersion = "1.2.0-beta.3",
    )

    private fun store(): ActivationStore = ActivationStore(temporaryFolder.newFolder("activation"))

    @Test
    fun aHeartbeatIsWrittenAndReadBack() {
        val store = store()
        val original = heartbeat(state = SubsystemState.FAILED, code = RuntimeFailureCode.DEXKIT_INIT_FAILED)

        assertTrue("A write that reports failure is a store that cannot record anything.", store.write(original))

        assertEquals(original, store.read(original.targetKey))
    }

    @Test
    fun aNewerHeartbeatReplacesTheOlderOneForTheSameTarget() {
        val store = store()

        assertTrue(store.write(heartbeat(pid = 100, timestamp = 1_000L)))
        assertTrue(store.write(heartbeat(pid = 200, timestamp = 2_000L)))

        val read = store.read("com.whatsapp|com.whatsapp")
        assertEquals(200, read?.pid)
        assertEquals(2_000L, read?.timestampMillis)
    }

    @Test
    fun twoBuildsAndTwoProcessesNeverShareARecord() {
        val store = store()

        assertTrue(store.write(heartbeat(packageName = "com.whatsapp", processName = "com.whatsapp", pid = 1)))
        assertTrue(store.write(heartbeat(packageName = "com.whatsapp.w4b", processName = "com.whatsapp.w4b", pid = 2)))
        assertTrue(store.write(heartbeat(packageName = "com.whatsapp", processName = "com.whatsapp:business", pid = 3)))

        assertEquals(1, store.read("com.whatsapp|com.whatsapp")?.pid)
        assertEquals(2, store.read("com.whatsapp.w4b|com.whatsapp.w4b")?.pid)
        assertEquals(3, store.read("com.whatsapp|com.whatsapp:business")?.pid)
        assertEquals(3, store.keys().size)
    }

    @Test
    fun nothingFiledReadsAsAbsentRatherThanAsAFailure() {
        val store = store()

        assertNull(store.read("com.whatsapp|com.whatsapp"))
        assertTrue(store.keys().isEmpty())
    }

    @Test
    fun aCorruptDocumentIsQuarantinedAndReadsAsAbsent() {
        val directory = temporaryFolder.newFolder("activation")
        val store = ActivationStore(directory)
        assertTrue(store.write(heartbeat()))

        val file = directory.listFiles()!!.single { it.name.endsWith(ActivationStore.FILE_SUFFIX) }
        file.writeText("{ this is not a heartbeat")

        assertNull(store.read("com.whatsapp|com.whatsapp"))
        assertFalse(
            "The unreadable record is moved aside rather than left in place: a file that cannot " +
                "be read must not keep being handed to every reader.",
            file.exists(),
        )
        assertTrue(
            "Quarantined rather than deleted. It is the only trace of whatever wrote it, and " +
                "deleting it destroys evidence of the fault.",
            directory.listFiles()!!.any { it.name.endsWith(ActivationStore.QUARANTINE_SUFFIX) },
        )
    }

    @Test
    fun noTemporaryFileSurvivesAWrite() {
        val directory = temporaryFolder.newFolder("activation")
        val store = ActivationStore(directory)

        assertTrue(store.write(heartbeat()))

        assertTrue(
            "A half-written temporary file is what a crash during a write would leave behind; a " +
                "later read must not find one and mistake it for a record.",
            directory.listFiles()!!.none { it.name.endsWith(ActivationStore.TEMPORARY_SUFFIX) },
        )
    }

    @Test
    fun aDirectoryThatCannotBeReachedReportsFailureInsteadOfThrowing() {
        // A path whose parent is a regular file, which mkdirs cannot turn into a directory.
        val blocker = temporaryFolder.newFile("blocker")
        val store = ActivationStore(File(blocker, "activation"))

        assertFalse(
            "A diagnostics write must not become a crash in the interface. It also must not be " +
                "silent: a store that quietly stops recording produces exactly the silent " +
                "degradation this package exists to remove, one layer up.",
            store.write(heartbeat()),
        )
        assertNull(store.read("com.whatsapp|com.whatsapp"))
    }

    @Test
    fun clearForgetsEveryRecord() {
        val store = store()
        assertTrue(store.write(heartbeat(pid = 1)))
        assertTrue(store.write(heartbeat(packageName = "com.whatsapp.w4b", processName = "com.whatsapp.w4b", pid = 2)))

        store.clear()

        assertTrue(store.keys().isEmpty())
    }

    /**
     * A key is data, so it must not choose where its record is written.
     *
     * The store keys files by `package|process`, and neither the pipe nor a colon is safe in a
     * file name on every platform this code runs on - Windows rejects the pipe outright, which
     * this test found by running on one. The name is therefore a readable prefix plus a digest
     * of the whole key.
     */
    @Test
    fun aProcessNameCannotChooseWhereTheRecordIsWritten() {
        val store = store()
        val hostile = heartbeat(processName = "com.whatsapp:../../evil", pid = 7)

        assertTrue(store.write(hostile))

        val written =
            temporaryFolder.root
                .walkTopDown()
                .filter { it.isFile }
                .toList()
        assertTrue(
            "A record escaped the directory: ${written.map { it.canonicalPath }}",
            written.all { it.canonicalPath.startsWith(temporaryFolder.root.canonicalPath) },
        )
        assertEquals(7, store.read(hostile.targetKey)?.pid)
    }

    @Test
    fun twoKeysThatReadAlikeDoNotShareARecord() {
        val store = store()

        assertTrue(store.write(heartbeat(processName = "com.whatsapp:a:b", pid = 1)))
        assertTrue(store.write(heartbeat(processName = "com.whatsapp:a|b", pid = 2)))

        assertEquals(1, store.read("com.whatsapp|com.whatsapp:a:b")?.pid)
        assertEquals(2, store.read("com.whatsapp|com.whatsapp:a|b")?.pid)
    }
}

package com.wmods.wppenhacer.storage

import com.wmods.wppenhacer.platform.InMemoryKeyValueStore
import com.wmods.wppenhacer.platform.JsonValue
import com.wmods.wppenhacer.platform.jsonNumber
import com.wmods.wppenhacer.platform.jsonObject
import com.wmods.wppenhacer.platform.jsonString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StorageSecurityTest {
    private lateinit var store: InMemoryKeyValueStore
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        now = 1_700_000_000_000L
    }

    // --- T144: dashboard --------------------------------------------------------------

    @Test
    fun theDashboardMergesDuplicateCategoriesAndKeepsEveryRow() {
        val dashboard =
            StorageDashboard.from(
                listOf(
                    StorageUsage(StorageCategory.CACHE, 1_000, 2),
                    StorageUsage(StorageCategory.CACHE, 500, 1),
                    StorageUsage(StorageCategory.BACKUPS, 8_000, 3),
                ),
            )
        assertEquals(1_500, dashboard.of(StorageCategory.CACHE).bytes)
        assertEquals(3, dashboard.of(StorageCategory.CACHE).fileCount)
        assertEquals(9_500, dashboard.totalBytes)
        assertEquals(StorageCategory.BACKUPS, dashboard.largest!!.category)
        assertEquals(
            "1 KiB",
            formatSize(1_500L),
        )
        assertEquals(StorageCategory.entries.size, dashboard.entries.size)
        assertTrue(dashboard.render().contains("Backups"))
    }

    // --- T145: smart cleanup ----------------------------------------------------------

    @Test
    fun theDefaultPoliciesSelectExactlyWhatTheRoadmapDescribes() {
        val files =
            listOf(
                StorageFile("cache-old", "old.cache", StorageCategory.CACHE, 1_000, 31L * 24 * 60 * 60 * 1000),
                StorageFile("cache-fresh", "fresh.cache", StorageCategory.CACHE, 1_000, 1_000),
                StorageFile("diag-old", "old.log", StorageCategory.DIAGNOSTICS, 2_000, 15L * 24 * 60 * 60 * 1000),
                StorageFile("diag-fresh", "fresh.log", StorageCategory.DIAGNOSTICS, 2_000, 1_000),
                StorageFile("tmp", "partial.download", StorageCategory.DOWNLOADS, 5_000, 10, StorageFileState.FAILED_TEMPORARY),
            )
        val engine = SmartCleanupEngine()
        val plan = engine.plan(files)
        assertEquals(setOf("cache-old", "diag-old", "tmp"), plan.files.map { it.id }.toSet())
        assertEquals(8_000, plan.totalBytes)
        assertTrue(plan.render().contains("Selected files: 3"))
    }

    @Test
    fun aCustomLargerThanPolicySelectsBySize() {
        val engine = SmartCleanupEngine(listOf(CleanupRule.LargerThan(StorageCategory.BACKUPS, 1_000)))
        val plan =
            engine.plan(
                listOf(
                    StorageFile("big", "big.bak", StorageCategory.BACKUPS, 2_000, 0),
                    StorageFile("small", "small.bak", StorageCategory.BACKUPS, 500, 0),
                ),
            )
        assertEquals(listOf("big"), plan.files.map { it.id })
    }

    @Test
    fun executingACleanupReportsFailuresInsteadOfHidingThem() {
        val engine = SmartCleanupEngine(listOf(CleanupRule.FailedTemporaryDownloads))
        val plan =
            engine.plan(
                listOf(
                    StorageFile("a", "a.part", StorageCategory.DOWNLOADS, 100, 0, StorageFileState.FAILED_TEMPORARY),
                    StorageFile("b", "b.part", StorageCategory.DOWNLOADS, 200, 0, StorageFileState.FAILED_TEMPORARY),
                ),
            )
        val result = engine.execute(plan) { id -> id == "a" }
        assertEquals(1, result.deleted)
        assertEquals(100, result.freedBytes)
        assertEquals(listOf("b.part"), result.failed)
        assertTrue(result.toDisplayLine().contains("1 failed"))
    }

    @Test
    fun anEmptyCleanupPlanSaysSo() {
        assertTrue(SmartCleanupEngine().plan(emptyList()).isEmpty)
    }

    // --- T146: duplicates -------------------------------------------------------------

    @Test
    fun theHashDatabaseCachesAndPrunes() {
        val database = LocalHashDatabase(store)
        database.putHash("/files/a", "hash-a")
        database.putHash("/files/b", "hash-b")
        assertEquals("hash-a", database.hashOf("/files/a"))
        assertEquals(2, database.size())
        assertEquals(mapOf("/files/a" to "hash-a", "/files/b" to "hash-b"), database.all())
        assertEquals(1, database.prune { it == "/files/a" })
        assertNull(database.hashOf("/files/b"))
    }

    @Test
    fun duplicateFilesAreGroupedByHashOnly() {
        val files =
            listOf(
                FileRecord("/a", 100, "same"),
                FileRecord("/b", 100, "same"),
                FileRecord("/c", 50, "other"),
                FileRecord("/d", 10, null),
            )
        val groups = DuplicateFileFinder.findDuplicates(files)
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().files.size)
        assertEquals(100, groups.single().reclaimableBytes)
        assertEquals(100, DuplicateFileFinder.reclaimableBytes(groups))
        assertFalse("files without a hash are never guessed at", groups.any { group -> group.files.any { it.path == "/d" } })
    }

    // --- T147: vault ------------------------------------------------------------------

    @Test
    fun theVaultRoundTripsAndRejectsWrongPasswords() {
        val vault = PrivateVault(store)
        val password = "correct horse battery staple".toCharArray()
        assertTrue(vault.store("note-1", "secret text".toByteArray(), password, iterations = 10_000))
        assertEquals("secret text", String(vault.retrieve("note-1", password)!!))
        assertNull(vault.retrieve("note-1", "wrong".toCharArray()))
        assertNull(vault.retrieve("missing", password))
        assertTrue(vault.contains("note-1"))
        assertEquals(listOf("note-1"), vault.ids())
    }

    @Test
    fun tamperingWithCiphertextIsDetected() {
        val vault = PrivateVault(store)
        val password = "pw".toCharArray()
        vault.store("note-1", "secret".toByteArray(), password, iterations = 10_000)
        val key = store.keys().single { it.startsWith(PrivateVault.KEY_PREFIX) }
        val blob =
            java.util.Base64
                .getDecoder()
                .decode(store.getString(key)!!)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        store.putString(
            key,
            java.util.Base64
                .getEncoder()
                .encodeToString(blob),
        )
        assertNull("GCM must reject modified data", vault.retrieve("note-1", password))
    }

    @Test
    fun twoEncryptionsOfTheSameSecretDifferAndOnlyCiphertextIsStored() {
        val first = VaultCrypto.encrypt("same".toByteArray(), "pw".toCharArray(), 10_000)
        val second = VaultCrypto.encrypt("same".toByteArray(), "pw".toCharArray(), 10_000)
        assertFalse("random salt and nonce make repeats indistinguishable", first.contentEquals(second))
        assertTrue(VaultCrypto.isVaultBlob(first))
        val vault = PrivateVault(store)
        vault.store("x", "same".toByteArray(), "pw".toCharArray(), 10_000)
        val stored = store.getString(store.keys().single { it.startsWith(PrivateVault.KEY_PREFIX) })!!
        val decoded =
            java.util.Base64
                .getDecoder()
                .decode(stored)
        assertFalse(String(decoded, Charsets.ISO_8859_1).contains("same"))
    }

    @Test
    fun theVaultRefusesToWeakenItsKdf() {
        val weak =
            runCatching {
                VaultCrypto.encrypt("x".toByteArray(), "pw".toCharArray(), iterations = 100)
            }
        assertTrue(weak.isFailure)
    }

    // --- T148-T150: backups -----------------------------------------------------------

    private fun document(schema: Int = BackupV3Codec.CURRENT_SCHEMA): BackupDocument =
        BackupDocument(
            schemaVersion = schema,
            waeVersion = "2.6.0",
            createdAtMillis = now,
            packageProfile = "com.whatsapp",
            sections =
                mapOf(
                    "settings" to
                        jsonObject(
                            "antirevoke" to jsonNumber(1L),
                            "wae.tasker.token_hash" to jsonString("deadbeef"),
                            "wae.vault.secret" to jsonString("ciphertext"),
                        ),
                ),
        )

    @Test
    fun aBackupRoundTripsWithAllRequiredMetadata() {
        val encoded = BackupV3Codec.encode(document())
        val decoded = BackupV3Codec.decode(encoded) as BackupDecodeResult.Decoded
        assertEquals(3, decoded.document.schemaVersion)
        assertEquals("2.6.0", decoded.document.waeVersion)
        assertEquals(now, decoded.document.createdAtMillis)
        assertEquals("com.whatsapp", decoded.document.packageProfile)
    }

    @Test
    fun aNewerSchemaIsRefusedAtTheDoor() {
        val result = BackupV3Codec.decode(BackupV3Codec.encode(document(schema = 4)))
        assertTrue(result is BackupDecodeResult.Rejected)
        assertTrue((result as BackupDecodeResult.Rejected).reason.contains("Update WaEnhancer"))
    }

    @Test
    fun corruptBackupsAreRejectedNotCrashed() {
        assertTrue(BackupV3Codec.decode("{oops") is BackupDecodeResult.Rejected)
        assertTrue(BackupV3Codec.decode(null) is BackupDecodeResult.Rejected)
    }

    @Test
    fun thePolicyExcludesTokensAndVaultContents() {
        assertTrue(BackupPolicy.isExcluded("wae.tasker.token_hash"))
        assertTrue(BackupPolicy.isExcluded("wae.vault.note"))
        assertFalse(BackupPolicy.isExcluded("antirevoke"))
        val filtered = BackupPolicy.filter(document().sections.getValue("settings").let { (it as JsonValue.Obj).fields })
        assertFalse(filtered.containsKey("wae.tasker.token_hash"))
        assertFalse(filtered.containsKey("wae.vault.secret"))
        assertTrue(filtered.containsKey("antirevoke"))
        assertTrue(BackupPolicy.statement().contains("never included"))
    }

    @Test
    fun anEncryptedBackupRoundTripsAndRejectsWrongPasswords() {
        val bytes = BackupV3Codec.encodeEncrypted(document(), "pw".toCharArray(), iterations = 10_000)
        val decoded = BackupV3Codec.decodeEncrypted(bytes, "pw".toCharArray())
        assertTrue(decoded is BackupDecodeResult.Decoded)
        val wrong = BackupV3Codec.decodeEncrypted(bytes, "nope".toCharArray())
        assertTrue(wrong is BackupDecodeResult.Rejected)
        assertTrue((wrong as BackupDecodeResult.Rejected).reason.contains("password is wrong"))
    }

    @Test
    fun theRestorePlannerClassifiesEveryKey() {
        val planner =
            CrossVersionRestorePlanner(
                knownKeys = setOf("antirevoke"),
                migrations = mapOf("old_theme" to "theme_mode"),
                deprecatedKeys = setOf("legacy_bubble"),
            )
        val plan =
            planner.plan(
                BackupDocument(
                    schemaVersion = 2,
                    waeVersion = "1.9.0",
                    createdAtMillis = now,
                    packageProfile = "com.whatsapp",
                    sections =
                        mapOf(
                            "settings" to
                                jsonObject(
                                    "antirevoke" to jsonNumber(1L),
                                    "old_theme" to jsonString("dark"),
                                    "legacy_bubble" to jsonNumber(0L),
                                    "mystery" to jsonString("?"),
                                ),
                        ),
                ),
            )
        assertEquals(1, plan.counts[RestoreKeyClass.COMPATIBLE])
        assertEquals(1, plan.counts[RestoreKeyClass.MIGRATED])
        assertEquals(1, plan.counts[RestoreKeyClass.DEPRECATED])
        assertEquals(1, plan.counts[RestoreKeyClass.REJECTED])
        assertTrue(plan.schemaSupported)
        assertFalse("an unknown key blocks the restore", plan.isRestorable)
        assertTrue(plan.render().contains("Compatible keys: 1"))
    }

    @Test
    fun migrationsRenameKeysAndRejectedOnesAreDropped() {
        val planner =
            CrossVersionRestorePlanner(
                knownKeys = setOf("antirevoke"),
                migrations = mapOf("old_theme" to "theme_mode"),
            )
        val entries =
            planner.restorableEntries(
                BackupDocument(
                    schemaVersion = 1,
                    waeVersion = "1.6.0",
                    createdAtMillis = now,
                    packageProfile = "com.whatsapp",
                    sections =
                        mapOf(
                            "settings" to
                                jsonObject(
                                    "antirevoke" to jsonNumber(1L),
                                    "old_theme" to jsonString("dark"),
                                    "mystery" to jsonString("?"),
                                ),
                        ),
                ),
            )
        assertEquals(setOf("antirevoke", "theme_mode"), entries.keys)
        assertEquals("dark", (entries["theme_mode"] as JsonValue.Str).value)
    }

    @Test
    fun aNewerSchemaIsNotRestorable() {
        val planner = CrossVersionRestorePlanner(knownKeys = setOf("antirevoke"))
        val plan = planner.plan(document(schema = 9))
        assertFalse(plan.schemaSupported)
        assertFalse(plan.isRestorable)
    }
}

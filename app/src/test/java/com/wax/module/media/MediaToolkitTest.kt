package com.wax.module.media

import com.wax.module.platform.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaToolkitTest {
    private lateinit var store: InMemoryKeyValueStore
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        now = 1_700_000_000_000L
    }

    private fun item(
        id: String,
        kind: MediaKind = MediaKind.IMAGE,
        name: String = "file.jpg",
        size: Long = 1_000,
        chatId: String? = "chat",
    ) = MediaItem(id, kind, chatId, name, size, now)

    // --- T116: media center -----------------------------------------------------------

    @Test
    fun theCatalogCoversEveryMediaKindAndSearchesLocally() {
        val catalog = MediaCatalog(store)
        MediaKind.entries.forEachIndexed { index, kind ->
            assertTrue(catalog.add(item("id-$index", kind, name = "$kind.bin")))
        }
        assertEquals(MediaKind.entries.size, catalog.count())
        assertEquals(1, catalog.byKind(MediaKind.VOICE).size)
        assertTrue(catalog.search("VOICE.bin").isNotEmpty())
        assertFalse(catalog.search("missing").isNotEmpty())
        assertEquals(MediaKind.entries.size, catalog.byChat("chat").size)
        assertTrue(catalog.byChat("other-chat").isEmpty())
    }

    @Test
    fun addingTheSameIdReplacesInsteadOfDuplicating() {
        val catalog = MediaCatalog(store)
        catalog.add(item("id", name = "first.jpg"))
        catalog.add(item("id", name = "second.jpg"))
        assertEquals(1, catalog.count())
        assertEquals("second.jpg", catalog.byId("id")!!.fileName)
    }

    @Test
    fun favoritesRemovalAndSizesAreTracked() {
        val catalog = MediaCatalog(store)
        catalog.add(item("a", size = 100))
        catalog.add(item("b", kind = MediaKind.VIDEO, size = 1_000))
        assertTrue(catalog.setFavorite("a", true))
        assertEquals(1_100, catalog.totalSizeBytes())
        assertEquals(1_000L, catalog.sizeByKind().getValue(MediaKind.VIDEO))
        assertTrue(catalog.remove("a"))
        assertNull(catalog.byId("a"))
        assertEquals(1_000, catalog.totalSizeBytes())
    }

    // --- T117: downloads --------------------------------------------------------------

    @Test
    fun aDownloadMovesThroughTheDefinedStates() {
        val downloads = DownloadManager(store, { now })
        val task = downloads.enqueue("media-1", "https://example.invalid/a", "/tmp/a")!!
        assertEquals(DownloadState.QUEUED, task.state)
        assertTrue(downloads.start(task.id))
        assertTrue(downloads.updateProgress(task.id, 500, 1_000))
        assertEquals(0.5, downloads.task(task.id)!!.progress, 0.001)
        assertTrue(downloads.complete(task.id))
        assertEquals(DownloadState.COMPLETED, downloads.task(task.id)!!.state)
    }

    @Test
    fun invalidTransitionsAreRefused() {
        val downloads = DownloadManager(store, { now })
        val task = downloads.enqueue("media-1", "src", "dst")!!
        assertFalse("cannot complete before starting", downloads.complete(task.id))
        assertTrue(downloads.start(task.id))
        assertFalse("cannot start twice", downloads.start(task.id))
    }

    @Test
    fun aFailedDownloadKeepsItsProgressForASafeResume() {
        val downloads = DownloadManager(store, { now }, maxAttempts = 3)
        val task = downloads.enqueue("media-1", "src", "dst")!!
        downloads.start(task.id)
        downloads.updateProgress(task.id, 800, 2_000)
        assertEquals(DownloadState.RETRYING, downloads.fail(task.id, "connection lost"))
        assertEquals(800, downloads.resumePoint(task.id))

        downloads.start(task.id)
        assertEquals(DownloadState.RETRYING, downloads.fail(task.id, "connection lost"))
        downloads.start(task.id)
        assertEquals(DownloadState.FAILED, downloads.fail(task.id, "connection lost"))
        assertEquals(800, downloads.resumePoint(task.id))
        assertTrue(downloads.retry(task.id))
        assertEquals(800, downloads.resumePoint(task.id))
    }

    @Test
    fun cancellingStopsTheDownloadAndRetryRestartsIt() {
        val downloads = DownloadManager(store, { now })
        val task = downloads.enqueue("media-1", "src", "dst")!!
        downloads.start(task.id)
        assertTrue(downloads.cancel(task.id))
        assertEquals(DownloadState.CANCELLED, downloads.task(task.id)!!.state)
        assertFalse(downloads.cancel(task.id))
        assertTrue(downloads.retry(task.id))
        assertEquals(DownloadState.RETRYING, downloads.task(task.id)!!.state)
    }

    @Test
    fun duplicateActiveDownloadsForTheSameMediaAreRefused() {
        val downloads = DownloadManager(store, { now })
        assertTrue(downloads.enqueue("media-1", "src", "dst") != null)
        assertNull(downloads.enqueue("media-1", "src", "dst"))
    }

    @Test
    fun theQueueSurvivesARecreation() {
        DownloadManager(store, { now }).enqueue("media-1", "src", "dst")
        assertEquals(1, DownloadManager(store, { now }).active().size)
    }

    // --- T118-T120: quality -----------------------------------------------------------

    @Test
    fun everyPresetPassesValidation() {
        MediaQualityPreset.entries.forEach { preset ->
            MediaQualityPresets.image(preset)?.let { image ->
                assertTrue("$preset image: ${image.validate()}", image.validate().isEmpty())
            }
            MediaQualityPresets.video(preset)?.let { video ->
                assertTrue("$preset video: ${video.validate()}", video.validate().isEmpty())
            }
        }
        assertNull("custom must supply its own values", MediaQualityPresets.image(MediaQualityPreset.CUSTOM))
    }

    @Test
    fun outOfRangeControlsAreRejected() {
        assertTrue(ImageQuality(0, 101, keepMetadata = false, format = "jpeg").validate().isNotEmpty())
        assertTrue(ImageQuality(-1, 80, keepMetadata = false, format = "jpeg").validate().isNotEmpty())
        assertTrue(ImageQuality(1000, 80, keepMetadata = false, format = "exe").validate().isNotEmpty())
        assertTrue(VideoQuality(720, 50, 30, "h264").validate().isNotEmpty())
        assertTrue(VideoQuality(720, 2_500, 100, "h264").validate().isNotEmpty())
        assertTrue(VideoQuality(720, 2_500, 30, "vp9").validate().isNotEmpty())
        assertTrue(VideoQuality(720, 2_500, 30, "h264").validate().isEmpty())
    }

    @Test
    fun dataSaverIsSmallerThanHigh() {
        val high = MediaQualityPresets.image(MediaQualityPreset.HIGH)!!
        val saver = MediaQualityPresets.image(MediaQualityPreset.DATA_SAVER)!!
        assertTrue(saver.jpegQuality < high.jpegQuality)
        assertTrue(saver.maxDimension!! < high.maxDimension!!)
    }

    // --- T121: duplicates -------------------------------------------------------------

    @Test
    fun exactDuplicatesAreGroupedAndHashedFilesOnly() {
        val hashes = mapOf("a" to "h1", "b" to "h1", "c" to "h2", "d" to null)
        val groups =
            DuplicateDetector.findDuplicates(
                listOf(item("a", size = 10), item("b", size = 10), item("c", size = 5), item("d", size = 1)),
            ) { hashes[it.id] }
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().items.size)
        assertEquals(10, groups.single().reclaimableBytes)
        assertEquals(10, DuplicateDetector.reclaimableBytes(groups))
    }

    // --- T122: status archive ---------------------------------------------------------

    @Test
    fun archiveModesDecidePerContactDeterministically() {
        assertFalse(StatusArchiveSettings(StatusArchiveMode.MANUAL_ONLY).shouldArchive("a", isFavorite = true))
        assertTrue(StatusArchiveSettings(StatusArchiveMode.FAVORITES).shouldArchive("a", isFavorite = true))
        assertFalse(StatusArchiveSettings(StatusArchiveMode.FAVORITES).shouldArchive("a", isFavorite = false))
        val selected = StatusArchiveSettings(StatusArchiveMode.SELECTED_CONTACTS, setOf("a"))
        assertTrue(selected.shouldArchive("a", isFavorite = false))
        assertFalse(selected.shouldArchive("b", isFavorite = true))
        assertTrue(StatusArchiveSettings(StatusArchiveMode.AUTOMATIC).shouldArchive("b", isFavorite = false))
    }

    // --- T123: cleanup preview --------------------------------------------------------

    @Test
    fun cleanupAlwaysShowsAPreviewAndNeverDeletesProtectedFiles() {
        val candidates =
            listOf(
                CleanupCandidate("1", "old.jpg", 1_000, "cache", now - 10_000),
                CleanupCandidate("2", "new.jpg", 2_000, "cache", now),
                CleanupCandidate("3", "locked.jpg", 4_000, "cache", now - 10_000, isProtected = true),
            )
        val preview = CleanupPlanner.preview(candidates)
        assertEquals(2, preview.fileCount)
        assertEquals(3_000, preview.totalSizeBytes)
        assertEquals(mapOf("cache" to 2), preview.categories)
        val rendered = preview.render()
        assertTrue(rendered.contains("Selected files: 2"))
        assertTrue(rendered.contains("Total size"))

        val deleted = ArrayList<String>()
        val result =
            CleanupRunner.execute(preview) { id ->
                deleted.add(id)
                id != "2"
            }
        assertEquals(listOf("1", "2"), deleted)
        assertEquals(1, result.deleted)
        assertEquals(1_000, result.freedBytes)
        assertEquals(listOf("new.jpg"), result.failed)
        assertFalse("protected files must not be offered", preview.files.any { it.id == "3" })
    }

    @Test
    fun anEmptyCleanupPreviewReportsItselfAsEmpty() {
        assertTrue(CleanupPlanner.preview(emptyList()).isEmpty)
    }
}

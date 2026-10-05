package com.wax.module.history

import com.wax.module.platform.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset

class MessageHistoryTest {
    private lateinit var store: InMemoryKeyValueStore
    private var now = 1_000_000L

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        now = 1_000_000L
    }

    private fun timeline(retention: TimelineRetention = TimelineRetention()) = MessageTimelineStore(store, { now }, retention)

    // --- T86/T87/T88: timeline --------------------------------------------------------

    @Test
    fun originalEditsAndDeletionFormTheTimeline() {
        val store = timeline()
        store.recordOriginal("m1", "chat", "hello")
        now += 60_000
        store.recordEdit("m1", "chat", "hello there")
        now += 60_000
        store.recordEdit("m1", "chat", "hello there!")
        now += 60_000
        store.recordDeletion("m1", "chat")

        val entry = store.entry("m1")!!
        assertEquals(3, entry.versions.count { it.kind != MessageVersionKind.DELETED })
        assertEquals(2, entry.editCount)
        assertEquals("hello", entry.original!!.text)
        assertEquals("hello there!", entry.lastKnownText)
        assertTrue(entry.isDeleted)
        assertTrue(entry.deletedAtMillis != null)
    }

    @Test
    fun theRenderedTimelineNamesEachVersion() {
        val store = timeline()
        store.recordOriginal("m1", "chat", "a")
        now += 60_000
        store.recordEdit("m1", "chat", "b")
        now += 60_000
        store.recordDeletion("m1", "chat")
        val rendered = store.entry("m1")!!.render(ZoneOffset.UTC)
        assertTrue(rendered.contains("Original"))
        assertTrue(rendered.contains("Edited"))
        assertTrue(rendered.contains("Deleted"))
        assertEquals(3, rendered.lines().size)
    }

    @Test
    fun anEditSeenBeforeTheOriginalBecomesTheOriginal() {
        val store = timeline()
        store.recordEdit("m1", "chat", "first observed")
        val entry = store.entry("m1")!!
        assertEquals(MessageVersionKind.ORIGINAL, entry.versions.single().kind)
        assertEquals("first observed", entry.original!!.text)
    }

    @Test
    fun recordingAnOriginalTwiceDoesNotLoseTheFirstObservation() {
        val store = timeline()
        store.recordOriginal("m1", "chat", "true original")
        now += 1_000
        store.recordOriginal("m1", "chat", "later sighting")
        assertEquals("true original", store.entry("m1")!!.original!!.text)
    }

    @Test
    fun aSecondDeletionKeepsTheFirstTimestamp() {
        val store = timeline()
        store.recordOriginal("m1", "chat", "a")
        store.recordDeletion("m1", "chat")
        val first = store.entry("m1")!!.deletedAtMillis
        now += 5_000
        store.recordDeletion("m1", "chat")
        assertEquals(first, store.entry("m1")!!.deletedAtMillis)
    }

    @Test
    fun deletionWithoutAnObservedMessageIsIgnored() {
        assertNull(timeline().recordDeletion("unknown", "chat"))
    }

    @Test
    fun mediaStateIsKeptForMediaVersions() {
        val store = timeline()
        store.recordOriginal("m1", "chat", "caption", mediaState = "photo")
        assertEquals("photo", store.entry("m1")!!.original!!.mediaState)
    }

    @Test
    fun retentionByEntryCountDropsTheOldestFirst() {
        val store = timeline(TimelineRetention(maxEntries = 2))
        store.recordOriginal("old", "chat", "1")
        now += 1_000
        store.recordOriginal("middle", "chat", "2")
        now += 1_000
        store.recordOriginal("newest", "chat", "3")
        assertEquals(2, store.size())
        assertTrue(store.hasTimeline("newest"))
        assertTrue(store.hasTimeline("middle"))
        assertFalse(store.hasTimeline("old"))
    }

    @Test
    fun retentionByAgeDropsStaleEntries() {
        val store = timeline(TimelineRetention(maxEntries = 100, maxAgeMillis = 10_000))
        store.recordOriginal("old", "chat", "1")
        now += 20_000
        store.recordOriginal("new", "chat", "2")
        assertEquals(1, store.size())
        assertFalse(store.hasTimeline("old"))
    }

    @Test
    fun removeAndClearDropOnlyTheRequestedTimeline() {
        val store = timeline()
        store.recordOriginal("a", "chat", "1")
        store.recordOriginal("b", "chat", "2")
        assertTrue(store.remove("a"))
        assertFalse(store.remove("a"))
        assertTrue(store.hasTimeline("b"))
        store.clear()
        assertEquals(0, store.size())
    }

    // --- T89: notes -------------------------------------------------------------------

    @Test
    fun aNoteCanBeSetReplacedAndRemoved() {
        val notes = MessageNoteStore(store, { now })
        val first = notes.setNote("m1", "chat", "Follow up tomorrow")!!
        assertEquals("Follow up tomorrow", first.text)
        now += 1_000
        val replaced = notes.setNote("m1", "chat", "Invoice")!!
        assertEquals("Invoice", replaced.text)
        assertEquals("Follow up tomorrow", first.text)
        assertEquals(first.createdAtMillis, replaced.createdAtMillis)
        assertNull(notes.setNote("m1", "chat", "   "))
        assertFalse(notes.hasNote("m1"))
    }

    @Test
    fun notesPersistAndAreCounted() {
        val first = MessageNoteStore(store, { now })
        first.setNote("m1", "chat", "one")
        first.setNote("m2", "chat", "two")
        val second = MessageNoteStore(store, { now })
        assertEquals(2, second.count())
        assertTrue(second.notes().any { it.text == "one" })
    }

    @Test
    fun aBlankMessageIdCannotHoldANote() {
        val notes = MessageNoteStore(store, { now })
        assertNull(notes.setNote(" ", "chat", "text"))
    }

    // --- T90: bookmarks ---------------------------------------------------------------

    @Test
    fun theFiveBuiltInCollectionsAreAvailable() {
        val bookmarks = BookmarkStore(store, { now })
        assertEquals(
            listOf("Work", "Important", "Later", "Receipts", "Personal"),
            bookmarks.collections().map { it.name },
        )
    }

    @Test
    fun aCustomCollectionCanBeCreatedRenamedAndDeletedWhenEmpty() {
        val bookmarks = BookmarkStore(store, { now })
        val created = bookmarks.createCollection("Travel") as CollectionResult.Success
        assertFalse(created.collection.builtIn)
        assertTrue(bookmarks.renameCollection(created.collection.id, "Trips") is CollectionResult.Success)
        assertTrue(bookmarks.deleteCollection(created.collection.id) is CollectionResult.Success)
    }

    @Test
    fun aNonEmptyCollectionCannotBeDeleted() {
        val bookmarks = BookmarkStore(store, { now })
        val created = (bookmarks.createCollection("Travel") as CollectionResult.Success).collection
        bookmarks.addBookmark("m1", "chat", created.id)
        val result = bookmarks.deleteCollection(created.id)
        assertTrue(result is CollectionResult.Rejected)
        assertTrue((result as CollectionResult.Rejected).message.contains("still holds"))
    }

    @Test
    fun builtInCollectionsCannotBeDeleted() {
        val bookmarks = BookmarkStore(store, { now })
        assertTrue(bookmarks.deleteCollection(BuiltInCollections.WORK) is CollectionResult.Rejected)
    }

    @Test
    fun duplicateCollectionNamesAreRejected() {
        val bookmarks = BookmarkStore(store, { now })
        assertTrue(bookmarks.createCollection("later") is CollectionResult.Rejected)
    }

    @Test
    fun bookmarkingIsIdempotentPerCollection() {
        val bookmarks = BookmarkStore(store, { now })
        assertTrue(bookmarks.addBookmark("m1", "chat", BuiltInCollections.IMPORTANT))
        assertFalse("adding twice is a no-op", bookmarks.addBookmark("m1", "chat", BuiltInCollections.IMPORTANT))
        assertTrue(bookmarks.addBookmark("m1", "chat", BuiltInCollections.RECEIPTS))
        assertEquals(2, bookmarks.collectionsFor("m1").size)
        assertEquals(1, bookmarks.counts()[BuiltInCollections.IMPORTANT])
        assertTrue(bookmarks.removeBookmark("m1", BuiltInCollections.RECEIPTS))
        assertFalse(bookmarks.removeBookmark("m1", BuiltInCollections.RECEIPTS))
    }

    @Test
    fun bookmarkingIntoAnUnknownCollectionIsRefused() {
        val bookmarks = BookmarkStore(store, { now })
        assertFalse(bookmarks.addBookmark("m1", "chat", "missing"))
    }

    // --- T95: context actions ---------------------------------------------------------

    @Test
    fun otpActionsAppearOnlyForCodes() {
        val withOtp =
            ContextActions
                .forCapabilities(
                    setOf(ContextCapability.TEXT, ContextCapability.OTP),
                ).map { it.id }
        assertTrue(withOtp.contains(ContextActions.COPY_OTP))
        val withoutOtp = ContextActions.forCapabilities(setOf(ContextCapability.TEXT)).map { it.id }
        assertFalse(withoutOtp.contains(ContextActions.COPY_OTP))
    }

    @Test
    fun mediaAndSenderActionsRequireTheirCapabilities() {
        assertEquals(
            listOf(ContextActions.SAVE_MEDIA),
            ContextActions.forCapabilities(setOf(ContextCapability.MEDIA)).map { it.id },
        )
        assertEquals(
            listOf(ContextActions.SEARCH_SENDER),
            ContextActions.forCapabilities(setOf(ContextCapability.SENDER_HISTORY)).map { it.id },
        )
    }

    @Test
    fun theMenuOrderIsStable() {
        val all = ContextActions.forCapabilities(ContextCapability.entries.toSet()).map { it.id }
        assertEquals(
            listOf(
                ContextActions.TRANSLATE,
                ContextActions.COPY_OTP,
                ContextActions.ADD_NOTE,
                ContextActions.BOOKMARK,
                ContextActions.SCHEDULE_REPLY,
                ContextActions.SEARCH_SENDER,
                ContextActions.SAVE_MEDIA,
            ),
            all,
        )
    }

    @Test
    fun aPlainMessageStillOffersTheCoreActions() {
        val ids = ContextActions.forCapabilities(setOf(ContextCapability.TEXT)).map { it.id }
        assertEquals(
            listOf(ContextActions.TRANSLATE, ContextActions.ADD_NOTE, ContextActions.BOOKMARK, ContextActions.SCHEDULE_REPLY),
            ids,
        )
    }
}

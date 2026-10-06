package com.wax.module.outgoing

import com.wax.module.platform.InMemoryKeyValueStore
import com.wax.module.platform.TargetApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The durable queue behind Timed Auto Delete for Everyone.
 *
 * What is being pinned down is the set of properties that make remote deletion safe to promise:
 * a job only exists for a message that was actually acknowledged, one message never gets two
 * jobs, every failure has a bounded and recorded outcome, and nothing about one target's queue
 * is reachable from the other's.
 */
class MessageRevocationQueueTest {
    private val wa = TargetApp.WHATSAPP
    private val business = TargetApp.WHATSAPP_BUSINESS

    private lateinit var store: InMemoryKeyValueStore
    private lateinit var queue: MessageRevocationQueue

    private var clock: Long = BASE_TIME

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        clock = BASE_TIME
        queue = MessageRevocationQueue(store, now = { clock })
    }

    private fun queueWith(limits: RevocationLimits): MessageRevocationQueue =
        MessageRevocationQueue(store, now = { clock }, limits = limits)

    private fun scheduling(
        delayMillis: Long = FIVE_MINUTES,
        source: PolicyScope = PolicyScope.Global,
    ): AutoDeleteDecision =
        AutoDeleteDecision(
            choice = AutoDeleteChoice.ENABLED,
            source = source,
            delayMillis = delayMillis,
            schedule = true,
            block = null,
            fallback = ExpiredWindowFallback.DO_NOTHING,
            explanation = "Delete for everyone after ${AutoDeleteDurations.label(delayMillis)}.",
        )

    private fun blockedAutoDelete(block: AutoDeleteBlockReason = AutoDeleteBlockReason.REVOKE_WINDOW_UNKNOWN): AutoDeleteDecision =
        AutoDeleteDecision(
            choice = AutoDeleteChoice.ENABLED,
            source = PolicyScope.Global,
            delayMillis = FIVE_MINUTES,
            schedule = false,
            block = block,
            fallback = ExpiredWindowFallback.DO_NOTHING,
            explanation = block.explanation,
        )

    private fun request(
        chatId: String = "chat-1",
        messageClass: OutgoingMessageClass = OutgoingMessageClass.PHOTO,
        app: TargetApp = wa,
        accountId: String? = null,
    ): RevocationRequest = RevocationRequest(target = app, chatId = chatId, messageClass = messageClass, accountId = accountId)

    // --- when a job is created ----------------------------------------------------------

    @Test
    fun theCountdownStartsFromTheAcknowledgementNotFromTheSendAction() {
        val result = queue.onSendCompleted(request(), scheduling(), SendOutcome.Sent("m1", clock))
        assertTrue(result.isScheduled)
        val job = (result as EnqueueResult.Scheduled).job
        assertEquals(clock, job.sentAt)
        assertEquals(clock + FIVE_MINUTES, job.deleteAt)
        assertEquals(RevocationState.PENDING, job.state)
        assertEquals(1, queue.active(wa).size)
    }

    @Test
    fun aFailedSendNeverSchedulesADeletion() {
        val result = queue.onSendCompleted(request(), scheduling(), SendOutcome.Failed)
        assertEquals(EnqueueResult.NotRequired(null), result)
        assertTrue(queue.active(wa).isEmpty())
        assertTrue(queue.allJobs().isEmpty())
    }

    @Test
    fun aSendCancelledBeforeItHappenedNeverSchedulesADeletion() {
        val result = queue.onSendCompleted(request(), scheduling(), SendOutcome.CancelledBeforeSend)
        assertEquals(EnqueueResult.NotRequired(null), result)
        assertTrue(queue.allJobs().isEmpty())
    }

    @Test
    fun aSendWithoutAStableIdentityIsRecordedRatherThanGuessedAt() {
        val result = queue.onSendCompleted(request(), scheduling(), SendOutcome.SentWithoutIdentity(clock))
        assertEquals(EnqueueResult.IdentityUnavailable, result)
        assertTrue(queue.allJobs().isEmpty())
    }

    @Test
    fun aPolicyThatDoesNotScheduleCreatesNothingAndSaysWhy() {
        val result = queue.onSendCompleted(request(), blockedAutoDelete(), SendOutcome.Sent("m1", clock))
        assertEquals(EnqueueResult.NotRequired(AutoDeleteBlockReason.REVOKE_WINDOW_UNKNOWN), result)
        assertTrue(queue.allJobs().isEmpty())
    }

    @Test
    fun ablankIdentityIsRefusedInsteadOfAddressingTheWrongMessage() {
        assertEquals(EnqueueResult.IdentityUnavailable, queue.enqueue(request(), scheduling(), "", clock))
        assertEquals(EnqueueResult.IdentityUnavailable, queue.enqueue(request(chatId = ""), scheduling(), "m1", clock))
    }

    // --- one message, one job -----------------------------------------------------------

    @Test
    fun aSecondJobForTheSameMessageIsRefusedAndTheOriginalDeadlineStands() {
        val first = queue.enqueue(request(), scheduling(), "m1", clock) as EnqueueResult.Scheduled
        val second = queue.enqueue(request(), scheduling(delayMillis = AutoDeleteDurations.HOUR), "m1", clock + 1_000)
        assertEquals(EnqueueResult.DuplicateId, second)
        val jobs = queue.active(wa)
        assertEquals(1, jobs.size)
        assertEquals(first.job.deleteAt, jobs.single().deleteAt)
    }

    @Test
    fun anEditedMessageKeepsItsOriginalDeadline() {
        queue.enqueue(request(), scheduling(), "m1", clock)
        queue.enqueue(request(), scheduling(delayMillis = AutoDeleteDurations.HOUR), "m1", clock + 60_000)
        assertEquals(clock + FIVE_MINUTES, queue.active(wa).single().deleteAt)
    }

    @Test
    fun theSameMessageOnTwoTargetsGetsOneJobEach() {
        assertTrue(queue.enqueue(request(app = wa), scheduling(), "m1", clock).isScheduled)
        assertTrue(queue.enqueue(request(app = business), scheduling(), "m1", clock).isScheduled)
        assertEquals(1, queue.active(wa).size)
        assertEquals(1, queue.active(business).size)
    }

    // --- manual deletion -----------------------------------------------------------------

    @Test
    fun manualDeletionCancelsThePendingJob() {
        queue.enqueue(request(), scheduling(), "m1", clock)
        assertTrue(queue.cancel(wa, null, "chat-1", "m1"))
        assertTrue(queue.active(wa).isEmpty())
        assertEquals(RevocationState.CANCELLED, queue.history(wa).single().state)
    }

    @Test
    fun cancellingSomethingTheQueueDoesNotHoldIsHarmless() {
        assertFalse(queue.cancel(wa, null, "chat-1", "never-seen"))
    }

    @Test
    fun aFinishedJobIsNotRewrittenByALaterManualDeletion() {
        val id = (queue.enqueue(request(), scheduling(), "m1", clock) as EnqueueResult.Scheduled).job.key.id
        queue.recordAttempt(id, RevocationAttemptResult.DeletedForEveryone)
        assertFalse(queue.cancel(wa, null, "chat-1", "m1"))
        assertEquals(RevocationState.DELETED_FOR_EVERYONE, queue.history(wa).single().state)
    }

    // --- deadlines ----------------------------------------------------------------------

    @Test
    fun aJobIsOnlyDueWhenItsDeadlineHasArrived() {
        queue.enqueue(request(), scheduling(), "m1", clock)
        assertTrue(queue.due(wa, clock).isEmpty())
        assertTrue(queue.due(wa, clock + FIVE_MINUTES - 1).isEmpty())
        assertEquals(1, queue.due(wa, clock + FIVE_MINUTES).size)
    }

    @Test
    fun dueJobsComeBackInDeadlineOrder() {
        queue.enqueue(request(), scheduling(delayMillis = FIVE_MINUTES), "late", clock)
        queue.enqueue(request(), scheduling(delayMillis = AutoDeleteDurations.MINUTE), "early", clock)
        val due = queue.due(wa, clock + FIVE_MINUTES)
        assertEquals(listOf("early", "late"), due.map { it.key.messageId })
        assertEquals("early", queue.nextDue(wa)!!.key.messageId)
    }

    @Test
    fun nextDueIsNullWhenNothingIsPending() {
        assertNull(queue.nextDue(wa))
    }

    // --- outcomes -----------------------------------------------------------------------

    @Test
    fun onlyTheOutcomesTheClientConfirmedAreReportedAsSuccess() {
        val id = (queue.enqueue(request(), scheduling(), "m1", clock) as EnqueueResult.Scheduled).job.key.id
        val recorded = queue.recordAttempt(id, RevocationAttemptResult.DeletedForEveryone)!!
        assertEquals(RevocationState.DELETED_FOR_EVERYONE, recorded.state)
        assertTrue(recorded.state.isSuccessful)
        assertTrue(recorded.isTerminal)
    }

    @Test
    fun anAlreadyDeletedMessageCountsAsDeleted() {
        val id = (queue.enqueue(request(), scheduling(), "m1", clock) as EnqueueResult.Scheduled).job.key.id
        val recorded = queue.recordAttempt(id, RevocationAttemptResult.AlreadyDeleted)!!
        assertEquals(RevocationState.ALREADY_DELETED, recorded.state)
        assertTrue(recorded.state.isSuccessful)
    }

    @Test
    fun anExpiredWindowIsNeverReportedAsSuccess() {
        val id = (queue.enqueue(request(), scheduling(), "m1", clock) as EnqueueResult.Scheduled).job.key.id
        val recorded = queue.recordWindowExpired(id)!!
        assertEquals(RevocationState.REVOKE_WINDOW_EXPIRED, recorded.state)
        assertFalse(recorded.state.isSuccessful)
    }

    @Test
    fun anUnknownResultIsTerminalAndNotASuccess() {
        val id = (queue.enqueue(request(), scheduling(), "m1", clock) as EnqueueResult.Scheduled).job.key.id
        val recorded = queue.recordAttempt(id, RevocationAttemptResult.Unknown)!!
        assertEquals(RevocationState.UNKNOWN_RESULT, recorded.state)
        assertFalse(recorded.state.isSuccessful)
    }

    @Test
    fun targetAndVersionProblemsAreRecordedAsTheirOwnStates() {
        val first = (queue.enqueue(request(), scheduling(), "m1", clock) as EnqueueResult.Scheduled).job.key.id
        assertEquals(RevocationState.TARGET_UNAVAILABLE, queue.recordAttempt(first, RevocationAttemptResult.TargetUnavailable)!!.state)
        val second = (queue.enqueue(request(), scheduling(), "m2", clock) as EnqueueResult.Scheduled).job.key.id
        assertEquals(RevocationState.UNSUPPORTED_VERSION, queue.recordAttempt(second, RevocationAttemptResult.UnsupportedVersion)!!.state)
        val third = (queue.enqueue(request(), scheduling(), "m3", clock) as EnqueueResult.Scheduled).job.key.id
        assertEquals(RevocationState.IDENTITY_UNAVAILABLE, queue.recordAttempt(third, RevocationAttemptResult.IdentityUnavailable)!!.state)
    }

    @Test
    fun aFailedAttemptConsumesBudgetAndPushesTheDeadlineOut() {
        val id = (queue.enqueue(request(), scheduling(), "m1", clock) as EnqueueResult.Scheduled).job.key.id
        val retried = queue.recordAttempt(id, RevocationAttemptResult.Failed("transient"), clock + 1_000)!!
        assertEquals(RevocationState.PENDING, retried.state)
        assertEquals(1, retried.attemptCount)
        assertEquals(clock + 1_000 + queue.backoffMillis(1), retried.deleteAt)
        assertEquals("transient", retried.failureReason)
    }

    @Test
    fun aJobStopsAfterItsAttemptBudgetIsSpent() {
        val id = (queue.enqueue(request(), scheduling(), "m1", clock) as EnqueueResult.Scheduled).job.key.id
        var last = queue.recordAttempt(id, RevocationAttemptResult.Failed("no"), clock)!!
        repeat(RevocationLimits().maxAttempts - 1) {
            last = queue.recordAttempt(id, RevocationAttemptResult.Failed("no"), clock)!!
        }
        assertEquals(RevocationState.REQUEST_FAILED, last.state)
        assertTrue(last.isTerminal)
        assertEquals(RevocationLimits().maxAttempts, last.attemptCount)
    }

    @Test
    fun aFinishedJobIsNotRewrittenByALaterAttemptResult() {
        val id = (queue.enqueue(request(), scheduling(), "m1", clock) as EnqueueResult.Scheduled).job.key.id
        queue.recordAttempt(id, RevocationAttemptResult.DeletedForEveryone)
        val after = queue.recordAttempt(id, RevocationAttemptResult.Failed("late arrival"))!!
        assertEquals(RevocationState.DELETED_FOR_EVERYONE, after.state)
    }

    @Test
    fun anAttemptForAnUnknownJobChangesNothing() {
        assertNull(queue.recordAttempt("does-not-exist", RevocationAttemptResult.DeletedForEveryone))
    }

    @Test
    fun backoffGrowsAndIsCapped() {
        assertEquals(0L, queue.backoffMillis(0))
        assertEquals(MessageRevocationQueue.BASE_BACKOFF_MILLIS, queue.backoffMillis(1))
        assertEquals(MessageRevocationQueue.BASE_BACKOFF_MILLIS * 2, queue.backoffMillis(2))
        assertEquals(MessageRevocationQueue.MAX_BACKOFF_MILLIS, queue.backoffMillis(20))
    }

    // --- safety limits ------------------------------------------------------------------

    @Test
    fun thePerChatSafetyLimitStopsARunawayButNotOtherChats() {
        val limited = queueWith(RevocationLimits(perChatPerWindow = 2, perTargetPerWindow = 100))
        assertTrue(limited.enqueue(request(), scheduling(), "m1", clock).isScheduled)
        assertTrue(limited.enqueue(request(), scheduling(), "m2", clock).isScheduled)
        assertEquals(EnqueueResult.ChatRateLimited, limited.enqueue(request(), scheduling(), "m3", clock))
        assertTrue(limited.enqueue(request(chatId = "chat-2"), scheduling(), "m1", clock).isScheduled)
    }

    @Test
    fun thePerTargetSafetyLimitStopsARunawayAcrossChats() {
        val limited = queueWith(RevocationLimits(perChatPerWindow = 100, perTargetPerWindow = 3))
        repeat(3) { index -> assertTrue(limited.enqueue(request(chatId = "chat-$index"), scheduling(), "m1", clock).isScheduled) }
        assertEquals(EnqueueResult.TargetRateLimited, limited.enqueue(request(chatId = "chat-9"), scheduling(), "m1", clock))
        assertTrue(limited.enqueue(request(chatId = "chat-9", app = business), scheduling(), "m1", clock).isScheduled)
    }

    @Test
    fun theSafetyLimitWindowExpires() {
        val limited = queueWith(RevocationLimits(perChatPerWindow = 1, perTargetPerWindow = 100))
        assertTrue(limited.enqueue(request(), scheduling(), "m1", clock).isScheduled)
        assertEquals(EnqueueResult.ChatRateLimited, limited.enqueue(request(), scheduling(), "m2", clock))
        clock += 60 * 60 * 1000L
        assertTrue(limited.enqueue(request(), scheduling(), "m3", clock).isScheduled)
    }

    // --- isolation ----------------------------------------------------------------------

    @Test
    fun oneTargetsQueueIsNeverWalkedByTheOthersQuery() {
        queue.enqueue(request(app = wa), scheduling(), "m1", clock)
        queue.enqueue(request(app = business), scheduling(), "m2", clock)
        assertEquals(listOf(wa), queue.active(wa).map { it.target }.distinct())
        assertEquals(listOf("m1"), queue.due(wa, clock + FIVE_MINUTES).map { it.key.messageId })
        assertEquals(listOf("m2"), queue.due(business, clock + FIVE_MINUTES).map { it.key.messageId })
    }

    @Test
    fun twoAccountsInOneTargetKeepSeparateJobs() {
        queue.enqueue(request(accountId = "1"), scheduling(), "m1", clock)
        queue.enqueue(request(accountId = "2"), scheduling(), "m1", clock)
        assertEquals(2, queue.active(wa).size)
    }

    @Test
    fun aStoredEntryWhoseKeyDoesNotMatchItsContentsIsIgnored() {
        val job =
            PendingMessageRevocation(
                key = RevocationKey(wa, null, "chat-1", "m1"),
                messageClass = OutgoingMessageClass.PHOTO,
                sentAt = clock,
                deleteAt = clock + FIVE_MINUTES,
                policySource = PolicyScope.Global,
            )
        store.putString(MessageRevocationQueue.JOB_PREFIX + "hand-edited", RevocationCodec.encodeJob(job))
        assertTrue(queue.allJobs().isEmpty())
        assertTrue(queue.active(wa).isEmpty())
    }

    // --- persistence --------------------------------------------------------------------

    @Test
    fun theQueueSurvivesAProcessRestartVerbatim() {
        val id = (queue.enqueue(request(), scheduling(), "m1", clock) as EnqueueResult.Scheduled).job.key.id
        queue.recordAttempt(id, RevocationAttemptResult.Failed("transient"), clock + 1_000)
        queue.enqueue(request(), scheduling(delayMillis = AutoDeleteDurations.HOUR), "m2", clock)
        val exported = queue.exportJobs()
        val before = queue.allJobs().sortedBy { it.key.id }

        val restarted = MessageRevocationQueue(InMemoryKeyValueStore(), now = { clock })
        assertEquals(2, restarted.restoreJobs(exported))
        assertEquals(before, restarted.allJobs().sortedBy { it.key.id })
    }

    @Test
    fun restoreDropsEverythingThatDoesNotDecode() {
        val restarted = MessageRevocationQueue(InMemoryKeyValueStore(), now = { clock })
        assertEquals(0, restarted.restoreJobs("not a queue"))
        assertEquals(0, restarted.restoreJobs("{\"v\":1,\"jobs\":[{\"target\":\"nope\"}]}"))
    }

    @Test
    fun theCodecRoundTripsEveryState() {
        RevocationState.entries.forEach { state ->
            val job =
                PendingMessageRevocation(
                    key = RevocationKey(business, "2", "chat-1", "m1"),
                    messageClass = OutgoingMessageClass.VOICE_MESSAGE,
                    sentAt = clock,
                    deleteAt = clock + FIVE_MINUTES,
                    policySource = PolicyScope.Account(business, "2"),
                    attemptCount = 2,
                    lastAttemptAt = clock + 500,
                    state = state,
                    failureReason = "reason",
                )
            assertEquals(job, RevocationCodec.decodeJob(RevocationCodec.encodeJob(job)))
        }
    }

    @Test
    fun theCodecRejectsAnIncompleteDocumentInsteadOfGuessing() {
        assertNull(RevocationCodec.decodeJob(null))
        assertNull(RevocationCodec.decodeJob("{"))
        assertNull(RevocationCodec.decodeJob("{\"target\":\"whatsapp\"}"))
        assertNull(RevocationCodec.decodeJob("{\"target\":\"unknown-app\",\"chatId\":\"c\",\"messageId\":\"m\"}"))
    }

    @Test
    fun aQueueDocumentIsRecognisedByItsSchemaVersion() {
        assertTrue(RevocationCodec.isReadable(RevocationCodec.encodeQueue(emptyList())))
        assertFalse(RevocationCodec.isReadable("{\"v\":99,\"jobs\":[]}"))
        assertFalse(RevocationCodec.isReadable("{}"))
    }

    @Test
    fun pruningRemovesUnreadableEntriesAndKeepsTheNewestCompletedJobs() {
        store.putString(MessageRevocationQueue.JOB_PREFIX + "corrupt", "not json")
        repeat(4) { index ->
            val id = (queue.enqueue(request(), scheduling(), "m$index", clock) as EnqueueResult.Scheduled).job.key.id
            queue.recordAttempt(id, RevocationAttemptResult.DeletedForEveryone, clock + index)
        }
        queue.pruneCompleted(keep = 2)
        assertEquals(2, queue.allJobs().size)
        assertEquals(setOf("m2", "m3"), queue.allJobs().map { it.key.messageId }.toSet())
    }

    @Test
    fun historyIsTheAuditLogAndNeverCarriesTheChatIdentifier() {
        val id =
            (
                queue.enqueue(
                    request(chatId = "4915123456789@s.whatsapp.net"),
                    scheduling(),
                    "m1",
                    clock,
                ) as EnqueueResult.Scheduled
            ).job.key.id
        queue.recordAttempt(id, RevocationAttemptResult.DeletedForEveryone)
        val line = queue.history(wa).single().toDisplayLine()
        assertNotNull(line)
        assertTrue(
            "the audit line must not carry the chat identifier: $line",
            !line.contains("4915123456789"),
        )
    }

    @Test
    fun aStoredJobHoldsIdentifiersAndSchedulingMetadataOnly() {
        val fieldNames = PendingMessageRevocation::class.java.declaredFields.map { it.name.lowercase() }
        listOf("body", "text", "content", "caption", "media", "path").forEach { forbidden ->
            assertTrue(
                "a revocation job must not carry '$forbidden': $fieldNames",
                fieldNames.none { it.contains(forbidden) },
            )
        }
    }

    private companion object {
        /** A realistic epoch-millis instant, so deadlines are in the future by default. */
        const val BASE_TIME = 1_700_000_000_000L

        val FIVE_MINUTES = 5 * AutoDeleteDurations.MINUTE
    }
}

package com.wax.module.automation

import com.wax.module.platform.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskerTest {
    private lateinit var store: InMemoryKeyValueStore
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        now = 1_700_000_000_000L
    }

    private fun authenticator() = TaskerAuthenticator(store, { now })

    @Test
    fun withoutATokenEveryCommandIsUnauthorized() {
        val api = TaskerApi(authenticator()) { }
        val result = api.dispatch("anything", TaskerCommand(TaskerActionType.REQUEST_BACKUP))
        assertEquals(TaskerDispatchResult.Unauthorized, result)
    }

    @Test
    fun aRotatedTokenAuthenticatesAndThePreviousOneStopsWorking() {
        val auth = authenticator()
        val first = auth.rotate()
        val second = auth.rotate()
        assertTrue(auth.isValid(second))
        assertFalse("rotation must revoke the old token", auth.isValid(first))
    }

    @Test
    fun aWrongTokenIsRejected() {
        val auth = authenticator()
        auth.rotate()
        assertFalse(auth.isValid("guess"))
        assertFalse(auth.isValid(null))
        assertFalse(auth.isValid(""))
    }

    @Test
    fun onlyTheTokenHashIsStored() {
        val auth = authenticator()
        val token = auth.rotate()
        val storedValues = store.keys().mapNotNull { store.getString(it) }
        assertFalse("the clear-text token must never be persisted", storedValues.contains(token))
        assertTrue(store.keys().any { it.contains("token_hash") })
    }

    @Test
    fun revokingClosesTheApi() {
        val auth = authenticator()
        val token = auth.rotate()
        auth.revoke()
        assertFalse(auth.hasToken())
        assertFalse(auth.isValid(token))
        val api = TaskerApi(auth) { }
        assertEquals(
            TaskerDispatchResult.Unauthorized,
            api.dispatch(token, TaskerCommand(TaskerActionType.EXPORT_DIAGNOSTICS)),
        )
    }

    @Test
    fun anExpiredTokenIsRejectedAsExpired() {
        val auth = authenticator()
        val token = auth.rotate()
        auth.setExpiry(durationMillis = 60_000)
        assertTrue(auth.isValid(token))
        now += 60_001
        assertTrue(auth.isExpired())
        assertFalse(auth.isValid(token))
        val api = TaskerApi(auth) { }
        assertEquals(
            TaskerDispatchResult.Expired,
            api.dispatch(token, TaskerCommand(TaskerActionType.REQUEST_BACKUP)),
        )
    }

    @Test
    fun removalOfExpiryMakesTheTokenPermanent() {
        val auth = authenticator()
        val token = auth.rotate()
        auth.setExpiry(durationMillis = 1_000)
        auth.setExpiry(null)
        now += 10_000_000
        assertTrue(auth.isValid(token))
    }

    @Test
    fun dispatchDelegatesTheCommandToTheExecutor() {
        val auth = authenticator()
        val token = auth.rotate()
        var received: TaskerCommand? = null
        val api = TaskerApi(auth) { received = it }
        val command =
            TaskerCommand(
                TaskerActionType.SEND_MESSAGE,
                mapOf(TaskerCommand.PARAM_CHAT to "chat", TaskerCommand.PARAM_TEXT to "hello"),
            )
        assertEquals(TaskerDispatchResult.Accepted(command), api.dispatch(token, command))
        assertEquals(command, received)
    }

    @Test
    fun aFailingExecutorBecomesAFailedResultNotAnException() {
        val auth = authenticator()
        val token = auth.rotate()
        val api = TaskerApi(auth) { throw IllegalStateException("no such chat") }
        val result = api.dispatch(token, TaskerCommand(TaskerActionType.OPEN_CONVERSATION))
        assertTrue(result is TaskerDispatchResult.Failed)
        assertTrue((result as TaskerDispatchResult.Failed).message.contains("IllegalStateException"))
    }

    @Test
    fun theDescriptorNeverLeaksTheToken() {
        val auth = authenticator()
        val token = auth.rotate()
        val description = auth.describe()
        assertFalse(description.contains(token))
        assertTrue(description.contains("active"))
    }

    @Test
    fun theApiExposesTheDocumentedActions() {
        val api = TaskerApi(authenticator()) { }
        assertEquals(
            listOf(
                TaskerActionType.SEND_MESSAGE,
                TaskerActionType.TOGGLE_PRIVACY_PROFILE,
                TaskerActionType.TOGGLE_DND,
                TaskerActionType.TOGGLE_FEATURE,
                TaskerActionType.REQUEST_BACKUP,
                TaskerActionType.OPEN_CONVERSATION,
                TaskerActionType.EXPORT_DIAGNOSTICS,
            ),
            api.availableActions(),
        )
    }
}

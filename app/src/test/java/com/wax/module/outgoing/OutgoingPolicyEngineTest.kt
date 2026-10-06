package com.wax.module.outgoing

import com.wax.module.platform.ChatKind
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
 * The shared outgoing policy engine.
 *
 * The tests are grouped the way the contract is written: precedence first, then View Once,
 * then timed revocation, then the two isolation properties the feature cannot be allowed to
 * lose — a policy set on one target never reaching the other, and a failed capability check
 * never turning media the user asked to be ephemeral into a permanent attachment.
 */
class OutgoingPolicyEngineTest {
    private val wa = TargetApp.WHATSAPP
    private val business = TargetApp.WHATSAPP_BUSINESS

    private lateinit var store: InMemoryOutgoingPolicyStore

    @Before
    fun setUp() {
        store = InMemoryOutgoingPolicyStore()
    }

    private fun engine(
        capabilities: CapabilityProbe = CapabilityProbe.AllSupported,
        windowMillis: Long = 72 * AutoDeleteDurations.HOUR,
    ): OutgoingMessagePolicyEngine =
        OutgoingMessagePolicyEngine(
            policies = store,
            capabilities = capabilities,
            revokeWindow = RevokeWindowProbe { windowMillis },
        )

    @Suppress("LongParameterList")
    private fun request(
        messageClass: OutgoingMessageClass = OutgoingMessageClass.PHOTO,
        app: TargetApp = wa,
        chatId: String = "chat-1",
        kind: ChatKind = ChatKind.CONTACT,
        listId: String? = null,
        accountId: String? = null,
        perMessageViewOnce: ViewOnceChoice? = null,
        perMessageAutoDelete: AutoDeleteChoice? = null,
        perMessageAutoDeleteMillis: Long? = null,
        nativeViewOnceControl: Boolean? = null,
        explicitViewOnceRequired: Boolean = false,
    ): OutgoingSendRequest =
        OutgoingSendRequest(
            app = app,
            chatId = chatId,
            kind = kind,
            messageClass = messageClass,
            listId = listId,
            accountId = accountId,
            perMessageViewOnce = perMessageViewOnce,
            perMessageAutoDelete = perMessageAutoDelete,
            perMessageAutoDeleteMillis = perMessageAutoDeleteMillis,
            nativeViewOnceControl = nativeViewOnceControl,
            explicitViewOnceRequired = explicitViewOnceRequired,
        )

    private fun viewOncePolicy(
        messageClass: OutgoingMessageClass,
        choice: ViewOnceChoice,
    ): OutgoingPolicyLayer = OutgoingPolicyLayer(viewOnce = mapOf(messageClass to choice))

    private fun autoDeletePolicy(
        delayMillis: Long,
        classes: Set<OutgoingMessageClass>? = null,
        fallback: ExpiredWindowFallback = ExpiredWindowFallback.DO_NOTHING,
    ): OutgoingPolicyLayer =
        OutgoingPolicyLayer(
            autoDelete = AutoDeleteChoice.ENABLED,
            autoDeleteDelayMillis = delayMillis,
            autoDeleteClasses = classes,
            expiredWindowFallback = fallback,
        )

    // --- defaults -----------------------------------------------------------------------

    @Test
    fun withNothingConfiguredAMessageIsSentNormally() {
        val decision = engine().decide(request())
        assertFalse(decision.viewOnce.applyViewOnce)
        assertEquals(ViewOnceChoice.NORMAL, decision.viewOnce.choice)
        assertEquals(PolicyScope.Global, decision.viewOnce.source)
        assertFalse(decision.autoDelete.schedule)
        assertEquals(AutoDeleteChoice.USE_PARENT, decision.autoDelete.choice)
        assertNull(decision.indicator)
        assertFalse(decision.abortRequired)
    }

    // --- precedence ---------------------------------------------------------------------

    @Test
    fun aContactPolicyAppliesOnlyToTheMediaClassItNames() {
        store.save(
            PolicyScope.Chat(wa, "chat-1", ChatKind.CONTACT),
            viewOncePolicy(OutgoingMessageClass.PHOTO, ViewOnceChoice.ALWAYS_VIEW_ONCE),
        )
        val photo = engine().decide(request(messageClass = OutgoingMessageClass.PHOTO))
        assertTrue(photo.viewOnce.applyViewOnce)
        assertEquals(PolicyScope.Chat(wa, "chat-1", ChatKind.CONTACT), photo.viewOnce.source)
        assertEquals("View Once · Contact policy", photo.indicator)

        val video = engine().decide(request(messageClass = OutgoingMessageClass.VIDEO))
        assertFalse(video.viewOnce.applyViewOnce)
    }

    @Test
    fun aGroupPolicyAppliesToAGroupChat() {
        store.save(
            PolicyScope.Chat(wa, "group-1", ChatKind.GROUP),
            viewOncePolicy(OutgoingMessageClass.VIDEO, ViewOnceChoice.ALWAYS_VIEW_ONCE),
        )
        val group = engine().decide(request(messageClass = OutgoingMessageClass.VIDEO, chatId = "group-1", kind = ChatKind.GROUP))
        assertTrue(group.viewOnce.applyViewOnce)
        assertEquals("View Once · Group policy", group.indicator)

        val other = engine().decide(request(messageClass = OutgoingMessageClass.VIDEO, chatId = "group-2", kind = ChatKind.GROUP))
        assertFalse(other.viewOnce.applyViewOnce)
    }

    @Test
    fun aTargetPolicyAppliesToEveryChatInThatTarget() {
        store.save(PolicyScope.Target(wa), viewOncePolicy(OutgoingMessageClass.PHOTO, ViewOnceChoice.ALWAYS_VIEW_ONCE))
        assertTrue(engine().decide(request(chatId = "any-chat")).viewOnce.applyViewOnce)
        assertEquals("View Once · WhatsApp policy", engine().decide(request(chatId = "any-chat")).indicator)
    }

    @Test
    fun aListPolicyAppliesThroughTheListTheChatIsIn() {
        store.save(PolicyScope.ListScope(wa, "work"), viewOncePolicy(OutgoingMessageClass.PHOTO, ViewOnceChoice.ALWAYS_VIEW_ONCE))
        assertTrue(engine().decide(request(listId = "work")).viewOnce.applyViewOnce)
        assertFalse(engine().decide(request(listId = "family")).viewOnce.applyViewOnce)
        assertFalse(engine().decide(request(listId = null)).viewOnce.applyViewOnce)
    }

    @Test
    fun anAccountPolicyAppliesOnlyToThatAccount() {
        store.save(PolicyScope.Account(wa, "2"), viewOncePolicy(OutgoingMessageClass.PHOTO, ViewOnceChoice.ALWAYS_VIEW_ONCE))
        assertTrue(engine().decide(request(accountId = "2")).viewOnce.applyViewOnce)
        assertFalse(engine().decide(request(accountId = "1")).viewOnce.applyViewOnce)
        assertFalse(engine().decide(request(accountId = null)).viewOnce.applyViewOnce)
    }

    @Test
    fun theMostSpecificScopeWins() {
        store.save(
            PolicyScope.Target(wa),
            viewOncePolicy(OutgoingMessageClass.PHOTO, ViewOnceChoice.ALWAYS_VIEW_ONCE)
                .over(autoDeletePolicy(5 * AutoDeleteDurations.MINUTE)),
        )
        store.save(
            PolicyScope.Chat(wa, "chat-1", ChatKind.CONTACT),
            viewOncePolicy(OutgoingMessageClass.PHOTO, ViewOnceChoice.NORMAL),
        )
        val decision = engine().decide(request())
        assertFalse(decision.viewOnce.applyViewOnce)
        // The contact said nothing about auto delete, so the target's delay still applies.
        assertTrue(decision.autoDelete.schedule)
        assertEquals(5 * AutoDeleteDurations.MINUTE, decision.autoDelete.delayMillis)
    }

    @Test
    fun aPerMessageChoiceBeatsEveryStoredPolicy() {
        store.save(
            PolicyScope.Chat(wa, "chat-1", ChatKind.CONTACT),
            viewOncePolicy(OutgoingMessageClass.PHOTO, ViewOnceChoice.ALWAYS_VIEW_ONCE),
        )
        val decision = engine().decide(request(perMessageViewOnce = ViewOnceChoice.NORMAL))
        assertFalse(decision.viewOnce.applyViewOnce)
        assertEquals(ViewOnceChoice.NORMAL, decision.viewOnce.choice)
        assertNull(decision.viewOnce.block)
    }

    @Test
    fun theNativeControlCountsAsAnExplicitPerMessageChoice() {
        store.save(
            PolicyScope.Chat(wa, "chat-1", ChatKind.CONTACT),
            viewOncePolicy(OutgoingMessageClass.PHOTO, ViewOnceChoice.ALWAYS_VIEW_ONCE),
        )
        assertFalse(engine().decide(request(nativeViewOnceControl = false)).viewOnce.applyViewOnce)

        val turnedOn = engine().decide(request(nativeViewOnceControl = true))
        assertTrue(turnedOn.viewOnce.applyViewOnce)
        assertEquals(PolicyScope.Message(wa), turnedOn.viewOnce.source)
        assertEquals("View Once · this message policy", turnedOn.indicator)
    }

    // --- View Once ----------------------------------------------------------------------

    @Test
    fun askEachTimeWaitsForTheUserWithoutBlockingTheSend() {
        store.save(
            PolicyScope.Chat(wa, "chat-1", ChatKind.CONTACT),
            viewOncePolicy(OutgoingMessageClass.PHOTO, ViewOnceChoice.ASK_EACH_TIME),
        )
        val decision = engine().decide(request())
        assertEquals(ViewOnceBlockReason.AWAITING_CONFIRMATION, decision.viewOnce.block)
        assertFalse(decision.viewOnce.applyViewOnce)
        assertFalse(decision.abortRequired)
        assertTrue(decision.notifyRequired)
    }

    @Test
    fun aMissingCapabilityStopsTheSendRatherThanSendingTheMediaPersistently() {
        store.save(
            PolicyScope.Chat(wa, "chat-1", ChatKind.CONTACT),
            viewOncePolicy(OutgoingMessageClass.PHOTO, ViewOnceChoice.ALWAYS_VIEW_ONCE),
        )
        val capabilities = CapabilityProbe { id -> id != OutgoingCapabilities.VIEW_ONCE_PHOTO }
        val decision = engine(capabilities = capabilities).decide(request())
        assertEquals(ViewOnceBlockReason.MISSING_CAPABILITY, decision.viewOnce.block)
        assertFalse(decision.viewOnce.applyViewOnce)
        assertTrue(decision.abortRequired)
        assertTrue(decision.notifyRequired)
        assertNull(decision.indicator)
    }

    @Test
    fun aClassWithoutNativeViewOnceNeverGetsIt() {
        store.save(PolicyScope.Global, viewOncePolicy(OutgoingMessageClass.DOCUMENT, ViewOnceChoice.ALWAYS_VIEW_ONCE))
        val decision = engine().decide(request(messageClass = OutgoingMessageClass.DOCUMENT))
        assertEquals(ViewOnceBlockReason.CLASS_NOT_SUPPORTED, decision.viewOnce.block)
        assertTrue(decision.abortRequired)
        assertNull(decision.viewOnce.capabilityId)
    }

    @Test
    fun aNativeVoiceMessageIsNotAGenericAudioFile() {
        store.save(PolicyScope.Global, viewOncePolicy(OutgoingMessageClass.VOICE_MESSAGE, ViewOnceChoice.ALWAYS_VIEW_ONCE))
        assertTrue(engine().decide(request(messageClass = OutgoingMessageClass.VOICE_MESSAGE)).viewOnce.applyViewOnce)
        assertFalse(engine().decide(request(messageClass = OutgoingMessageClass.AUDIO_FILE)).viewOnce.applyViewOnce)
    }

    @Test
    fun scheduledMediaRevalidatesTheCapabilityAndSaysSo() {
        store.save(
            PolicyScope.Chat(wa, "chat-1", ChatKind.CONTACT),
            viewOncePolicy(OutgoingMessageClass.VIDEO, ViewOnceChoice.ALWAYS_VIEW_ONCE),
        )
        val capabilities = CapabilityProbe { false }
        val decision =
            engine(capabilities = capabilities).decide(
                request(messageClass = OutgoingMessageClass.VIDEO, explicitViewOnceRequired = true),
            )
        assertEquals(ViewOnceBlockReason.MISSING_CAPABILITY, decision.viewOnce.block)
        assertTrue(decision.abortRequired)
        assertTrue(decision.diagnostics.any { it.contains("blocked") })
    }

    @Test
    fun theIndicatorNamesTheScopeThatActedAndCarriesNoIdentifiers() {
        store.save(
            PolicyScope.Chat(wa, "4915123456789@s.whatsapp.net", ChatKind.CONTACT),
            viewOncePolicy(OutgoingMessageClass.PHOTO, ViewOnceChoice.ALWAYS_VIEW_ONCE),
        )
        val decision = engine().decide(request(chatId = "4915123456789@s.whatsapp.net"))
        assertEquals("View Once · Contact policy", decision.indicator)
        assertTrue(
            "diagnostics must not carry the chat identifier: ${decision.diagnostics}",
            decision.diagnostics.none { it.contains("4915123456789") },
        )
        assertTrue(decision.toDisplayLine().let { !it.contains("4915123456789") })
    }

    // --- timed revocation ---------------------------------------------------------------

    @Test
    fun autoDeleteSchedulesAtTheConfiguredDelay() {
        store.save(PolicyScope.Global, autoDeletePolicy(5 * AutoDeleteDurations.MINUTE))
        val decision = engine().decide(request())
        assertTrue(decision.autoDelete.schedule)
        assertEquals(5 * AutoDeleteDurations.MINUTE, decision.autoDelete.delayMillis)
        assertEquals("Delete for everyone after 5 minutes.", decision.autoDelete.explanation)
    }

    @Test
    fun everyPresetDelayIsAccepted() {
        AutoDeleteDurations.PRESETS.forEach { preset ->
            val fresh = InMemoryOutgoingPolicyStore()
            fresh.save(PolicyScope.Global, autoDeletePolicy(preset))
            val decision =
                OutgoingMessagePolicyEngine(
                    policies = fresh,
                    capabilities = CapabilityProbe.AllSupported,
                    revokeWindow = RevokeWindowProbe { 72 * AutoDeleteDurations.HOUR },
                ).decide(request())
            assertTrue("preset $preset must schedule", decision.autoDelete.schedule)
            assertEquals(preset, decision.autoDelete.delayMillis)
        }
    }

    @Test
    fun aDelayPastTheRevokeWindowIsRefusedInsteadOfPromised() {
        store.save(PolicyScope.Global, autoDeletePolicy(24 * AutoDeleteDurations.HOUR))
        val decision = engine(windowMillis = AutoDeleteDurations.HOUR).decide(request())
        assertEquals(AutoDeleteBlockReason.DELAY_EXCEEDS_REVOKE_WINDOW, decision.autoDelete.block)
        assertFalse(decision.autoDelete.schedule)
        assertEquals(ExpiredWindowFallback.DO_NOTHING, decision.autoDelete.fallback)
        assertTrue(decision.autoDelete.explanation.contains("left as it is"))
    }

    @Test
    fun theLocalOnlyFallbackIsUsedOnlyWhenItWasChosen() {
        store.save(
            PolicyScope.Global,
            autoDeletePolicy(24 * AutoDeleteDurations.HOUR, fallback = ExpiredWindowFallback.DELETE_LOCAL_COPY_ONLY),
        )
        val decision = engine(windowMillis = AutoDeleteDurations.HOUR).decide(request())
        assertFalse(decision.autoDelete.schedule)
        assertEquals(ExpiredWindowFallback.DELETE_LOCAL_COPY_ONLY, decision.autoDelete.fallback)
        assertTrue(decision.autoDelete.explanation.contains("own copy"))
    }

    @Test
    fun anUnknownRevokeWindowPromisesNothing() {
        store.save(PolicyScope.Global, autoDeletePolicy(AutoDeleteDurations.MINUTE))
        val decision = engine(windowMillis = 0L).decide(request())
        assertEquals(AutoDeleteBlockReason.REVOKE_WINDOW_UNKNOWN, decision.autoDelete.block)
        assertFalse(decision.autoDelete.schedule)
        assertTrue(decision.diagnostics.any { it.contains("revoke window unresolved") })
    }

    @Test
    fun aClientWithoutTimedRevokeSchedulesNothing() {
        store.save(PolicyScope.Global, autoDeletePolicy(AutoDeleteDurations.MINUTE))
        val decision = engine(capabilities = CapabilityProbe { false }).decide(request())
        assertEquals(AutoDeleteBlockReason.TIMED_REVOKE_UNSUPPORTED, decision.autoDelete.block)
        assertFalse(decision.autoDelete.schedule)
    }

    @Test
    fun anEnabledPolicyWithoutADelayIsNotGuessedAt() {
        store.save(PolicyScope.Global, OutgoingPolicyLayer(autoDelete = AutoDeleteChoice.ENABLED))
        val decision = engine().decide(request())
        assertEquals(AutoDeleteBlockReason.MALFORMED_POLICY, decision.autoDelete.block)
        assertFalse(decision.autoDelete.schedule)
    }

    @Test
    fun onlyTheSelectedMessageClassesAreRevoked() {
        store.save(PolicyScope.Global, autoDeletePolicy(AutoDeleteDurations.MINUTE, classes = setOf(OutgoingMessageClass.TEXT)))
        assertTrue(engine().decide(request(messageClass = OutgoingMessageClass.TEXT)).autoDelete.schedule)
        assertEquals(
            AutoDeleteBlockReason.CLASS_NOT_SELECTED,
            engine().decide(request(messageClass = OutgoingMessageClass.PHOTO)).autoDelete.block,
        )
    }

    @Test
    fun everyEligibleClassGoesThroughOneDeletionPolicy() {
        store.save(PolicyScope.Global, autoDeletePolicy(AutoDeleteDurations.MINUTE))
        val classes =
            listOf(
                OutgoingMessageClass.TEXT,
                OutgoingMessageClass.PHOTO,
                OutgoingMessageClass.VIDEO,
                OutgoingMessageClass.VOICE_MESSAGE,
                OutgoingMessageClass.DOCUMENT,
                OutgoingMessageClass.STICKER,
                OutgoingMessageClass.GIF,
                OutgoingMessageClass.CONTACT_CARD,
                OutgoingMessageClass.LOCATION,
                OutgoingMessageClass.POLL,
            )
        classes.forEach { messageClass ->
            val decision = engine().decide(request(messageClass = messageClass))
            assertTrue("$messageClass must be revoked by the same policy", decision.autoDelete.schedule)
        }
    }

    @Test
    fun aPerMessageAutoDeleteChoiceBeatsTheStoredPolicy() {
        store.save(PolicyScope.Chat(wa, "chat-1", ChatKind.CONTACT), autoDeletePolicy(5 * AutoDeleteDurations.MINUTE))
        val off = engine().decide(request(perMessageAutoDelete = AutoDeleteChoice.DISABLED))
        assertFalse(off.autoDelete.schedule)
        assertEquals(AutoDeleteChoice.DISABLED, off.autoDelete.choice)

        val oneOff =
            engine().decide(
                request(perMessageAutoDelete = AutoDeleteChoice.ENABLED, perMessageAutoDeleteMillis = AutoDeleteDurations.SECOND),
            )
        assertTrue(oneOff.autoDelete.schedule)
        assertEquals(AutoDeleteDurations.SECOND, oneOff.autoDelete.delayMillis)
        assertEquals(PolicyScope.Message(wa), oneOff.autoDelete.source)
    }

    // --- both policies at once ----------------------------------------------------------

    @Test
    fun viewOnceAndTimedRevokeBothApplyWithADefinedAnswer() {
        store.save(
            PolicyScope.Chat(wa, "chat-1", ChatKind.CONTACT),
            viewOncePolicy(OutgoingMessageClass.PHOTO, ViewOnceChoice.ALWAYS_VIEW_ONCE)
                .over(autoDeletePolicy(5 * AutoDeleteDurations.MINUTE)),
        )
        val decision = engine().decide(request())
        assertTrue(decision.viewOnce.applyViewOnce)
        assertTrue(decision.autoDelete.schedule)
        assertTrue(decision.mediaDisappearanceRule.contains("View Once governs"))
        assertTrue(decision.mediaDisappearanceRule.contains("unopened"))
    }

    // --- isolation, failure and storage -------------------------------------------------

    @Test
    fun aPolicySetOnOneTargetNeverReachesTheOther() {
        store.save(
            PolicyScope.Target(wa),
            viewOncePolicy(OutgoingMessageClass.PHOTO, ViewOnceChoice.ALWAYS_VIEW_ONCE)
                .over(autoDeletePolicy(AutoDeleteDurations.MINUTE)),
        )
        val businessDecision = engine().decide(request(app = business))
        assertFalse(businessDecision.viewOnce.applyViewOnce)
        assertFalse(businessDecision.autoDelete.schedule)
        assertTrue(engine().decide(request(app = wa)).viewOnce.applyViewOnce)
    }

    @Test
    fun anUnreadableStoredPolicyIsTreatedAsNothingConfigured() {
        val raw = InMemoryKeyValueStore(mapOf(StoredOutgoingPolicyStore.KEY_PREFIX + "chat.whatsapp.contact.chat-1" to "not json at all"))
        val decision = OutgoingMessagePolicyEngine(StoredOutgoingPolicyStore(raw)).decide(request())
        assertFalse(decision.viewOnce.applyViewOnce)
        assertFalse(decision.autoDelete.schedule)
    }

    @Test
    fun storedLayersRoundTripAndAnEmptyOneIsRemoved() {
        val raw = InMemoryKeyValueStore()
        val persisted = StoredOutgoingPolicyStore(raw)
        val layer =
            OutgoingPolicyLayer(
                viewOnce = mapOf(OutgoingMessageClass.VIDEO to ViewOnceChoice.ALWAYS_VIEW_ONCE),
                autoDelete = AutoDeleteChoice.ENABLED,
                autoDeleteDelayMillis = 15 * AutoDeleteDurations.MINUTE,
                autoDeleteClasses = setOf(OutgoingMessageClass.VIDEO, OutgoingMessageClass.PHOTO),
                expiredWindowFallback = ExpiredWindowFallback.DELETE_LOCAL_COPY_ONLY,
            )
        val scope = PolicyScope.Chat(wa, "chat-1", ChatKind.CONTACT)
        persisted.save(scope, layer)
        assertEquals(layer, persisted.layerFor(scope))
        assertEquals(listOf(scope), persisted.configuredScopes())

        persisted.save(scope, OutgoingPolicyLayer())
        assertEquals(OutgoingPolicyLayer(), persisted.layerFor(scope))
        assertTrue(persisted.configuredScopes().isEmpty())
    }

    @Test
    fun storedPoliciesAreResolvedByTheEngine() {
        val raw = InMemoryKeyValueStore()
        val persisted = StoredOutgoingPolicyStore(raw)
        persisted.save(PolicyScope.Global, autoDeletePolicy(30 * AutoDeleteDurations.SECOND))
        val decision =
            OutgoingMessagePolicyEngine(
                policies = persisted,
                revokeWindow = RevokeWindowProbe { 72 * AutoDeleteDurations.HOUR },
            ).decide(request())
        assertTrue(decision.autoDelete.schedule)
        assertEquals(30 * AutoDeleteDurations.SECOND, decision.autoDelete.delayMillis)
    }

    @Test
    fun everyScopeCanBeParsedBackFromItsCode() {
        val scopes: List<PolicyScope> =
            listOf(
                PolicyScope.Global,
                PolicyScope.Target(wa),
                PolicyScope.Account(wa, "2"),
                PolicyScope.ListScope(wa, "work"),
                PolicyScope.Chat(business, "x", ChatKind.GROUP),
                PolicyScope.Message(wa),
            )
        scopes.forEach { scope ->
            assertEquals(scope, PolicyScope.parse(scope.code))
        }
        assertNull(PolicyScope.parse("nonsense"))
        assertNull(PolicyScope.parse(null))
    }

    @Test
    fun delayLabelsReadTheWayTheInterfaceShowsThem() {
        assertEquals("30 seconds", AutoDeleteDurations.label(30 * AutoDeleteDurations.SECOND))
        assertEquals("1 minute", AutoDeleteDurations.label(AutoDeleteDurations.MINUTE))
        assertEquals("5 minutes", AutoDeleteDurations.label(5 * AutoDeleteDurations.MINUTE))
        assertEquals("1 hour", AutoDeleteDurations.label(AutoDeleteDurations.HOUR))
        assertEquals("24 hours", AutoDeleteDurations.label(24 * AutoDeleteDurations.HOUR))
        assertEquals("90 minutes", AutoDeleteDurations.label(90 * AutoDeleteDurations.MINUTE))
        assertEquals("disabled", AutoDeleteDurations.label(0L))
        assertTrue(AutoDeleteDurations.isPreset(5 * AutoDeleteDurations.MINUTE))
        assertFalse(AutoDeleteDurations.isPreset(7 * AutoDeleteDurations.MINUTE))
        assertNotNull(AutoDeleteDurations.label(7 * AutoDeleteDurations.SECOND))
    }
}

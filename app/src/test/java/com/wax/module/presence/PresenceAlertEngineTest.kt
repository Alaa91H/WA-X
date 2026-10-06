package com.wax.module.presence

import com.wax.module.notifications.CooldownWindows
import com.wax.module.notifications.NotificationCooldownEngine
import com.wax.module.notifications.NotificationCooldownStore
import com.wax.module.outgoing.PolicyScope
import com.wax.module.platform.ChatKind
import com.wax.module.platform.InMemoryKeyValueStore
import com.wax.module.platform.TargetApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the alert engine decides, including the burst window.
 *
 * Every boundary is driven by a fake clock rather than a wait, so "the window is anchored to the
 * first alert and is not pushed forward by later ones" is a real assertion about 19 999 ms and
 * 20 000 ms instead of a timing guess. The cases are grouped the way the feature can hurt:
 * alerting the user to their own typing, repeating one beep into a stream of them while the other
 * party keeps composing, and silencing an alert the user configured for a reason that does not
 * apply to it.
 */
class PresenceAlertEngineTest {
    private var now = 0L
    private val keyValue = InMemoryKeyValueStore()
    private val rules = PresenceAlertStore(keyValue)
    private val engine =
        PresenceAlertEngine(
            rules,
            NotificationCooldownEngine(NotificationCooldownStore(InMemoryKeyValueStore())) { now },
        )

    private fun chat(
        chatId: String,
        app: TargetApp = TargetApp.WHATSAPP,
        kind: ChatKind = ChatKind.CONTACT,
    ): PresenceChatRef = PresenceChatRef(app = app, chatId = chatId, kind = kind)

    private fun observed(
        activity: PresenceActivity,
        chatId: String = "chat-1",
        fromMe: Boolean = false,
        state: PresenceChatState = PresenceChatState(),
    ): PresenceObservation = PresenceObservation(ref = chat(chatId), activity = activity, isFromMe = fromMe, state = state)

    private fun businessObservation(): PresenceObservation =
        PresenceObservation(
            ref = chat("chat-1", app = TargetApp.WHATSAPP_BUSINESS),
            activity = PresenceActivity.TYPING,
            isFromMe = false,
        )

    private fun scopeFor(chatId: String?): PolicyScope =
        if (chatId == null) {
            PolicyScope.Global
        } else {
            PolicyScope.Chat(TargetApp.WHATSAPP, chatId, ChatKind.CONTACT)
        }

    /** One activity at one style, the shape most cases need. */
    private fun configure(
        activity: PresenceActivity,
        style: PresenceAlertStyle,
        chatId: String? = null,
    ) = configureWith(PresenceAlertRule(activity = activity, style = style), chatId)

    /** The cases that also differ on cooldown, open-chat behaviour or suppressors. */
    private fun configureWith(
        rule: PresenceAlertRule,
        chatId: String? = null,
    ) {
        rules.save(scopeFor(chatId), rule)
    }

    // --- the basic decisions --------------------------------------------------------------

    @Test
    fun yourOwnActivityNeverAlertsEvenWhenItIsConfigured() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP_AND_BANNER)
        val decision = engine.decide(observed(PresenceActivity.TYPING, fromMe = true))
        assertFalse(decision.alert)
        assertTrue(decision.reason.contains("your own activity"))
        assertFalse(decision.soundEnabled)
        assertFalse(decision.bannerEnabled)
    }

    @Test
    fun anUnconfiguredActivityStaysSilentAndSaysSo() {
        val decision = engine.decide(observed(PresenceActivity.TYPING))
        assertFalse(decision.alert)
        assertTrue(decision.isSilent)
        assertTrue(decision.reason.contains("No alert is configured"))
    }

    @Test
    fun aConfiguredActivityAlertsOnExactlyTheChannelsItsStyleNames() {
        configure(PresenceActivity.RECORDING_VOICE, PresenceAlertStyle.BEEP_AND_BANNER)
        val decision = engine.decide(observed(PresenceActivity.RECORDING_VOICE))
        assertTrue(decision.alert)
        assertTrue(decision.soundEnabled)
        assertTrue(decision.bannerEnabled)
        assertFalse(decision.vibrationEnabled)
        assertEquals(PresenceAlertStyle.BEEP_AND_BANNER.channels, decision.channels)
        assertTrue(decision.toDisplayLine().contains("sound"))
        assertTrue(decision.toDisplayLine().contains("heads_up"))
    }

    @Test
    fun aBannerOnlyStyleMakesNoSound() {
        configure(PresenceActivity.UPLOADING_MEDIA, PresenceAlertStyle.BANNER)
        val decision = engine.decide(observed(PresenceActivity.UPLOADING_MEDIA))
        assertTrue(decision.alert)
        assertFalse(decision.soundEnabled)
        assertTrue(decision.bannerEnabled)
    }

    @Test
    fun anExplicitlySilentRuleIsHonouredAndExplained() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.NOTHING)
        val decision = engine.decide(observed(PresenceActivity.TYPING))
        assertFalse(decision.alert)
        assertTrue(decision.reason.contains("stay silent"))
    }

    // --- per user and per activity --------------------------------------------------------

    @Test
    fun perUserCustomisationIsHonouredAcrossChatsInTheSameAccount() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP_AND_BANNER, chatId = "chat-1")
        assertTrue(engine.decide(observed(PresenceActivity.TYPING, chatId = "chat-1")).alert)
        assertFalse(engine.decide(observed(PresenceActivity.TYPING, chatId = "chat-2")).alert)
    }

    @Test
    fun perActivityCustomisationIsHonouredInsideOneChat() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP, chatId = "chat-1")
        configure(PresenceActivity.RECORDING_VOICE, PresenceAlertStyle.VIBRATE, chatId = "chat-1")
        val typing = engine.decide(observed(PresenceActivity.TYPING, chatId = "chat-1"))
        val recording = engine.decide(observed(PresenceActivity.RECORDING_VOICE, chatId = "chat-1"))
        assertTrue(typing.alert)
        assertTrue(recording.alert)
        assertNotEquals(typing.channels, recording.channels)
        assertTrue(typing.soundEnabled)
        assertTrue(recording.vibrationEnabled)
    }

    @Test
    fun aTargetRuleDoesNotLeakIntoTheOtherApp() {
        rules.save(
            PolicyScope.Target(TargetApp.WHATSAPP),
            PresenceAlertRule.beepAndBanner(PresenceActivity.TYPING),
        )
        assertTrue(engine.decide(observed(PresenceActivity.TYPING)).alert)
        assertFalse(engine.decide(businessObservation()).alert)
    }

    @Test
    fun aDeviceWideRuleAppliesToBothAppsByDesign() {
        // Not a leak but the definition of the Global scope: a rule saved for the device applies
        // to every chat in every target, which is why per-app and per-contact rules exist above it.
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP_AND_BANNER)
        assertTrue(engine.decide(observed(PresenceActivity.TYPING)).alert)
        now = 1_000L
        assertTrue(engine.decide(businessObservation()).alert)
    }

    // --- what the alert defers to ---------------------------------------------------------

    @Test
    fun aMutedChatIsRespectedAndARuleCanOverrideIt() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP_AND_BANNER)
        val muted = PresenceChatState(chatIsMuted = true)
        assertFalse(engine.decide(observed(PresenceActivity.TYPING, state = muted)).alert)
        configureWith(
            PresenceAlertRule(
                activity = PresenceActivity.RECORDING_VOICE,
                style = PresenceAlertStyle.BEEP,
                suppressors = PresenceSuppressors.NONE,
            ),
        )
        assertTrue(engine.decide(observed(PresenceActivity.RECORDING_VOICE, state = muted)).alert)
    }

    @Test
    fun aBlockedContactIsRespectedAndARuleCanOverrideIt() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BANNER)
        val blocked = PresenceChatState(contactIsBlocked = true)
        val respected = engine.decide(observed(PresenceActivity.TYPING, state = blocked))
        assertFalse(respected.alert)
        assertTrue(respected.reason.contains("blocked"))
        configureWith(
            PresenceAlertRule(
                activity = PresenceActivity.UPLOADING_MEDIA,
                style = PresenceAlertStyle.BANNER,
                suppressors = PresenceSuppressors(blockedContacts = false),
            ),
        )
        assertTrue(engine.decide(observed(PresenceActivity.UPLOADING_MEDIA, state = blocked)).alert)
    }

    @Test
    fun quietHoursAreRespectedAndARuleCanOverrideThem() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP_AND_BANNER)
        val quiet = PresenceChatState(quietHoursActive = true)
        val respected = engine.decide(observed(PresenceActivity.TYPING, state = quiet))
        assertFalse(respected.alert)
        assertTrue(respected.reason.contains("Quiet hours"))
        configureWith(
            PresenceAlertRule(
                activity = PresenceActivity.RECORDING_VIDEO,
                style = PresenceAlertStyle.BEEP,
                suppressors = PresenceSuppressors(quietHours = false),
            ),
        )
        assertTrue(engine.decide(observed(PresenceActivity.RECORDING_VIDEO, state = quiet)).alert)
    }

    @Test
    fun anOpenChatIsSilentUnlessTheRuleAsksForIt() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP_AND_BANNER)
        val open = PresenceChatState(chatIsOpen = true)
        assertFalse(engine.decide(observed(PresenceActivity.TYPING, state = open)).alert)
        configureWith(
            PresenceAlertRule(
                activity = PresenceActivity.UPLOADING_MEDIA,
                style = PresenceAlertStyle.BANNER,
                alertWhenChatIsOpen = true,
            ),
        )
        assertTrue(engine.decide(observed(PresenceActivity.UPLOADING_MEDIA, state = open)).alert)
    }

    // --- the capability -------------------------------------------------------------------

    @Test
    fun theUnreadableCapabilityIsToleratedAndSaysItIsUnverified() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP_AND_BANNER)
        val tolerated = engine.decide(observed(PresenceActivity.TYPING), PresenceCapability.Unknown)
        assertTrue(tolerated.alert)
        assertTrue(tolerated.reason.contains("unverified"))
    }

    @Test
    fun aBuildThatDoesNotExposeTheSignalIsRefusedEvenWhenConfigured() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP_AND_BANNER)
        val refused =
            engine.decide(
                observed(PresenceActivity.TYPING),
                PresenceCapability.Resolved(setOf(PresenceSignal.MEDIA_TRANSFER)),
            )
        assertFalse(refused.alert)
        assertTrue(refused.reason.contains("does not expose"))
    }

    // --- the burst ------------------------------------------------------------------------

    @Test
    fun aBurstAlertsOnceAndAgainOnlyAfterTheWindow() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP_AND_BANNER)
        assertTrue(engine.decide(observed(PresenceActivity.TYPING)).alert)
        now = 5_000L
        val inside = engine.decide(observed(PresenceActivity.TYPING))
        assertFalse(inside.alert)
        assertTrue(inside.reason.contains("1 further observation"))
        now = 20_000L
        assertTrue(engine.decide(observed(PresenceActivity.TYPING)).alert)
    }

    @Test
    fun theWindowIsAnchoredToTheFirstAlertAndIsNotPushedForward() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP_AND_BANNER)
        assertTrue(engine.decide(observed(PresenceActivity.TYPING)).alert)
        // Keeps composing every 5s. If each observation extended the window, 20s would be silent.
        listOf(5_000L, 10_000L, 15_000L, 19_999L).forEach { moment ->
            now = moment
            assertFalse("$moment is inside the window", engine.decide(observed(PresenceActivity.TYPING)).alert)
        }
        now = 20_000L
        assertTrue(engine.decide(observed(PresenceActivity.TYPING)).alert)
    }

    @Test
    fun typingAndRecordingKeepSeparateBurstsInOneChat() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP_AND_BANNER)
        configure(PresenceActivity.RECORDING_VOICE, PresenceAlertStyle.BEEP_AND_BANNER)
        assertTrue(engine.decide(observed(PresenceActivity.TYPING)).alert)
        now = 1_000L
        assertTrue(engine.decide(observed(PresenceActivity.RECORDING_VOICE)).alert)
        now = 2_000L
        assertFalse(engine.decide(observed(PresenceActivity.TYPING)).alert)
    }

    @Test
    fun twoChatsKeepSeparateBursts() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP_AND_BANNER)
        assertTrue(engine.decide(observed(PresenceActivity.TYPING, chatId = "chat-1")).alert)
        now = 1_000L
        assertTrue(engine.decide(observed(PresenceActivity.TYPING, chatId = "chat-2")).alert)
        now = 2_000L
        assertFalse(engine.decide(observed(PresenceActivity.TYPING, chatId = "chat-1")).alert)
    }

    @Test
    fun noCooldownMeansEveryObservationAlerts() {
        configureWith(PresenceAlertRule(activity = PresenceActivity.TYPING, style = PresenceAlertStyle.BEEP, cooldownMillis = 0L))
        assertTrue(engine.decide(observed(PresenceActivity.TYPING)).alert)
        now = 1L
        val second = engine.decide(observed(PresenceActivity.TYPING))
        assertTrue(second.alert)
        assertTrue(second.reason.contains("no cooldown"))
    }

    @Test
    fun aWiderCooldownHoldsTheWindowOpenForLonger() {
        configureWith(
            PresenceAlertRule(
                activity = PresenceActivity.TYPING,
                style = PresenceAlertStyle.BEEP,
                cooldownMillis = CooldownWindows.MINUTE,
            ),
        )
        assertTrue(engine.decide(observed(PresenceActivity.TYPING)).alert)
        now = 20_000L
        assertFalse(engine.decide(observed(PresenceActivity.TYPING)).alert)
        now = 60_000L
        assertTrue(engine.decide(observed(PresenceActivity.TYPING)).alert)
    }

    @Test
    fun forgettingOneChatLeavesTheOthersBurstAlone() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP_AND_BANNER)
        assertTrue(engine.decide(observed(PresenceActivity.TYPING, chatId = "chat-1")).alert)
        now = 1_000L
        assertTrue(engine.decide(observed(PresenceActivity.TYPING, chatId = "chat-2")).alert)
        now = 2_000L
        assertTrue(engine.forget("chat-1"))
        assertTrue(engine.decide(observed(PresenceActivity.TYPING, chatId = "chat-1")).alert)
        assertFalse(engine.decide(observed(PresenceActivity.TYPING, chatId = "chat-2")).alert)
        assertFalse(engine.forget("chat-3"))
    }

    @Test
    fun clearingTheEngineStartsEveryBurstOver() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP_AND_BANNER)
        assertTrue(engine.decide(observed(PresenceActivity.TYPING)).alert)
        now = 1_000L
        assertFalse(engine.decide(observed(PresenceActivity.TYPING)).alert)
        engine.clear()
        assertTrue(engine.decide(observed(PresenceActivity.TYPING)).alert)
    }

    // --- what the log may carry -----------------------------------------------------------

    @Test
    fun aDecisionLineNamesTheActivityAndTheChannelsAndNoChat() {
        configure(PresenceActivity.TYPING, PresenceAlertStyle.BEEP_AND_BANNER, chatId = "chat-secret")
        val line = engine.decide(observed(PresenceActivity.TYPING, chatId = "chat-secret")).toDisplayLine()
        assertTrue(line.contains(PresenceActivity.TYPING.id))
        assertTrue(line.contains("sound"))
        assertFalse(line.contains("chat-secret"))
    }

    @Test
    fun aSilentDecisionLineStillSaysWhatWasDecided() {
        val line = PresenceAlertDecision.silent(PresenceActivity.TYPING, reason = "nothing to do").toDisplayLine()
        assertEquals("presence typing: silent — nothing to do", line)
    }
}

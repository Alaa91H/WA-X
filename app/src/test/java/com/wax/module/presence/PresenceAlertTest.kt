package com.wax.module.presence

import com.wax.module.notifications.CooldownWindows
import com.wax.module.outgoing.PolicyScope
import com.wax.module.platform.ChatKind
import com.wax.module.platform.InMemoryKeyValueStore
import com.wax.module.platform.TargetApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The presence-activity model, its capability report and the store behind it.
 *
 * The cases are grouped the way the feature can hurt: alerting for an activity the build cannot
 * even observe, letting one contact's choice stand in for an opinion it never expressed, and
 * turning a corrupt entry into a silent feature instead of inheriting the parent's rule.
 */
class PresenceAlertTest {
    private val keyValue = InMemoryKeyValueStore()
    private val rules = PresenceAlertStore(keyValue)

    private fun chat(chatId: String): PresenceChatRef = PresenceChatRef(app = TargetApp.WHATSAPP, chatId = chatId, kind = ChatKind.CONTACT)

    // --- the activities and the signals behind them --------------------------------------

    @Test
    fun everyActivityHasAStableDistinctId() {
        val ids = PresenceActivity.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it.isBlank() })
        assertEquals(ids, PresenceActivity.IDS)
        PresenceActivity.entries.forEach { assertEquals(it, PresenceActivity.fromId(it.id)) }
        assertNull(PresenceActivity.fromId("never-heard-of-it"))
        assertNull(PresenceActivity.fromId(null))
    }

    @Test
    fun everyActivityNamesTheSignalItIsObservedThroughAndSaysWhatItMeans() {
        assertEquals(PresenceSignal.CHAT_STATE, PresenceActivity.TYPING.signal)
        assertEquals(PresenceSignal.CHAT_STATE, PresenceActivity.RECORDING_VOICE.signal)
        assertEquals(PresenceSignal.CHAT_STATE, PresenceActivity.RECORDING_VIDEO.signal)
        assertEquals(PresenceSignal.MEDIA_TRANSFER, PresenceActivity.UPLOADING_MEDIA.signal)
        val sentences = PresenceActivity.entries.map { it.describe() }
        assertTrue(sentences.none { it.isBlank() })
        assertEquals(sentences.size, sentences.toSet().size)
    }

    @Test
    fun anUnreadableProbeAllowsTheActivityAndSaysItIsUnverified() {
        assertFalse(PresenceCapability.Unknown.isReadable)
        PresenceActivity.entries.forEach { assertTrue(PresenceCapability.Unknown.isObservable(it)) }
        assertTrue(PresenceCapability.Unknown.describe(PresenceActivity.TYPING).contains("unverified"))
    }

    @Test
    fun aReadableProbeWithoutTheSignalRefusesTheActivityAndNamesWhy() {
        val chatStateOnly = PresenceCapability.Resolved(setOf(PresenceSignal.CHAT_STATE))
        assertTrue(chatStateOnly.isReadable)
        assertTrue(chatStateOnly.isObservable(PresenceActivity.TYPING))
        assertFalse(chatStateOnly.isObservable(PresenceActivity.UPLOADING_MEDIA))
        val reason = chatStateOnly.describe(PresenceActivity.UPLOADING_MEDIA)
        assertTrue(reason.contains("does not expose"))
        assertTrue(reason.contains(PresenceActivity.UPLOADING_MEDIA.label.lowercase()))
    }

    @Test
    fun theTwoSignalsFailIndependentlyOfEachOther() {
        // A build that stopped reporting chat state still uploads media, and the other way round.
        val mediaOnly = PresenceCapability.Resolved(setOf(PresenceSignal.MEDIA_TRANSFER))
        assertTrue(mediaOnly.isObservable(PresenceActivity.UPLOADING_MEDIA))
        assertFalse(mediaOnly.isObservable(PresenceActivity.RECORDING_VOICE))
        val chatStateOnly = PresenceCapability.Resolved(setOf(PresenceSignal.CHAT_STATE))
        assertTrue(chatStateOnly.isObservable(PresenceActivity.RECORDING_VOICE))
        assertFalse(chatStateOnly.isObservable(PresenceActivity.UPLOADING_MEDIA))
    }

    // --- the styles ----------------------------------------------------------------------

    @Test
    fun aStyleIsNothingMoreThanItsChannels() {
        assertTrue(PresenceAlertStyle.NOTHING.isSilent)
        assertTrue(PresenceAlertStyle.NOTHING.channels.isEmpty())
        assertTrue(PresenceAlertStyle.BEEP.hasSound)
        assertFalse(PresenceAlertStyle.BEEP.hasBanner)
        assertTrue(PresenceAlertStyle.BANNER.hasBanner)
        assertFalse(PresenceAlertStyle.BANNER.hasSound)
        assertTrue(PresenceAlertStyle.BEEP_AND_BANNER.hasSound)
        assertTrue(PresenceAlertStyle.BEEP_AND_BANNER.hasBanner)
        assertTrue(PresenceAlertStyle.VIBRATE.hasVibration)
        assertFalse(PresenceAlertStyle.VIBRATE.hasSound)
        assertEquals(3, PresenceAlertStyle.BEEP_BANNER_VIBRATE.channels.size)
        assertEquals(PresenceAlertStyle.entries.toList(), PresenceAlertStyle.CHOICES)
    }

    @Test
    fun aStoredStyleNameIsReadBackAndAnUnknownOneIsRefused() {
        PresenceAlertStyle.entries.forEach { assertEquals(it, PresenceAlertStyle.fromName(it.name)) }
        assertNull(PresenceAlertStyle.fromName("beep_and_telegram"))
        assertNull(PresenceAlertStyle.fromName(null))
    }

    // --- the rule ------------------------------------------------------------------------

    @Test
    fun anUnconfiguredRuleIsSilentSoNobodyIsAlertedByAccident() {
        val rule = PresenceAlertRule(PresenceActivity.TYPING)
        assertTrue(rule.isSilent)
        assertEquals(PresenceAlertStyle.NOTHING, rule.style)
        assertTrue(rule.describe().contains("nothing"))
    }

    @Test
    fun aRuleWithNoCooldownSaysSoInsteadOfHidingIt() {
        val noisy = PresenceAlertRule(PresenceActivity.TYPING).withCooldown(0L)
        assertTrue(noisy.hasNoCooldown)
        assertTrue(noisy.describe().contains("no cooldown"))
        assertEquals(0L, PresenceAlertRule(PresenceActivity.TYPING).withCooldown(-5_000L).cooldownMillis)
        val normal = PresenceAlertRule(PresenceActivity.TYPING).withCooldown(CooldownWindows.MINUTE)
        assertFalse(normal.hasNoCooldown)
        assertTrue(normal.describe().contains("1 minute"))
    }

    @Test
    fun theSuppressorsSummariseWhatTheAlertDefersTo() {
        val sentence = PresenceSuppressors().describe()
        assertTrue(sentence.contains("muted chats"))
        assertTrue(sentence.contains("blocked contacts"))
        assertTrue(sentence.contains("quiet hours"))
        assertTrue(PresenceSuppressors.NONE.describe().contains("ignores"))
        val mutedOnly = PresenceSuppressors(blockedContacts = false, quietHours = false)
        assertTrue(mutedOnly.describe().contains("muted chats"))
        assertFalse(mutedOnly.describe().contains("blocked contacts"))
    }

    @Test
    fun aRuleDescribesItselfWithTheActivityTheStyleAndTheCadence() {
        val line = PresenceAlertRule.beepAndBanner(PresenceActivity.RECORDING_VOICE).describe()
        assertTrue(line.contains(PresenceActivity.RECORDING_VOICE.label))
        assertTrue(line.contains("beep and floating banner"))
        assertTrue(line.contains("not open"))
        assertTrue(PresenceAlertRule.silent(PresenceActivity.TYPING).isSilent)
    }

    // --- the store -----------------------------------------------------------------------

    @Test
    fun aStoredRuleRoundTripsThroughTheStore() {
        val rule =
            PresenceAlertRule(
                activity = PresenceActivity.RECORDING_VOICE,
                style = PresenceAlertStyle.BANNER,
                cooldownMillis = CooldownWindows.MINUTE,
                alertWhenChatIsOpen = true,
                suppressors = PresenceSuppressors(mutedChats = false),
            )
        rules.save(PolicyScope.Global, rule)
        assertEquals(rule, rules.ruleFor(PolicyScope.Global, PresenceActivity.RECORDING_VOICE))
    }

    @Test
    fun anActivityNobodyConfiguredHasNoRuleAtAll() {
        assertNull(rules.ruleFor(PolicyScope.Global, PresenceActivity.TYPING))
        assertNull(rules.effectiveRule(chat("chat-1").scopes(), PresenceActivity.TYPING))
    }

    @Test
    fun oneKeyPerScopePerActivityAndTheActivityComesFirstInIt() {
        rules.save(PolicyScope.Global, PresenceAlertRule.beepAndBanner(PresenceActivity.TYPING))
        rules.save(PolicyScope.Global, PresenceAlertRule.beepAndBanner(PresenceActivity.RECORDING_VOICE))
        rules.save(PolicyScope.Target(TargetApp.WHATSAPP), PresenceAlertRule.silent(PresenceActivity.TYPING))
        val keys = keyValue.keys(PresenceAlertStore.KEY_PREFIX)
        assertEquals(3, keys.size)
        assertTrue(keys.contains("${PresenceAlertStore.KEY_PREFIX}typing.global"))
        assertTrue(keys.contains("${PresenceAlertStore.KEY_PREFIX}recording_voice.global"))
        assertTrue(keys.contains("${PresenceAlertStore.KEY_PREFIX}typing.target.whatsapp"))
    }

    @Test
    fun theMostSpecificScopeWithAChoiceForTheActivityWins() {
        val scope = PolicyScope.Chat(TargetApp.WHATSAPP, "chat-1", ChatKind.CONTACT)
        rules.save(PolicyScope.Global, PresenceAlertRule.beepAndBanner(PresenceActivity.TYPING))
        rules.save(scope, PresenceAlertRule(PresenceActivity.TYPING, style = PresenceAlertStyle.BANNER))
        val effective = rules.effectiveRule(chat("chat-1").scopes(), PresenceActivity.TYPING)
        assertEquals(PresenceAlertStyle.BANNER, effective?.style)
    }

    @Test
    fun aPerContactChoiceDoesNotStandInForAnOpinionItNeverExpressed() {
        // The compositional case: the chat names typing only, and recording keeps the wider
        // scope's choice instead of inheriting the chat's typing rule by accident.
        val scope = PolicyScope.Chat(TargetApp.WHATSAPP, "chat-1", ChatKind.CONTACT)
        rules.save(PolicyScope.Global, PresenceAlertRule.beepAndBanner(PresenceActivity.TYPING))
        rules.save(
            PolicyScope.Global,
            PresenceAlertRule(PresenceActivity.RECORDING_VOICE, style = PresenceAlertStyle.BANNER),
        )
        rules.save(scope, PresenceAlertRule(PresenceActivity.TYPING, style = PresenceAlertStyle.VIBRATE))
        val chain = chat("chat-1").scopes()
        assertEquals(PresenceAlertStyle.VIBRATE, rules.effectiveRule(chain, PresenceActivity.TYPING)?.style)
        assertEquals(
            PresenceAlertStyle.BANNER,
            rules.effectiveRule(chain, PresenceActivity.RECORDING_VOICE)?.style,
        )
        assertNull(rules.effectiveRule(chain, PresenceActivity.UPLOADING_MEDIA))
    }

    @Test
    fun removingAChoiceMakesTheScopeInheritAgain() {
        val scope = PolicyScope.Chat(TargetApp.WHATSAPP, "chat-1", ChatKind.CONTACT)
        rules.save(PolicyScope.Global, PresenceAlertRule.beepAndBanner(PresenceActivity.TYPING))
        rules.save(scope, PresenceAlertRule.silent(PresenceActivity.TYPING))
        val chain = chat("chat-1").scopes()
        assertTrue(rules.effectiveRule(chain, PresenceActivity.TYPING)!!.isSilent)
        assertTrue(rules.remove(scope, PresenceActivity.TYPING))
        assertEquals(
            PresenceAlertStyle.BEEP_AND_BANNER,
            rules.effectiveRule(chain, PresenceActivity.TYPING)?.style,
        )
        assertFalse(rules.remove(scope, PresenceActivity.TYPING))
    }

    @Test
    fun aCorruptValueIsTreatedAsNoOpinionAndInheritsInstead() {
        keyValue.putString("${PresenceAlertStore.KEY_PREFIX}typing.global", "{ this is not json")
        assertNull(rules.ruleFor(PolicyScope.Global, PresenceActivity.TYPING))
        val scope = PolicyScope.Chat(TargetApp.WHATSAPP, "chat-1", ChatKind.CONTACT)
        rules.save(scope, PresenceAlertRule.beepAndBanner(PresenceActivity.TYPING))
        assertEquals(
            PresenceAlertStyle.BEEP_AND_BANNER,
            rules.effectiveRule(chat("chat-1").scopes(), PresenceActivity.TYPING)?.style,
        )
    }

    @Test
    fun aRuleFoundUnderAnotherActivitysKeyIsRefusedRatherThanApplied() {
        // The one corruption a per-activity key can have. Reading it would put the typing style
        // on recording, which is a louder failure than dropping the entry and inheriting.
        rules.save(PolicyScope.Global, PresenceAlertRule.beepAndBanner(PresenceActivity.TYPING))
        val text = keyValue.getString("${PresenceAlertStore.KEY_PREFIX}typing.global")
        keyValue.putString("${PresenceAlertStore.KEY_PREFIX}recording_voice.global", text)
        assertNull(rules.ruleFor(PolicyScope.Global, PresenceActivity.RECORDING_VOICE))
        assertFalse(rules.configured(PolicyScope.Global).any { it.activity == PresenceActivity.RECORDING_VOICE })
    }

    @Test
    fun theConfiguredScopesAreOrderedAndListedPerActivity() {
        rules.save(PolicyScope.Global, PresenceAlertRule.beepAndBanner(PresenceActivity.TYPING))
        rules.save(PolicyScope.Target(TargetApp.WHATSAPP), PresenceAlertRule.silent(PresenceActivity.TYPING))
        val group = PolicyScope.Chat(TargetApp.WHATSAPP, "chat-9", ChatKind.GROUP)
        rules.save(group, PresenceAlertRule.silent(PresenceActivity.TYPING))
        rules.save(PolicyScope.Global, PresenceAlertRule.beepAndBanner(PresenceActivity.UPLOADING_MEDIA))
        assertEquals(
            listOf(PolicyScope.Global, PolicyScope.Target(TargetApp.WHATSAPP), group),
            rules.configuredScopes(PresenceActivity.TYPING),
        )
        assertEquals(listOf(PolicyScope.Global), rules.configuredScopes(PresenceActivity.UPLOADING_MEDIA))
        assertTrue(rules.configuredScopes(PresenceActivity.RECORDING_VIDEO).isEmpty())
        assertEquals(
            listOf(PresenceActivity.TYPING, PresenceActivity.UPLOADING_MEDIA),
            rules.configured(PolicyScope.Global).map { it.activity },
        )
        assertEquals(3, rules.configuredActivityCounts()[PresenceActivity.TYPING])
        assertEquals(0, rules.configuredActivityCounts()[PresenceActivity.RECORDING_VIDEO])
    }

    @Test
    fun clearingRemovesEveryRule() {
        rules.save(PolicyScope.Global, PresenceAlertRule.beepAndBanner(PresenceActivity.TYPING))
        rules.save(PolicyScope.Global, PresenceAlertRule.beepAndBanner(PresenceActivity.UPLOADING_MEDIA))
        rules.clear()
        assertTrue(keyValue.keys(PresenceAlertStore.KEY_PREFIX).isEmpty())
        assertNull(rules.ruleFor(PolicyScope.Global, PresenceActivity.TYPING))
    }
}

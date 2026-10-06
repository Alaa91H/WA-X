package com.wax.module.notifications

import com.wax.module.outgoing.PolicyScope
import com.wax.module.outgoing.PolicyScopes
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
 * Notification cooldown / burst control.
 *
 * The cases follow the rule the feature promises: the first message of a burst alerts normally,
 * the ones inside the window update quietly, and once the window has passed the next message
 * alerts normally again. The clock is injected, so every boundary is tested directly rather than
 * by waiting for real time to pass.
 */
class NotificationCooldownTest {
    private val wa = TargetApp.WHATSAPP

    private lateinit var keyValue: InMemoryKeyValueStore
    private lateinit var store: NotificationCooldownStore
    private var nowMillis = 0L

    @Before
    fun setUp() {
        keyValue = InMemoryKeyValueStore()
        store = NotificationCooldownStore(keyValue)
        nowMillis = 0L
    }

    private fun engine(): NotificationCooldownEngine = NotificationCooldownEngine(store) { nowMillis }

    private fun chat(chatId: String = "chat-1"): PolicyScope = PolicyScope.Chat(wa, chatId, ChatKind.GROUP)

    private fun chain(chatId: String = "chat-1"): List<PolicyScope> = PolicyScopes.forChat(wa, chatId, ChatKind.GROUP)

    private fun rule(
        windowMillis: Long = 20 * CooldownWindows.SECOND,
        channels: Set<AlertChannel> = AlertChannel.entries.toSet(),
    ): CooldownRule = CooldownRule(windowMillis = windowMillis, channels = channels)

    @Test
    fun theCooldownIsOffUntilItIsConfigured() {
        val decision = engine().decide(chain(), "chat-1")
        assertTrue(decision.alert)
        assertFalse(decision.isSuppressed)
        assertTrue(CooldownRule.Disabled.isDisabled)
        assertTrue(store.effectiveRule(chain()).isDisabled)
    }

    @Test
    fun theFirstMessageOfABurstAlertsNormally() {
        store.save(PolicyScope.Global, rule())
        val decision = engine().decide(chain(), "chat-1")
        assertTrue(decision.alert)
        assertEquals(1, decision.burstCount)
        assertTrue(decision.soundEnabled)
        assertTrue(decision.vibrationEnabled)
        assertTrue(decision.headsUpEnabled)
    }

    @Test
    fun messagesInsideTheWindowAreSilenced() {
        store.save(PolicyScope.Global, rule())
        val engine = engine()
        engine.decide(chain(), "chat-1")
        nowMillis = 5 * CooldownWindows.SECOND
        val decision = engine.decide(chain(), "chat-1")
        assertFalse(decision.alert)
        assertEquals(AlertChannel.entries.toSet(), decision.mutedChannels)
        assertEquals(2, decision.burstCount)
        assertEquals(15 * CooldownWindows.SECOND, decision.cooldownRemainingMillis)
    }

    @Test
    fun theWindowIsAnchoredToTheFirstAlertAndNotExtendedByLaterMessages() {
        store.save(PolicyScope.Global, rule())
        val engine = engine()
        engine.decide(chain(), "chat-1")
        nowMillis = 10 * CooldownWindows.SECOND
        assertFalse(engine.decide(chain(), "chat-1").alert)
        nowMillis = 15 * CooldownWindows.SECOND
        assertFalse(engine.decide(chain(), "chat-1").alert)
        nowMillis = 25 * CooldownWindows.SECOND
        val afterWindow = engine.decide(chain(), "chat-1")
        assertTrue(afterWindow.alert)
        assertEquals(1, afterWindow.burstCount)
    }

    @Test
    fun channelsAreSilencedIndependently() {
        store.save(PolicyScope.Global, rule(channels = setOf(AlertChannel.SOUND)))
        val engine = engine()
        engine.decide(chain(), "chat-1")
        nowMillis = CooldownWindows.SECOND
        val decision = engine.decide(chain(), "chat-1")
        assertFalse(decision.soundEnabled)
        assertTrue(decision.vibrationEnabled)
        assertTrue(decision.headsUpEnabled)
    }

    @Test
    fun aZeroWindowDisablesTheRule() {
        store.save(PolicyScope.Global, rule(windowMillis = 0L))
        val engine = engine()
        engine.decide(chain(), "chat-1")
        nowMillis = 1L
        assertTrue(engine.decide(chain(), "chat-1").alert)
    }

    @Test
    fun anExplicitlyDisabledScopeOverridesAParentCooldown() {
        store.save(PolicyScope.Global, rule())
        store.save(chat(), CooldownRule.Disabled)
        assertTrue(store.effectiveRule(chain()).isDisabled)
        assertTrue(engine().decide(chain(), "chat-1").alert)
    }

    @Test
    fun theNearestScopeWins() {
        store.save(PolicyScope.Global, rule(windowMillis = 10 * CooldownWindows.SECOND))
        store.save(chat(), rule(windowMillis = 60 * CooldownWindows.SECOND))
        assertEquals(60 * CooldownWindows.SECOND, store.effectiveRule(chain()).windowMillis)
    }

    @Test
    fun aScopeWithNoOpinionInheritsItsParent() {
        store.save(PolicyScope.Global, rule(windowMillis = 30 * CooldownWindows.SECOND))
        assertNull(store.ruleFor(chat()))
        assertEquals(30 * CooldownWindows.SECOND, store.effectiveRule(chain()).windowMillis)
    }

    @Test
    fun changingTheRuleStartsAFreshBurst() {
        val engine = engine()
        store.save(PolicyScope.Global, rule(windowMillis = 20 * CooldownWindows.SECOND))
        engine.decide(chain(), "chat-1")
        nowMillis = CooldownWindows.SECOND
        store.save(PolicyScope.Global, rule(windowMillis = 30 * CooldownWindows.SECOND))
        assertTrue(engine.decide(chain(), "chat-1").alert)
    }

    @Test
    fun isCoolingDownDoesNotConsumeABurstSlot() {
        store.save(PolicyScope.Global, rule())
        val engine = engine()
        engine.decide(chain(), "chat-1")
        nowMillis = 5 * CooldownWindows.SECOND
        assertTrue(engine.isCoolingDown(chain(), "chat-1"))
        assertEquals(2, engine.decide(chain(), "chat-1").burstCount)
        nowMillis = 25 * CooldownWindows.SECOND
        assertFalse(engine.isCoolingDown(chain(), "chat-1"))
    }

    @Test
    fun forgettingOneChatLeavesTheOthersAlone() {
        store.save(PolicyScope.Global, rule())
        val engine = engine()
        engine.decide(chain("chat-1"), "chat-1")
        engine.decide(chain("chat-2"), "chat-2")
        nowMillis = CooldownWindows.SECOND
        assertTrue(engine.forget("chat-1"))
        assertTrue(engine.decide(chain("chat-1"), "chat-1").alert)
        assertFalse(engine.decide(chain("chat-2"), "chat-2").alert)
        assertFalse(engine.forget("chat-3"))
    }

    @Test
    fun idleBurstStateIsPruned() {
        store.save(PolicyScope.Global, rule())
        val engine = engine()
        engine.decide(chain(), "chat-1")
        nowMillis = 5 * CooldownWindows.MINUTE
        assertEquals(1, engine.prune(CooldownWindows.MINUTE))
        assertEquals(0, engine.prune(CooldownWindows.MINUTE))
    }

    @Test
    fun rulesRoundTrip() {
        val saved = rule(windowMillis = 30_000L, channels = setOf(AlertChannel.SOUND, AlertChannel.HEADS_UP))
        store.save(PolicyScope.Global, saved)
        assertEquals(saved, store.ruleFor(PolicyScope.Global))
        assertEquals(listOf(PolicyScope.Global), store.configuredScopes())
        assertTrue(store.remove(PolicyScope.Global))
        assertFalse(store.remove(PolicyScope.Global))
        assertNull(store.ruleFor(PolicyScope.Global))
        store.save(PolicyScope.Global, saved)
        store.clear()
        assertTrue(store.configuredScopes().isEmpty())
    }

    @Test
    fun anUnreadableRuleIsTreatedAsNoOpinion() {
        keyValue.putString(NotificationCooldownStore.KEY_PREFIX + PolicyScope.Global.code, "{ this is not json")
        assertNull(store.ruleFor(PolicyScope.Global))
        assertTrue(store.effectiveRule(chain()).isDisabled)
        assertTrue(engine().decide(chain(), "chat-1").alert)
    }

    @Test
    fun diagnosticsNeverCarryTheChatIdentifier() {
        store.save(PolicyScope.Global, rule())
        val engine = engine()
        engine.decide(chain(), "chat-1")
        nowMillis = CooldownWindows.SECOND
        val decision = engine.decide(chain(), "chat-1")
        assertFalse(decision.toDisplayLine().contains("chat-1"))
        assertFalse(decision.explanation.contains("chat-1"))
        assertTrue(decision.toDisplayLine().startsWith("cooldown:"))
    }

    @Test
    fun aRuleOnOneChatDoesNotAffectAnother() {
        store.save(chat("chat-1"), rule())
        assertNotNull(store.ruleFor(chat("chat-1")))
        assertNull(store.ruleFor(chat("chat-2")))
        assertTrue(store.effectiveRule(chain("chat-2")).isDisabled)
    }

    @Test
    fun windowLabelsReadAsTimes() {
        assertEquals("20 seconds", CooldownWindows.label(20 * CooldownWindows.SECOND))
        assertEquals("1 minute", CooldownWindows.label(CooldownWindows.MINUTE))
        assertEquals("off", CooldownWindows.label(0L))
        assertTrue(CooldownWindows.isPreset(CooldownWindows.DEFAULT_MILLIS))
        assertFalse(CooldownWindows.isPreset(7 * CooldownWindows.SECOND))
    }

    @Test
    fun aRuleDescribesItselfWithoutUserData() {
        assertEquals("Cooldown off.", CooldownRule.Disabled.describe())
        val described = rule(channels = setOf(AlertChannel.SOUND)).describe()
        assertTrue(described.contains("sound"))
        assertTrue(described.contains("20 seconds"))
    }
}

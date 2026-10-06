package com.wax.module.media

import com.wax.module.outgoing.PolicyScope
import com.wax.module.platform.ChatKind
import com.wax.module.platform.InMemoryKeyValueStore
import com.wax.module.platform.TargetApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Per-chat and per-group media policy.
 *
 * The cases cover the three things the feature can get wrong: resolving a class to the wrong
 * scope, treating "no policy" as a policy, and letting a rule set on one hooked application
 * reach the other.
 */
class MediaPolicyTest {
    private val wa = TargetApp.WHATSAPP
    private val business = TargetApp.WHATSAPP_BUSINESS

    private lateinit var store: MediaPolicyStore
    private lateinit var resolver: MediaPolicyResolver

    @Before
    fun setUp() {
        store = MediaPolicyStore(InMemoryKeyValueStore())
        resolver = MediaPolicyResolver(store)
    }

    /** A group chat on WhatsApp; tests vary it with `copy`, which keeps this helper short. */
    private fun request(): MediaPolicyRequest = MediaPolicyRequest(wa, "chat-1", ChatKind.GROUP)

    private fun layer(vararg choices: Pair<MediaClass, MediaPolicyChoice>): MediaPolicyLayer = MediaPolicyLayer(mapOf(*choices))

    @Test
    fun withNoPolicyWaXLeavesTheDecisionToWhatsApp() {
        val decision = resolver.decide(request(), MediaClass.IMAGES, NetworkClass.METERED)
        assertEquals(MediaDownloadAction.LEAVE_TO_WHATSAPP, decision.action)
        assertFalse(decision.download)
        assertFalse(resolver.resolve(request(), MediaClass.IMAGES).isConfigured)
    }

    @Test
    fun aChatRuleWinsOverTheGlobalRule() {
        store.save(PolicyScope.Global, layer(MediaClass.IMAGES to MediaPolicyChoice.NEVER))
        store.save(
            PolicyScope.Chat(wa, "chat-1", ChatKind.GROUP),
            layer(MediaClass.IMAGES to MediaPolicyChoice.ALWAYS),
        )
        val resolved = resolver.resolve(request(), MediaClass.IMAGES)
        assertEquals(MediaPolicyChoice.ALWAYS, resolved.choice)
        assertEquals(PolicyScope.Chat(wa, "chat-1", ChatKind.GROUP), resolved.source)
    }

    @Test
    fun useParentInheritsTheMoreGeneralAnswer() {
        store.save(PolicyScope.Global, layer(MediaClass.VIDEOS to MediaPolicyChoice.ALWAYS))
        store.save(
            PolicyScope.Chat(wa, "chat-1", ChatKind.GROUP),
            layer(MediaClass.VIDEOS to MediaPolicyChoice.USE_PARENT),
        )
        val resolved = resolver.resolve(request(), MediaClass.VIDEOS)
        assertEquals(MediaPolicyChoice.ALWAYS, resolved.choice)
        assertEquals(PolicyScope.Global, resolved.source)
    }

    @Test
    fun classesResolveIndependently() {
        store.save(
            PolicyScope.Global,
            layer(MediaClass.IMAGES to MediaPolicyChoice.ALWAYS, MediaClass.DOCUMENTS to MediaPolicyChoice.NEVER),
        )
        assertTrue(resolver.decide(request(), MediaClass.IMAGES, NetworkClass.METERED).download)
        assertEquals(
            MediaDownloadAction.DO_NOT_DOWNLOAD,
            resolver.decide(request(), MediaClass.DOCUMENTS, NetworkClass.UNMETERED).action,
        )
        assertEquals(
            MediaDownloadAction.LEAVE_TO_WHATSAPP,
            resolver.decide(request(), MediaClass.STICKERS, NetworkClass.UNMETERED).action,
        )
    }

    @Test
    fun wifiOnlyWaitsForAnUnmeteredConnection() {
        store.save(PolicyScope.Global, layer(MediaClass.VIDEOS to MediaPolicyChoice.WIFI_ONLY))
        assertEquals(
            MediaDownloadAction.WAIT_FOR_UNMETERED,
            resolver.decide(request(), MediaClass.VIDEOS, NetworkClass.METERED).action,
        )
        assertEquals(
            MediaDownloadAction.DOWNLOAD_NOW,
            resolver.decide(request(), MediaClass.VIDEOS, NetworkClass.UNMETERED).action,
        )
    }

    @Test
    fun offlineNeverDownloads() {
        store.save(PolicyScope.Global, layer(MediaClass.IMAGES to MediaPolicyChoice.ALWAYS))
        val decision = resolver.decide(request(), MediaClass.IMAGES, NetworkClass.OFFLINE)
        assertEquals(MediaDownloadAction.DO_NOT_DOWNLOAD, decision.action)
        assertFalse(decision.download)
    }

    @Test
    fun manualTapOnlyWaitsForTheUser() {
        store.save(PolicyScope.Global, layer(MediaClass.AUDIO to MediaPolicyChoice.MANUAL_TAP_ONLY))
        assertEquals(
            MediaDownloadAction.WAIT_FOR_USER_TAP,
            resolver.decide(request(), MediaClass.AUDIO, NetworkClass.UNMETERED).action,
        )
    }

    @Test
    fun anUntrustedSenderAlwaysWaitsForATap() {
        store.save(PolicyScope.Global, layer(MediaClass.DOCUMENTS to MediaPolicyChoice.ALWAYS))
        val decision = resolver.decide(request().copy(trusted = false), MediaClass.DOCUMENTS, NetworkClass.UNMETERED)
        assertEquals(MediaDownloadAction.WAIT_FOR_USER_TAP, decision.action)
        assertFalse(decision.download)
    }

    @Test
    fun listAndAccountScopesResolveToo() {
        store.save(PolicyScope.Global, layer(MediaClass.IMAGES to MediaPolicyChoice.NEVER))
        store.save(PolicyScope.ListScope(wa, "family"), layer(MediaClass.IMAGES to MediaPolicyChoice.ALWAYS))
        store.save(PolicyScope.Account(wa, "acct-2"), layer(MediaClass.IMAGES to MediaPolicyChoice.WIFI_ONLY))
        assertEquals(
            MediaPolicyChoice.ALWAYS,
            resolver.resolve(request().copy(listId = "family"), MediaClass.IMAGES).choice,
        )
        assertEquals(
            MediaPolicyChoice.WIFI_ONLY,
            resolver.resolve(request().copy(accountId = "acct-2"), MediaClass.IMAGES).choice,
        )
    }

    @Test
    fun theTwoTargetsAreIsolated() {
        store.save(PolicyScope.Target(wa), layer(MediaClass.IMAGES to MediaPolicyChoice.NEVER))
        assertEquals(MediaPolicyChoice.NEVER, resolver.resolve(request(), MediaClass.IMAGES).choice)
        assertEquals(
            MediaPolicyChoice.USE_PARENT,
            resolver.resolve(request().copy(app = business), MediaClass.IMAGES).choice,
        )
    }

    @Test
    fun everyClassResolvesToSomething() {
        val resolved = resolver.resolve(request())
        assertEquals(MediaClass.entries.size, resolved.size)
        MediaClass.entries.forEach { assertEquals(resolved[it], resolver.resolve(request(), it)) }
    }

    @Test
    fun layersRoundTripAndEmptyLayersAreRemoved() {
        val scope = PolicyScope.Chat(wa, "chat-1", ChatKind.GROUP)
        val saved = layer(MediaClass.GIFS to MediaPolicyChoice.WIFI_ONLY)
        store.save(scope, saved)
        assertEquals(saved, store.layerFor(scope))
        assertEquals(listOf(scope), store.configuredScopes())
        store.save(scope, MediaPolicyLayer())
        assertNull(store.layerFor(scope).choices[MediaClass.GIFS])
        assertTrue(store.configuredScopes().isEmpty())
        store.save(scope, saved)
        store.clear()
        assertTrue(store.configuredScopes().isEmpty())
    }

    @Test
    fun anUnreadableLayerChangesNothing() {
        val keyValue = InMemoryKeyValueStore()
        val broken = MediaPolicyStore(keyValue)
        keyValue.putString(MediaPolicyStore.KEY_PREFIX + PolicyScope.Global.code, "not json at all")
        assertTrue(broken.layerFor(PolicyScope.Global).isEmpty)
        assertEquals(
            MediaDownloadAction.LEAVE_TO_WHATSAPP,
            MediaPolicyResolver(broken).decide(request(), MediaClass.IMAGES, NetworkClass.UNMETERED).action,
        )
    }

    @Test
    fun diagnosticsNameTheScopeKindAndNotTheChat() {
        store.save(PolicyScope.Global, layer(MediaClass.IMAGES to MediaPolicyChoice.NEVER))
        val decision = resolver.decide(request(), MediaClass.IMAGES, NetworkClass.UNMETERED)
        assertFalse(decision.toDisplayLine().contains("chat-1"))
        assertTrue(decision.toDisplayLine().contains("Global"))
        assertTrue(decision.toDisplayLine().contains(MediaPolicyChoice.NEVER.name))
    }

    @Test
    fun theScopeChainNeverContainsAChatTwice() {
        val scopes = request().copy(listId = "family", accountId = "acct-2").scopes()
        assertEquals(scopes.size, scopes.distinct().size)
        assertEquals(PolicyScope.Global, scopes.first())
        assertEquals(5, scopes.size)
    }
}

package com.wax.module.outgoing

import com.wax.module.platform.JsonValue
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.array
import com.wax.module.platform.jsonNumber
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import com.wax.module.platform.jsonStrings
import com.wax.module.platform.long
import com.wax.module.platform.obj
import com.wax.module.platform.objOrNull
import com.wax.module.platform.string
import com.wax.module.platform.stringOrNull

/**
 * Where each scope's outgoing policy lives.
 *
 * An interface with two implementations for the same reason the platform's `KeyValueStore`
 * has one: every precedence rule has to be provable in a plain unit test, and a policy that
 * could only be exercised against real `SharedPreferences` would be tested by reading screens
 * instead. The engine takes this interface, so the tests drive it with [InMemoryOutgoingPolicyStore].
 *
 * A scope with no stored layer returns an empty layer rather than null. "Nothing configured
 * here" and "configured to do nothing" are different answers, and a nullable return makes
 * every caller invent the distinction.
 */
interface OutgoingPolicyStore {
    /** The layer stored for [scope], or an empty layer when nothing is configured there. */
    fun layerFor(scope: PolicyScope): OutgoingPolicyLayer

    /** Stores [layer] for [scope]. An empty layer removes the entry instead of writing it. */
    fun save(
        scope: PolicyScope,
        layer: OutgoingPolicyLayer,
    )

    /** Every scope that currently holds a layer, in precedence order. Used by diagnostics. */
    fun configuredScopes(): List<PolicyScope>

    /** Removes every layer. Used by tests and by a factory reset. */
    fun clear()
}

/** The in-memory store, used by the tests and by a settings import before it is written. */
class InMemoryOutgoingPolicyStore(
    seed: Map<PolicyScope, OutgoingPolicyLayer> = emptyMap(),
) : OutgoingPolicyStore {
    private val layers = LinkedHashMap<PolicyScope, OutgoingPolicyLayer>().apply { putAll(seed) }

    override fun layerFor(scope: PolicyScope): OutgoingPolicyLayer = layers[scope] ?: OutgoingPolicyLayer()

    override fun save(
        scope: PolicyScope,
        layer: OutgoingPolicyLayer,
    ) {
        if (layer.isEmpty) layers.remove(scope) else layers[scope] = layer
    }

    override fun configuredScopes(): List<PolicyScope> = layers.keys.sortedBy { it.precedence }

    override fun clear() {
        layers.clear()
    }
}

/**
 * The persisted store, on top of the platform's flat key-value surface.
 *
 * One key per scope rather than one key per field, so a policy is written and read as a unit:
 * a half-applied policy (a delay with no enabled flag, say) cannot exist on disk, and a
 * corrupt entry loses one scope instead of silently leaving the rest in an unknown state. The
 * decoder is total — an unreadable entry decodes to an empty layer, which inherits and does
 * nothing, never to a policy that deletes messages.
 */
class StoredOutgoingPolicyStore(
    private val store: KeyValueStore,
) : OutgoingPolicyStore {
    override fun layerFor(scope: PolicyScope): OutgoingPolicyLayer {
        val text = store.getString(keyFor(scope)) ?: return OutgoingPolicyLayer()
        return decodeLayer(text)
    }

    override fun save(
        scope: PolicyScope,
        layer: OutgoingPolicyLayer,
    ) {
        if (layer.isEmpty) {
            store.remove(keyFor(scope))
            return
        }
        store.putString(keyFor(scope), encodeLayer(layer))
    }

    override fun configuredScopes(): List<PolicyScope> =
        store
            .keys(KEY_PREFIX)
            .mapNotNull { PolicyScope.parse(it.removePrefix(KEY_PREFIX)) }
            .sortedBy { it.precedence }

    override fun clear() {
        store.keys(KEY_PREFIX).forEach { store.remove(it) }
    }

    private fun keyFor(scope: PolicyScope): String = KEY_PREFIX + scope.code

    private fun encodeLayer(layer: OutgoingPolicyLayer): String {
        val viewOnce = LinkedHashMap<String, JsonValue>()
        layer.viewOnce.forEach { (messageClass, choice) -> viewOnce[messageClass.name] = JsonValue.Str(choice.name) }
        return MiniJson.write(
            jsonObject(
                "viewOnce" to JsonValue.Obj(viewOnce),
                "autoDelete" to jsonString(layer.autoDelete.name),
                "autoDeleteDelay" to layer.autoDeleteDelayMillis?.let { jsonNumber(it) },
                "autoDeleteClasses" to
                    layer.autoDeleteClasses?.let { classes -> jsonStrings(classes.map { it.name }) },
                "expiredWindow" to jsonString(layer.expiredWindowFallback.name),
            ),
        )
    }

    private fun decodeLayer(text: String): OutgoingPolicyLayer {
        val fields = MiniJson.parse(text)?.objOrNull() ?: return OutgoingPolicyLayer()
        return OutgoingPolicyLayer(
            viewOnce = decodeViewOnce(fields.obj("viewOnce")),
            autoDelete = decodeAutoDelete(fields.string("autoDelete")),
            autoDeleteDelayMillis = fields.long("autoDeleteDelay")?.takeIf { it > 0L },
            autoDeleteClasses = decodeClasses(fields.array("autoDeleteClasses")),
            expiredWindowFallback = decodeExpiredWindow(fields.string("expiredWindow")),
        )
    }

    private fun decodeViewOnce(fields: Map<String, JsonValue>?): Map<OutgoingMessageClass, ViewOnceChoice> {
        if (fields.isNullOrEmpty()) return emptyMap()
        val decoded = LinkedHashMap<OutgoingMessageClass, ViewOnceChoice>()
        fields.forEach { (name, raw) ->
            val messageClass = OutgoingMessageClass.entries.firstOrNull { it.name == name } ?: return@forEach
            val choice = ViewOnceChoice.entries.firstOrNull { it.name == raw.stringOrNull() } ?: return@forEach
            decoded[messageClass] = choice
        }
        return decoded
    }

    private fun decodeClasses(names: List<JsonValue>?): Set<OutgoingMessageClass>? {
        if (names == null) return null
        return names
            .mapNotNull { it.stringOrNull() }
            .mapNotNull { name -> OutgoingMessageClass.entries.firstOrNull { it.name == name } }
            .toSet()
    }

    private fun decodeAutoDelete(name: String?): AutoDeleteChoice =
        AutoDeleteChoice.entries.firstOrNull { it.name == name } ?: AutoDeleteChoice.USE_PARENT

    private fun decodeExpiredWindow(name: String?): ExpiredWindowFallback =
        ExpiredWindowFallback.entries.firstOrNull { it.name == name } ?: ExpiredWindowFallback.DO_NOTHING

    companion object {
        /** Prefix for every stored outgoing policy. */
        const val KEY_PREFIX: String = "wae.outgoing.policy."
    }
}

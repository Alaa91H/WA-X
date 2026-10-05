package com.wax.module.automation

import com.wax.module.platform.KeyValueStore
import java.security.MessageDigest
import java.security.SecureRandom

/** The actions external automation may request through T103. */
enum class TaskerActionType(
    val displayName: String,
) {
    SEND_MESSAGE("Send message"),
    TOGGLE_PRIVACY_PROFILE("Toggle privacy profile"),
    TOGGLE_DND("Toggle do-not-disturb"),
    TOGGLE_FEATURE("Enable or disable a feature"),
    REQUEST_BACKUP("Request a backup"),
    OPEN_CONVERSATION("Open a conversation"),
    EXPORT_DIAGNOSTICS("Export diagnostics"),
}

/**
 * One external command.
 *
 * Payload values are untyped strings because that is what automation sources send; the
 * executor is responsible for validating them and, critically, for never treating them as
 * code. A command can ask for an action, not for arbitrary execution.
 */
data class TaskerCommand(
    val action: TaskerActionType,
    val payload: Map<String, String> = emptyMap(),
) {
    /** The payload value for [key], or null. */
    fun parameter(key: String): String? = payload[key]

    /** One line for the audit log; parameter values are deliberately not included. */
    fun toDisplayLine(): String = action.displayName

    companion object {
        /** Common payload keys, centralised so sender and receiver cannot drift. */
        const val PARAM_CHAT = "chat"
        const val PARAM_TEXT = "text"
        const val PARAM_PROFILE = "profile"
        const val PARAM_FEATURE = "feature"
        const val PARAM_ENABLED = "enabled"
        const val PARAM_PATH = "path"
    }
}

/**
 * The outcome of an external command. Authentication failures are distinct from execution
 * failures so the caller can tell "wrong token" from "the action did not work".
 */
sealed interface TaskerDispatchResult {
    /** The command was accepted and handed to the executor. */
    data class Accepted(
        val command: TaskerCommand,
    ) : TaskerDispatchResult

    /** The token was missing or wrong. */
    data object Unauthorized : TaskerDispatchResult

    /** The token was valid but has expired. */
    data object Expired : TaskerDispatchResult

    /** The executor refused or threw. */
    data class Failed(
        val message: String,
    ) : TaskerDispatchResult
}

/**
 * Owns the Tasker authentication token.
 *
 * The token is never stored in clear text: only its SHA-256 hash is persisted. That makes
 * reading the preferences file insufficient to issue commands, which matters because those
 * preferences are world-readable to any process that can reach the module's provider.
 * Rotation and revocation are first-class operations (T104): regenerating replaces the hash
 * and invalidates every previously issued token; revoking leaves the API closed until a new
 * token is generated.
 *
 * An optional expiry bounds a leaked token's lifetime without requiring the user to
 * remember to rotate it.
 */
class TaskerAuthenticator(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * Generates a new token, invalidating every previous one.
     *
     * This is the only way to obtain a clear-text token: storage keeps only the hash, so
     * the value is shown once at generation and never read back. Callers that just need to
     * know whether access is configured use [hasToken] or [describe].
     */
    fun rotate(): String {
        val token = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }.toHex()
        store.putString(KEY_TOKEN_HASH, sha256Hex(token))
        store.putLong(KEY_CREATED_AT, now())
        return token
    }

    /** Revokes the token; every command is unauthorized until a new token is generated. */
    fun revoke() {
        store.remove(KEY_TOKEN_HASH)
        store.remove(KEY_CREATED_AT)
        store.remove(KEY_EXPIRES_AT)
    }

    /** Whether any token is currently active. */
    fun hasToken(): Boolean = store.getString(KEY_TOKEN_HASH) != null

    /**
     * Whether [token] authenticates right now.
     *
     * Comparison is constant-time over the hashes so a wrong token cannot be discovered
     * byte by byte through timing.
     */
    fun isValid(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val stored = store.getString(KEY_TOKEN_HASH) ?: return false
        if (isExpired()) return false
        return MessageDigest.isEqual(
            stored.toByteArray(Charsets.US_ASCII),
            sha256Hex(token).toByteArray(Charsets.US_ASCII),
        )
    }

    /** Whether the stored token has expired. */
    fun isExpired(): Boolean {
        val expiresAt = store.getLong(KEY_EXPIRES_AT)
        return expiresAt > 0L && now() >= expiresAt
    }

    /** The expiry time, or 0 when the token does not expire. */
    fun expiresAt(): Long = store.getLong(KEY_EXPIRES_AT)

    /** Sets an expiry [durationMillis] from now, or removes it when null. */
    fun setExpiry(durationMillis: Long?) {
        if (durationMillis == null || durationMillis <= 0L) {
            store.remove(KEY_EXPIRES_AT)
        } else {
            store.putLong(KEY_EXPIRES_AT, now() + durationMillis)
        }
    }

    /** How the token was issued, for diagnostics; never the token itself. */
    fun describe(): String =
        when {
            !hasToken() -> "Tasker access is off (no token)."
            isExpired() -> "Tasker token expired."
            else -> "Tasker token active" + if (expiresAt() > 0L) ", expires at ${expiresAt()}." else "."
        }

    /** Drops token state. Used by tests and factory reset. */
    fun clear() {
        store.remove(KEY_TOKEN_HASH)
        store.remove(KEY_CREATED_AT)
        store.remove(KEY_EXPIRES_AT)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY_TOKEN_HASH = "wae.tasker.token_hash"
        private const val KEY_CREATED_AT = "wae.tasker.created_at"
        private const val KEY_EXPIRES_AT = "wae.tasker.expires_at"

        /** 256 bits of randomness. */
        const val TOKEN_BYTES: Int = 32

        /** Default token lifetime when expiry is enabled: 90 days. */
        const val DEFAULT_EXPIRY_MILLIS: Long = 90L * 24L * 60L * 60L * 1000L

        private fun sha256Hex(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** Runs one accepted command. May throw; the API converts that into [TaskerDispatchResult.Failed]. */
fun interface TaskerActionExecutor {
    fun execute(command: TaskerCommand)
}

/**
 * The authenticated entry point for external automation.
 *
 * The order is fixed: authenticate, then execute. The token is checked before the command's
 * action is even inspected, so an unauthenticated caller cannot use error messages to probe
 * which actions exist. The executor never receives the token and never sees unvalidated
 * payloads as anything but data.
 */
class TaskerApi(
    private val authenticator: TaskerAuthenticator,
    private val executor: TaskerActionExecutor,
) {
    /** Authenticates and runs [command]. */
    fun dispatch(
        token: String?,
        command: TaskerCommand,
    ): TaskerDispatchResult {
        if (!authenticator.hasToken()) return TaskerDispatchResult.Unauthorized
        if (authenticator.isExpired()) return TaskerDispatchResult.Expired
        if (!authenticator.isValid(token)) return TaskerDispatchResult.Unauthorized
        return try {
            executor.execute(command)
            TaskerDispatchResult.Accepted(command)
        } catch (error: Throwable) {
            TaskerDispatchResult.Failed("the action failed: ${error.javaClass.simpleName}")
        }
    }

    /** The action names the API exposes, for the settings screen and docs. */
    fun availableActions(): List<TaskerActionType> = TaskerActionType.entries.toList()
}

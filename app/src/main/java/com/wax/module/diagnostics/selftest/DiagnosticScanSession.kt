package com.wax.module.diagnostics.selftest

/**
 * Guard the existing DiagnosticEngine against concurrent Quick/Deep requests.
 * Tokens also prevent a previous worker from replacing the newer screen state.
 */
class DiagnosticScanSession {
    enum class Completion { FINISHED, CANCELLED, STALE }

    private var sequence = 0L
    private var active: Long? = null
    private var cancelRequested = false

    @Synchronized
    fun begin(): Long? {
        if (active != null) return null
        val token = ++sequence
        active = token
        cancelRequested = false
        return token
    }

    @Synchronized
    fun acceptsProgress(token: Long): Boolean = active == token && !cancelRequested

    @Synchronized
    fun cancel(): Boolean {
        if (active == null) return false
        cancelRequested = true
        return true
    }

    @Synchronized
    fun finish(token: Long): Completion {
        if (active != token) return Completion.STALE
        active = null
        return if (cancelRequested) Completion.CANCELLED else Completion.FINISHED
    }

    @Synchronized
    fun invalidate() {
        sequence++
        active = null
        cancelRequested = true
    }
}

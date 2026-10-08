package com.wax.module.contract

import com.wax.module.diagnostics.FailureCode
import com.wax.module.diagnostics.FeatureFailureReport

/**
 * Fakes for every contract, for JVM tests.
 *
 * They live in `main` rather than in `test` for one reason: the next thing to be unit tested is
 * the feature classes themselves, and a feature under test inside this repository's own
 * `androidTest` sources would need them on the instrumentation classpath too. Putting them beside
 * the contracts keeps one copy, and the whole package is pure Kotlin so there is nothing to
 * exclude from the release build.
 *
 * Each fake is hand-written rather than generated, for the same reason the JSON codec in
 * `com.wax.module.health` is: a generated double asserts against a signature, and the point of
 * these is to be the *opposite* of the real thing. [RecordingHookEngine] in particular is
 * deliberately simple - it records what was asked and does nothing - because a fake that
 * "simulates" behaviour is a second implementation to keep correct.
 */
object Fakes {
    /** A clock a test moves by hand. */
    class FakeClock(
        /** Wall-clock reading. */
        var now: Long = 1_000L,
        /** Uptime reading. */
        var elapsed: Long = 0L,
    ) : RuntimeClock {
        override fun nowMillis(): Long = now

        override fun elapsedRealtimeMillis(): Long = elapsed

        /** Advances both readings, which is what a test that measures a duration wants. */
        fun advance(millis: Long) {
            now += millis
            elapsed += millis
        }
    }

    /** A logger that keeps everything, so a test can assert what was said. */
    class RecordingLogger : RuntimeLogger {
        /** Every line, with `info` and `debug` distinguished by the prefix. */
        val lines: MutableList<String> = mutableListOf()

        /** How many lines [debug] was called with. */
        var debugCount: Int = 0
            private set

        override fun info(message: String) {
            lines += "info: $message"
        }

        override fun debug(message: String) {
            debugCount++
            lines += "debug: $message"
        }

        override fun error(
            message: String,
            throwable: Throwable?,
        ) {
            lines += "error: $message${throwable?.let { " (${it::class.java.simpleName})" }.orEmpty()}"
        }
    }

    /**
     * A hook engine that records what it was asked to do and installs nothing.
     *
     * [hookAttempts] is what a test asserts against, and [installed] is what a feature is told,
     * so a test can make a hook *fail* by leaving a class out of [availableClasses] - which is how
     * "this feature skips cleanly on an unsupported build" becomes a test rather than a claim.
     */
    class RecordingHookEngine(
        /** The class names this engine pretends the target has. */
        val availableClasses: Set<String> = emptySet(),
        /** Whether every hook succeeds. */
        private val succeeds: Boolean = true,
    ) : HookEngine {
        /** One entry per [hookMethod] call. */
        data class Attempt(
            val className: String,
            val methodName: String,
            val parameterTypes: List<Class<*>>,
            val after: Boolean,
            val callback: MethodCallback,
        )

        /** Everything the feature asked to hook, in order. */
        val hookAttempts: MutableList<Attempt> = mutableListOf()

        private var count = 0

        override fun hookMethod(
            className: String,
            methodName: String,
            parameterTypes: List<Class<*>>,
            after: Boolean,
            callback: MethodCallback,
        ): Boolean {
            hookAttempts += Attempt(className, methodName, parameterTypes, after, callback)
            val installed = succeeds && className in availableClasses
            if (installed) count++
            return installed
        }

        override fun hasClass(className: String): Boolean = className in availableClasses

        override fun installedCount(): Int = count

        /** Runs a recorded callback as the engine would, so its effect can be asserted. */
        fun fire(
            index: Int,
            thrown: Throwable? = null,
        ) {
            val attempt = hookAttempts[index]
            attempt.callback.invoke(beforeRun = false, thrown = thrown)
        }
    }

    /** A capability provider over a declared set of classes. */
    class FakeCapabilityProvider(
        /** The class names the target is pretended to have. */
        val availableClasses: Set<String> = emptySet(),
        /** The target's version name. */
        private val version: String? = "2.26.39.78",
        /** The Android level. */
        private val sdk: Int = 34,
        /** Whether the engine started. */
        private val resolution: Boolean = true,
    ) : CapabilityProvider {
        override fun hasClass(className: String): Boolean = className in availableClasses

        /**
         * A class object for [className], or null.
         *
         * Only names this JVM can actually load produce a class object, which is the honest
         * answer: a fake that manufactured a `Class` for a class it does not have would let a
         * feature pass a test it could never pass on a device. [availableClasses] still decides
         * [hasClass], so a test can still declare a target class this repository does not contain.
         */
        override fun findClass(className: String): Class<*>? =
            runCatching { Class.forName(className, false, Fakes::class.java.classLoader) }.getOrNull()

        override fun targetVersionName(): String? = version

        override fun androidSdkInt(): Int = sdk

        override fun resolutionAvailable(): Boolean = resolution
    }

    /** A diagnostic sink that records what it was told instead of storing it. */
    class RecordingDiagnosticSink : DiagnosticSink {
        /** Every report, in order. */
        val reports: MutableList<FeatureFailureReport> = mutableListOf()

        override fun report(
            featureId: String,
            code: FailureCode,
            message: String?,
            stage: String?,
        ): FeatureFailureReport {
            val stored =
                FeatureFailureReport(
                    featureId = featureId,
                    code = code,
                    moduleVersion = "test",
                    whatsappVersion = "test",
                    packageName = "com.whatsapp",
                    message = message,
                    resolver = stage,
                    timestampMillis = 0L,
                )
            reports += stored
            return stored
        }

        override fun reportThrowable(
            featureId: String,
            throwable: Throwable,
            stage: String?,
        ): FeatureFailureReport = report(featureId, FailureCode.classify(throwable, stage), throwable.message, stage)
    }
}

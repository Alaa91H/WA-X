package com.wax.module.xposed.contract

import com.wax.module.contract.CapabilityProvider
import com.wax.module.contract.HookEngine
import com.wax.module.contract.MethodCallback
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real hooking engine, over the framework's own API.
 *
 * This class is the *only* place in the module where `XposedHelpers` is called for a feature.
 * Fifty-seven feature files used to do it themselves; after this phase they go through
 * [HookEngine], which is what makes "did this feature install its hook" a question with an answer.
 *
 * Three behaviours are chosen here rather than inherited from the framework, and each one exists
 * because the framework's default loses information:
 *
 * * **A method that is not found returns `false` rather than throwing.** `findAndHookMethod`
 *   throws `NoSuchMethodError`; on an unsupported build that is an ordinary outcome and turning it
 *   into an exception per hook is how a feature ends up reported as broken on a build it simply
 *   does not support.
 * * **The hook count is real.** [installedCount] is incremented only when the framework confirms
 *   the hook, so a feature's `FeatureStartResult.Installed(hooks = …)` is a measurement.
 * * **A throwable from the original method reaches the callback** and is then allowed to
 *   propagate. Swallowing it here would make every feature silently convert a failure into a
 *   success, which is the exact defect this whole modernization programme exists to remove.
 */
class XposedHookEngine(
    private val classLoader: ClassLoader,
    private val log: (String) -> Unit,
) : HookEngine {
    private val installed = AtomicInteger(0)

    override fun hookMethod(
        className: String,
        methodName: String,
        parameterTypes: List<Class<*>>,
        after: Boolean,
        callback: MethodCallback,
    ): Boolean =
        try {
            val hook =
                XposedHelpers.findAndHookMethod(
                    className,
                    classLoader,
                    methodName,
                    *parameterTypes.toTypedArray(),
                    if (after) AfterHook(callback) else BeforeHook(callback),
                )
            val succeeded = hook != null
            if (succeeded) installed.incrementAndGet()
            succeeded
        } catch (notOnThisBuild: NoSuchMethodError) {
            // The exception itself is logged, not just a message about it. A build that renamed a
            // method is something a maintainer has to be able to see, and "hook skipped" on its
            // own reads like a module that did nothing for no reason.
            log("$className.$methodName is not present on this build; hook skipped")
            log(notOnThisBuild.toString())
            false
        } catch (notOnThisBuild: ClassNotFoundException) {
            log("$className is not present on this build; hook skipped")
            log(notOnThisBuild.toString())
            false
        }

    override fun hasClass(className: String): Boolean = runCatching { XposedHelpers.findClass(className, classLoader) }.isSuccess

    override fun installedCount(): Int = installed.get()

    /** Runs [callback] before the original method, leaving the arguments untouched. */
    private class BeforeHook(
        private val callback: MethodCallback,
    ) : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            callback.invoke(true, null)
        }
    }

    /**
     * Runs [callback] after the original method.
     *
     * The throwable is reported and then rethrown. It is rethrown rather than swallowed because
     * the original method's failure is the target's business, not the hook's, and a hook that
     * absorbs it converts a crash into silence.
     */
    private class AfterHook(
        private val callback: MethodCallback,
    ) : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val thrown = param.throwable
            callback.invoke(false, thrown)
            if (thrown != null) throw thrown
        }
    }
}

/**
 * What the target actually has, read through the resolver engine.
 *
 * [resolutionAvailable] is the honest answer to "is this build supported or is the engine broken",
 * which the previous arrangement could not give: a feature that needed a member and did not get
 * one reported an unsupported build whether the class was missing or the engine had failed to
 * start.
 */
class FrameworkCapabilityProvider(
    private val classLoader: ClassLoader,
    private val targetVersionName: String?,
    private val androidSdkInt: Int,
    private val resolutionAvailable: Boolean,
) : CapabilityProvider {
    override fun hasClass(className: String): Boolean = runCatching { Class.forName(className, false, classLoader) }.isSuccess

    override fun findClass(className: String): Class<*>? = runCatching { Class.forName(className, false, classLoader) }.getOrNull()

    override fun targetVersionName(): String? = targetVersionName

    override fun androidSdkInt(): Int = androidSdkInt

    override fun resolutionAvailable(): Boolean = resolutionAvailable
}

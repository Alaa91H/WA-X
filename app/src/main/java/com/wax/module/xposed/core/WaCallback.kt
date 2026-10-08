package com.wax.module.xposed.core

import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import com.wax.module.xposed.core.ModuleRuntime.ActivityChangeState
import com.wax.module.xposed.core.ModuleRuntime.listenerActivity
import java.util.function.Consumer

/**
 * The lifecycle callback every activity state in the module is derived from.
 *
 * Two things happen here and only two. The current activity is recorded, because that is a single
 * fact about the process; and the listeners registered by features are notified, because that is a
 * fan-out the runtime owns on their behalf.
 *
 * There was a third thing here: `ActivityStateRegistry`, a pair of synchronized maps keyed by
 * activity and by simple class name, written on every callback. Nothing ever read it. #336 A02
 * removed it rather than containing it, because a container for state nobody reads is not a
 * container, it is a leak with a data structure - and one of its maps held `WeakReference`s to
 * activities under a `HashMap`, which is not a cache but a way of keeping the names of destroyed
 * activities alive for the life of the process.
 */
class WaCallback : ActivityLifecycleCallbacks {
    override fun onActivityCreated(
        activity: Activity,
        bundle: Bundle?,
    ) {
        ModuleRuntime.mCurrentActivity = activity
        triggerActivityState(activity, ActivityChangeState.ChangeType.CREATED)
    }

    override fun onActivityStarted(activity: Activity) {
        ModuleRuntime.mCurrentActivity = activity
        triggerActivityState(activity, ActivityChangeState.ChangeType.STARTED)
    }

    override fun onActivityResumed(activity: Activity) {
        ModuleRuntime.mCurrentActivity = activity
        triggerActivityState(activity, ActivityChangeState.ChangeType.RESUMED)
    }

    override fun onActivityPaused(activity: Activity) {
        triggerActivityState(activity, ActivityChangeState.ChangeType.PAUSED)
    }

    override fun onActivityStopped(activity: Activity) {
        triggerActivityState(activity, ActivityChangeState.ChangeType.ENDED)
    }

    override fun onActivityDestroyed(activity: Activity) {
        triggerActivityState(activity, ActivityChangeState.ChangeType.DESTROYED)
    }

    override fun onActivitySaveInstanceState(
        activity: Activity,
        bundle: Bundle,
    ) = Unit

    companion object {
        private fun triggerActivityState(
            activity: Activity,
            type: ActivityChangeState.ChangeType,
        ) {
            listenerActivity.forEach(
                Consumer { listener: ActivityChangeState? ->
                    listener!!.onChange(
                        activity,
                        type,
                    )
                },
            )
        }
    }
}

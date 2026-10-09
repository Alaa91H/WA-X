package com.wax.module.modern

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.HeaderViewListAdapter
import android.widget.ListAdapter
import android.widget.ListView
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * API 102 port of the legacy ConversationItemListener conversation bus.
 *
 * Hooks the framework [ListView.setAdapter] (stable platform API, no DexKit
 * needed), then reflectively hooks the bound adapter's public
 * `getView(int, View, ViewGroup)` and fans out bound message rows to
 * registered consumers (six known legacy consumers migrate in later waves).
 * View→message bindings live in a [WeakHashMap], replacing the legacy
 * `XposedHelpers` additional instance fields with an explicit structure.
 *
 * The bus delivers the raw message object plus its row [ViewGroup]. Field
 * interpretation (message IDs, row IDs, JIDs) stays with each consumer
 * migration: those wrappers need their own resolver evidence and are not
 * re-derived here. No obfuscated member name is used — only the
 * `com.whatsapp.Conversation` activity name already proven in the legacy
 * runtime and the home-activity name from [ModernMenuHomePolicy].
 *
 * Always-on infrastructure with no user toggle: no in-WhatsApp control.
 */
object ModernConversationItemListenerFeature {
    const val FEATURE_ID = "conversation_item_listener"
    const val CONVERSATION_CLASS = "com.whatsapp.Conversation"
    private const val TAG = "WA-X ConversationBus102"

    enum class Outcome {
        INSTALLED,
        ALREADY_INSTALLED,
        APPLICATION_UNAVAILABLE,
        ERROR,
    }

    data class BoundConversationItem(
        val message: Any,
        val view: View,
    )

    /** Consumer callback, mirroring the legacy bus surface with raw payloads. */
    interface OnConversationItemListener {
        fun onItemBind(message: Any, view: ViewGroup, position: Int, convertView: View?)
        fun onAttachAdapter(adapter: ListAdapter?) {}
    }

    private val listeners = CopyOnWriteArraySet<OnConversationItemListener>()
    private val boundItems = WeakHashMap<View, BoundConversationItem>()
    private var adapter: ListAdapter? = null
    private var adapterActivity: WeakReference<Activity>? = null
    private var innerUnhook: ModernHookRegistry.Handle? = null
    private var lifecycleRegistered = false

    @Volatile
    private var currentActivity: WeakReference<Activity>? = null

    @Volatile
    private var bridge: ModernHookBridge? = null

    @JvmStatic
    fun addListener(listener: OnConversationItemListener) {
        listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: OnConversationItemListener) {
        listeners.remove(listener)
    }

    /** Visible for tests and consumers: the message bound to a row view, if any. */
    @JvmStatic
    fun boundMessage(view: View): Any? = synchronized(boundItems) { boundItems[view]?.message }

    @JvmStatic
    fun unwrapBaseAdapter(adapter: ListAdapter?): BaseAdapter? {
        var current: Any? = adapter ?: return null
        if (current is HeaderViewListAdapter) current = current.wrappedAdapter
        if (current is BaseAdapter) return current
        return current?.javaClass?.declaredFields?.firstNotNullOfOrNull { field ->
            if (BaseAdapter::class.java.isAssignableFrom(field.type)) {
                try {
                    field.isAccessible = true
                    field.get(current) as? BaseAdapter
                } catch (ignored: Throwable) {
                    null
                }
            } else {
                null
            }
        }
    }

    @JvmStatic
    fun notifyDataSetChanged() {
        val snapshot = adapter
        Handler(Looper.getMainLooper()).post {
            unwrapBaseAdapter(snapshot)?.notifyDataSetChanged()
        }
    }

    /** Conversation check ported from the legacy runtime (exact activity name). */
    @JvmStatic
    fun isConversation(activity: Activity?): Boolean {
        if (activity == null) return false
        if (activity.javaClass.name == CONVERSATION_CLASS) return true
        return activity.resources.configuration.smallestScreenWidthDp >= 600 &&
            activity.javaClass.name == ModernMenuHomePolicy.homeClassName(activity.packageName)
    }

    @JvmStatic
    fun install(
        target: Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
    ): Outcome {
        val application = target.applicationContext as? Application
            ?: return Outcome.APPLICATION_UNAVAILABLE
        synchronized(this) {
            if (!lifecycleRegistered) {
                application.registerActivityLifecycleCallbacks(LifecycleTracker())
                lifecycleRegistered = true
            }
        }
        val setAdapter = try {
            ListView::class.java.getDeclaredMethod("setAdapter", ListAdapter::class.java)
        } catch (missing: NoSuchMethodException) {
            return Outcome.ERROR
        }
        bridge = ModernHookBridge(framework)
        val installed = try {
            hooks.installOnce(FEATURE_ID,
                ModernHookRegistry.Registration("conversation.set_adapter") {
                    val handle = (bridge ?: ModernHookBridge(framework)).intercept(
                        setAdapter, "wax.modern.conversation_item.set_adapter") { chain ->
                        onSetAdapter(chain.thisObject as? ListView, chain.args.firstOrNull())
                        chain.proceed()
                    }
                    ModernHookRegistry.Handle { handle.unhook() }
                })
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Conversation bus unavailable", failure)
            return Outcome.ERROR
        }
        return if (installed) Outcome.INSTALLED else Outcome.ALREADY_INSTALLED
    }

    private fun onSetAdapter(listView: ListView?, rawAdapter: Any?) {
        if (listeners.isEmpty()) return
        val conversation = currentActivity?.get()
        if (!isConversation(conversation)) return
        if (listView == null || listView.id != android.R.id.list) return
        var current = rawAdapter as? ListAdapter ?: return
        if (current is HeaderViewListAdapter) current = current.wrappedAdapter ?: return
        adapter = current
        adapterActivity = WeakReference(conversation)
        for (listener in listeners) {
            try {
                listener.onAttachAdapter(current)
            } catch (listenerFailure: Throwable) {
                Log.w(TAG, "Conversation attach listener failed", listenerFailure)
            }
        }
        rehookGetView(current)
    }

    private fun rehookGetView(current: ListAdapter) {
        try {
            innerUnhook?.unhook()
        } catch (unhookFailure: Throwable) {
            Log.w(TAG, "Previous row hook would not release", unhookFailure)
        } finally {
            innerUnhook = null
        }
        val getView = try {
            current.javaClass.getMethod(
                "getView",
                Int::class.javaPrimitiveType,
                View::class.java,
                ViewGroup::class.java,
            )
        } catch (missing: NoSuchMethodException) {
            Log.w(TAG, "Row method missing on " + current.javaClass.name)
            return
        }
        // Inner hook is per-adapter and short-lived by design, like the legacy
        // re-hook on every setAdapter; lifecycle is owned here, not the registry.
        val captured = bridge
        if (captured == null) {
            Log.w(TAG, "Row hook has no installing bridge")
            return
        }
        try {
            val handle = captured.intercept(
                getView, "wax.modern.conversation_item.get_view") { chain ->
                val result = chain.proceed()
                if (listeners.isEmpty()) return@intercept result
                val active = adapter ?: return@intercept result
                if (chain.thisObject !== active) return@intercept result
                val args = chain.args
                if (args.size < 2) return@intercept result
                val position = args[0] as? Int ?: return@intercept result
                val convertView = args[1] as? View
                val row = result as? ViewGroup ?: return@intercept result
                val message = try {
                    active.getItem(position)
                } catch (itemFailure: Throwable) {
                    return@intercept result
                } ?: return@intercept result
                synchronized(boundItems) {
                    boundItems[row] = BoundConversationItem(message, row)
                }
                for (listener in listeners) {
                    try {
                        listener.onItemBind(message, row, position, convertView)
                    } catch (listenerFailure: Throwable) {
                        Log.w(TAG, "Conversation row listener failed", listenerFailure)
                    }
                }
                result
            }
            innerUnhook = ModernHookRegistry.Handle { handle?.unhook() }
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Row hook unavailable on " + current.javaClass.name, failure)
        }
    }

    private inner class LifecycleTracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            currentActivity = WeakReference(activity)
        }
        override fun onActivityDestroyed(activity: Activity) {
            if (adapterActivity?.get() === activity) {
                try {
                    innerUnhook?.unhook()
                } catch (unhookFailure: Throwable) {
                    Log.w(TAG, "Row hook would not release on destroy", unhookFailure)
                } finally {
                    innerUnhook = null
                }
                adapter = null
                adapterActivity = null
                synchronized(boundItems) { boundItems.clear() }
            }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

}

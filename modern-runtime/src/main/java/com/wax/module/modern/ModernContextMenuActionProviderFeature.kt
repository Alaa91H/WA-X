package com.wax.module.modern

import android.app.Activity
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.CopyOnWriteArraySet
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * API 102 port of the legacy ContextMenuActionProvider.
 *
 * Resolves WhatsApp's message-selection popup from the repo-derived evidence
 * the legacy resolver uses (the `MessageSelectionDropDownRecyclerView` string
 * with a `PopupWindow` superclass), then restructures its reactions tray into
 * a horizontal pill row plus a scrollable action row and lets registered
 * providers contribute actions.
 *
 * Two deliberate differences from the legacy implementation:
 *
 * - **Framework widgets only.** The legacy version builds Material buttons
 *  from the module's own resources and an AndroidX view helper. The modern
 *  runtime module carries no AndroidX dependency by design, so pills are
 *  plain framework `TextView`s. This keeps the module free of theme and
 *  context surprises inside WhatsApp instead of adding AndroidX to reach for
 *  a rounded outline style.
 * - **Raw message object.** The `FMessageWpp` wrapper and its JID chain need
 *  their own resolver evidence, so consumers receive the raw message and
 *  interpret it themselves.
 *
 * Always-on infrastructure with no user toggle: the bus is a no-op while no
 * provider is registered.
 */
object ModernContextMenuActionProviderFeature {
    const val FEATURE_ID = "context_menu_action_provider"
    const val ANCHOR_POPUP = "MessageSelectionDropDownRecyclerView"
    const val TRAY_RESOURCE_ID = "reactions_tray_layout"
    private const val TAG = "WA-X ContextMenu102"

    enum class Outcome {
        INSTALLED,
        RESOLVER_MISSING,
        RESOLVER_AMBIGUOUS,
        ERROR,
    }

    data class ContextMenuAction(
        val title: String,
        val autoDismiss: Boolean = true,
        val onClick: () -> Unit,
    )

    fun interface Provider {
        /** Returns null to contribute nothing for this message. */
        fun createAction(activity: Activity, popupWindow: PopupWindow, message: Any): ContextMenuAction?
    }

    private val providers = CopyOnWriteArraySet<Provider>()

    @Volatile
    private var currentActivity: WeakActivity? = null

    @JvmStatic
    fun register(provider: Provider) {
        providers.add(provider)
    }

    @JvmStatic
    fun unregister(provider: Provider) {
        providers.remove(provider)
    }

    /** Track the resumed Activity so a popup knows its host. */
    @JvmStatic
    fun trackActivity(activity: Activity?) {
        currentActivity = activity?.let { WeakActivity(it) }
    }

    @JvmStatic
    fun install(
        target: android.content.Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
    ): Outcome {
        val classLoader = target.classLoader
        val popupClass = try {
            DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
                val matches = dex.findClass {
                    matcher {
                        addUsingString(ANCHOR_POPUP, StringMatchType.Contains)
                        superClass = PopupWindow::class.java.name
                    }
                }
                when (matches.size) {
                    0 -> return Outcome.RESOLVER_MISSING
                    1 -> matches[0].getInstance(classLoader)
                    else -> return Outcome.RESOLVER_AMBIGUOUS
                }
            }
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Selection popup resolver unavailable", failure)
            return Outcome.RESOLVER_MISSING
        }
        val constructors = popupClass.declaredConstructors
        if (constructors.isEmpty()) return Outcome.RESOLVER_MISSING
        try {
            hooks.installFeature(
                FEATURE_ID,
                constructors.mapIndexed { index, constructor ->
                    ModernHookRegistry.Registration("context_menu.popup.$index") {
                        val handle = ModernHookBridge(framework).intercept(
                            constructor, "wax.modern.context_menu.popup.$index",
                        ) { chain ->
                            val popup = chain.thisObject as? PopupWindow
                            val result = chain.proceed()
                            if (popup != null) {
                                publish(popup, chain.args.toList())
                            }
                            result
                        }
                        ModernHookRegistry.Handle { handle.unhook() }
                    }
                },
            )
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Context-menu hook unavailable", failure)
            return Outcome.ERROR
        }
        return Outcome.INSTALLED
    }

    private fun publish(popup: PopupWindow, args: List<Any?>) {
        if (providers.isEmpty()) return
        val activity = currentActivity?.get() ?: return
        val viewGroup = popup.contentView as? ViewGroup ?: return
        // The message is the constructor argument that is not a Context or a
        // CharSequence; the legacy code used its own typed wrapper for the same
        // object and consumers still interpret it themselves.
        val message = args.firstOrNull { arg ->
            arg != null && arg !is CharSequence && arg !is android.content.Context
        } ?: return
        val trayId = try {
            viewGroup.resources.getIdentifier(TRAY_RESOURCE_ID, "id", viewGroup.context.packageName)
        } catch (failure: Throwable) {
            Log.w(TAG, "Tray resource lookup failed", failure)
            0
        }
        if (trayId <= 0) return
        val tray = viewGroup.findViewById(trayId) as? LinearLayout ?: return
        val buttonRow = prepareTray(viewGroup, tray)
        for (provider in providers) {
            try {
                val action = provider.createAction(activity, popup, message) ?: continue
                buttonRow.addView(
                    buildPill(activity, action.title) {
                        if (action.autoDismiss) runCatching { popup.dismiss() }
                        action.onClick()
                    },
                )
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                Log.w(TAG, "Context-menu provider failed", failure)
            }
        }
    }

    /**
     * Moves the existing reactions into a horizontal row and adds a scrollable
     * action row, mirroring the legacy layout without duplicating it: running
     * twice must not nest rows, so an already-wrapped tray is reused.
     */
    private fun prepareTray(viewGroup: ViewGroup, tray: LinearLayout): LinearLayout {
        tray.orientation = LinearLayout.VERTICAL
        // A popup can be rebuilt repeatedly: reuse the row we already created
        // instead of nesting another wrapper into the tray.
        val existingRow = tray.findViewWithTag<LinearLayout>(TAG_ACTION_ROW)
        if (existingRow != null) return existingRow
        val children = (0 until tray.childCount).map { tray.getChildAt(it) }
        tray.removeAllViews()
        val reactions = LinearLayout(viewGroup.context).apply {
            orientation = LinearLayout.HORIZONTAL
            children.forEach { addView(it) }
        }
        tray.addView(reactions)
        val actionRow = LinearLayout(viewGroup.context).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = TAG_ACTION_ROW
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        tray.addView(ScrollView(viewGroup.context).apply { addView(actionRow) })
        return actionRow
    }

    private fun buildPill(activity: Activity, title: String, onClick: () -> Unit) =
        android.widget.TextView(activity).apply {
            text = title
            setPadding(PILL_PADDING_DP.times(4), PILL_PADDING_DP.times(2),
                PILL_PADDING_DP.times(4), PILL_PADDING_DP.times(2))
            isClickable = true
            isFocusable = true
            contentDescription = title
            setOnClickListener { onClick() }
        }

    /** A weak reference that also tolerates a cleared Activity. */
    private class WeakActivity(activity: Activity) {
        private val reference = java.lang.ref.WeakReference(activity)
        fun get(): Activity? = reference.get()
    }

    private const val PILL_PADDING_DP = 8
    private const val TAG_ACTION_ROW = "wax.context_menu.actions"
}
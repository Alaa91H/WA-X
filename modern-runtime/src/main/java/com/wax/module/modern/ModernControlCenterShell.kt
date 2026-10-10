package com.wax.module.modern

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The embedded WA X Control Center (#433): the primary in-WhatsApp surface for
 * migrated features.
 *
 * Built in-process with framework widgets only, because the modern runtime
 * module deliberately carries no AndroidX dependency and must not pull theme
 * or context surprises into WhatsApp. Every row is a real control wired to a
 * real preference through the authenticated settings channel; rows whose
 * adapter is not migrated yet are rendered inert in a dedicated pending area
 * with an explicit "pending migration" status.
 *
 * Failure policy: any construction or inflation problem is caught and returned
 * to the menu owner, which launches the Manager once. This UI must never be
 * able to crash WhatsApp.
 */
class ModernControlCenterShell(
    private val activity: Activity,
    private val packageName: String,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "wax-api102-control-center-write").apply { isDaemon = true }
    }
    private val taskScope = newTaskScope(writer, mainHandler)
    private val disposed = AtomicBoolean(false)

    /** Follows the device language WhatsApp is already running in. */
    private val strings = ControlCenterStrings.forLanguage(
        activity.resources.configuration.locales.get(0).language,
    )

    private var favorites = emptySet<String>()
    private var favoritesOnly = false
    private val currentModes = HashMap<String, String>()

    private val primary = themeColor(android.R.attr.textColorPrimary, Color.WHITE)
    private val secondary = themeColor(android.R.attr.textColorSecondary, Color.LTGRAY)

    private var dialog: Dialog? = null
    private var dialogLifecycle: ActivityBoundDialogLifecycle? = null
    private var managerRefresh: (() -> Unit)? = null
    private var settingsObserver: ContentObserver? = null

    /** Shows one centre per target process/activity. Returns false for one Manager fallback. */
    private fun show(): Boolean {
        if (!isHostUsable()) {
            Log.w(TAG, "WINDOW_FAILED package=$packageName reason=ACTIVITY_UNAVAILABLE")
            disposeWithoutWindow(ActivityDialogCloseReason.SHOW_FAILED)
            return false
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w(TAG, "WINDOW_FAILED package=$packageName reason=MAIN_THREAD_REQUIRED")
            disposeWithoutWindow(ActivityDialogCloseReason.SHOW_FAILED)
            return false
        }
        if (dialogLifecycle?.isActive == true && dialog?.isShowing == true) {
            Log.i(TAG, "WINDOW_REUSED package=$packageName")
            return true
        }
        return try {
            buildAndShow()
            true
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            val reason = ControlCenterFailureReason.fromClassName(failure.javaClass.name)
            Log.w(TAG,
                "WINDOW_FAILED package=$packageName reason=$reason exception=${failure.javaClass.simpleName}")
            dialogLifecycle?.closeForShowFailure()
                ?: disposeWithoutWindow(ActivityDialogCloseReason.SHOW_FAILED)
            false
        }
    }

    private fun isReusableFor(host: Activity): Boolean =
        activity === host && isHostUsable() && !disposed.get() &&
            dialogLifecycle?.isActive == true && dialog?.isShowing == true

    private fun isHostUsable(): Boolean =
        activity.packageName == packageName && !activity.isFinishing && !activity.isDestroyed

    private fun closeForReplacement() {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        dialogLifecycle?.closeForReplacement()
            ?: disposeWithoutWindow(ActivityDialogCloseReason.REPLACED)
    }

    private fun buildAndShow() {
        val states = ModernTargetStateClient.read(activity, packageName)
        favorites = ModernControlCenterCatalog.parseFavorites(
            states.getString("pref." + ModernControlCenterCatalog.FAVORITES_KEY, null),
        )
        currentModes[ModernHideChatFeature.PREF_ARCHIVE_MODE] =
            states.getString("state." + ModernHideChatFeature.PREF_ARCHIVE_MODE, null)
                ?: readModeFromState(states)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val header = TextView(activity).apply {
            text = strings.title
            setTextColor(primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        root.addView(header)

        val favoritesFilter = CheckBox(activity).apply {
            text = strings.favoritesOnly
            isChecked = favoritesOnly
            setTextColor(secondary)
        }
        val search = EditText(activity).apply {
            hint = strings.searchHint
            setTextColor(primary)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        root.addView(search)
        root.addView(favoritesFilter)

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(activity).apply {
            addView(content)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        root.addView(scroll)

        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val restart = Button(activity).apply {
            text = strings.restart
            setOnClickListener { if (isWindowInteractive()) restartWhatsApp() }
        }
        val manager = Button(activity).apply {
            text = strings.openManager
            setOnClickListener { fallbackToManager() }
        }
        footer.addView(restart)
        footer.addView(manager)
        root.addView(footer)

        var rowsInOrder = buildEntries(states, "")

        fun render(query: String) {
            if (!isShellAlive()) return
            content.removeAllViews()
            val matching = rowsInOrder.filter { ControlPolicy.matches(it, query) }
            val filtered = if (favoritesOnly) matching.filter { it.id in favorites } else matching
            val entries = ControlPolicy.group(filtered, query)
            if (entries.isEmpty()) {
                content.addView(TextView(activity).apply {
                    text = strings.noResults
                    setTextColor(secondary)
                    setPadding(0, dp(12), 0, dp(12))
                })
                return
            }
            val favouriteRows = if (favoritesOnly) emptyList() else
                matching.filter { it.id in favorites }
            if (favouriteRows.isNotEmpty()) {
                content.addView(sectionHeader(strings.favorites))
                for (row in favouriteRows) content.addView(rowView(row))
            }
            val favouriteIds = favouriteRows.map { it.id }.toSet()
            for ((category, rows) in entries) {
                content.addView(sectionHeader(ControlStatusText.categoryTitle(category)))
                for (row in rows) {
                    if (row.id in favouriteIds) continue
                    content.addView(rowView(row))
                }
            }
        }

        favoritesFilter.setOnCheckedChangeListener { button, checked ->
            if (!isShellAlive() || !button.isPressed) return@setOnCheckedChangeListener
            favoritesOnly = checked
            render(search.text.toString())
        }
        render("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isShellAlive()) return
                try {
                    render(s?.toString().orEmpty())
                } catch (failure: Throwable) {
                    if (failure is VirtualMachineError) throw failure
                    Log.w(TAG,
                        "WINDOW_RENDER_FAILED package=$packageName exception=${failure.javaClass.simpleName}")
                }
            }
        })

        // Manager writes reach this dialog through the same provider notification URI.
        // Re-read on the writer worker, never with a Binder roundtrip on WhatsApp UI.
        var refreshInFlight = false
        var refreshQueued = false
        val readContext = activity.applicationContext
        fun refreshSettings() {
            if (!isWindowInteractive()) return
            if (refreshInFlight) {
                refreshQueued = true
                return
            }
            refreshInFlight = true
            val latest = java.util.concurrent.atomic.AtomicReference<Bundle?>()
            val accepted = taskScope.submit(
                operation = {
                    latest.set(ModernTargetStateClient.read(readContext, packageName))
                    true
                },
                onComplete = { _ ->
                    refreshInFlight = false
                    if (!isWindowInteractive()) return@submit
                    val updated = latest.get()
                    if (updated?.getBoolean("accepted", false) == true) {
                        favorites = ModernControlCenterCatalog.parseFavorites(
                            updated.getString("pref." + ModernControlCenterCatalog.FAVORITES_KEY, null),
                        )
                        currentModes[ModernHideChatFeature.PREF_ARCHIVE_MODE] = readModeFromState(updated)
                        rowsInOrder = buildEntries(updated, "")
                        render(search.text.toString())
                    } else {
                        Log.w(TAG, "CONTROL_CENTER_REFRESH_FAILED package=$packageName")
                    }
                    if (refreshQueued) {
                        refreshQueued = false
                        refreshSettings()
                    }
                },
            )
            if (!accepted) refreshInFlight = false
        }
        managerRefresh = { refreshSettings() }
        showDialog(root)
        startObservingSettings()
    }

    /** Listen only while this dialog is visible; never poll WhatsApp or its database. */
    private fun startObservingSettings() {
        if (settingsObserver != null) return
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                managerRefresh?.invoke()
            }
        }
        try {
            activity.contentResolver.registerContentObserver(
                ModernTargetStateClient.STATES_URI, false, observer,
            )
            settingsObserver = observer
        } catch (failure: RuntimeException) {
            Log.w(TAG, "CONTROL_CENTER_OBSERVER_REGISTER_FAILED", failure)
        }
    }

    private fun stopObservingSettings() {
        managerRefresh = null
        val observer = settingsObserver ?: return
        settingsObserver = null
        try {
            activity.contentResolver.unregisterContentObserver(observer)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "CONTROL_CENTER_OBSERVER_REMOVE_FAILED", failure)
        }
    }
    private fun showDialog(root: View) {
        val window = Dialog(activity)
        window.setContentView(root)
        window.setTitle(strings.title)
        dialog = window

        val application = activity.application
        var callbackRegistered = false
        lateinit var lifecycle: ActivityBoundDialogLifecycle
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(candidate: Activity, state: Bundle?) {}
            override fun onActivityStarted(candidate: Activity) {}
            override fun onActivityResumed(candidate: Activity) {
                if (candidate === activity) managerRefresh?.invoke()
            }
            override fun onActivityPaused(candidate: Activity) {}
            override fun onActivityStopped(candidate: Activity) {
                lifecycle.onActivityStopped(candidate)
            }
            override fun onActivitySaveInstanceState(candidate: Activity, state: Bundle) {}
            override fun onActivityDestroyed(candidate: Activity) {
                lifecycle.onActivityDestroyed(candidate)
            }
        }
        val handler = mainHandler
        lifecycle = ActivityBoundDialogLifecycle(
            hostActivity = activity,
            dispatchToMain = { action ->
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    action()
                    true
                } else {
                    handler.post(action)
                }
            },
            dismissDialog = {
                if (window.isShowing) window.dismiss()
            },
            unregister = {
                if (callbackRegistered) {
                    callbackRegistered = false
                    application.unregisterActivityLifecycleCallbacks(callbacks)
                }
            },
            onClosed = { reason -> finishSession(window, lifecycle, reason) },
            onCleanupFailure = { reason, code ->
                Log.w(TAG, "$code package=$packageName reason=$reason")
            },
            onMainDispatchRejected = { reason ->
                Log.e(TAG, "WINDOW_CLOSE_FAILED package=$packageName reason=MAIN_DISPATCH_REJECTED close=$reason")
            },
        )
        dialogLifecycle = lifecycle
        window.setOnDismissListener { lifecycle.onDialogDismissed() }

        application.registerActivityLifecycleCallbacks(callbacks)
        callbackRegistered = true
        Log.i(TAG, "WINDOW_CREATED package=$packageName")
        window.show()
        if (!window.isShowing) throw IllegalStateException("Dialog did not attach")
        Log.i(TAG, "WINDOW_SHOWN package=$packageName")
    }

    private fun finishSession(
        window: Dialog,
        lifecycle: ActivityBoundDialogLifecycle,
        reason: ActivityDialogCloseReason,
    ) {
        if (!disposed.compareAndSet(false, true)) return
        stopObservingSettings()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            taskScope.close()
        } catch (_: RuntimeException) {
            Log.w(TAG, "CONTROL_CENTER_TASK_SHUTDOWN_FAILED package=$packageName")
        }
        if (dialog === window) dialog = null
        if (dialogLifecycle === lifecycle) dialogLifecycle = null
        sessions.release(packageName, this)
        Log.i(TAG, "WINDOW_CLOSED package=$packageName reason=$reason")
    }

    private fun disposeWithoutWindow(reason: ActivityDialogCloseReason) {
        if (!disposed.compareAndSet(false, true)) return
        stopObservingSettings()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            taskScope.close()
        } catch (_: RuntimeException) {
            Log.w(TAG, "CONTROL_CENTER_TASK_SHUTDOWN_FAILED package=$packageName")
        }
        sessions.release(packageName, this)
        Log.i(TAG, "WINDOW_CLOSED package=$packageName reason=$reason")
    }

    private fun isShellAlive(): Boolean = !disposed.get() && isHostUsable()

    private fun isWindowInteractive(): Boolean =
        isShellAlive() && dialogLifecycle?.isActive == true && dialog?.isShowing == true

    /** Kept as the row-action boundary used by the in-flight diagnostics PR. */
    private fun fallbackToManager() {
        if (isWindowInteractive()) ModernManagerFallback.open(activity)
    }

    private fun sectionHeader(label: String): View = TextView(activity).apply {
        text = label
        setTextColor(secondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun buildEntries(states: Bundle, query: String): List<ControlEntry> {
        val stateAccepted = states.getBoolean("accepted", false)
        val rows = ArrayList<ControlEntry>()
        for (item in ModernControlCenterCatalog.wired) {
            val requested = when {
                !stateAccepted || item.preferenceKey.isEmpty() ||
                    !states.containsKey("pref." + item.preferenceKey) -> ControlRequested.UNKNOWN
                readBoolean(states, item.preferenceKey) -> ControlRequested.ENABLED
                else -> ControlRequested.DISABLED
            }
            val reported = readString(states, item.evidenceKey)
            val effective = if (!stateAccepted) ControlEffective.ERROR else
                ControlPolicy.effectiveFrom(reported, false, requested)
            rows.add(
                ControlEntry(
                    id = item.id,
                    title = item.label,
                    description = item.description,
                    category = item.category,
                    preferenceKey = item.preferenceKey.ifEmpty { null },
                    requested = requested,
                    effective = effective,
                    writable = stateAccepted && requested != ControlRequested.UNKNOWN &&
                        item.preferenceKey.isNotEmpty() &&
                        ControlPolicy.isWritable(item.preferenceKey, effective),
                    restartRequired = item.restartHint && requested == ControlRequested.ENABLED &&
                        effective != ControlEffective.INSTALLED && effective != ControlEffective.WORKING,
                ),
            )
        }
        for (item in ModernControlCenterCatalog.pending) {
            rows.add(
                ControlEntry(
                    id = item.id,
                    title = item.label,
                    description = "Not migrated to the modern runtime yet",
                    category = ControlCategory.PENDING,
                    preferenceKey = null,
                    requested = ControlRequested.UNKNOWN,
                    effective = ControlEffective.PENDING_MIGRATION,
                    writable = false,
                    restartRequired = false,
                ),
            )
        }
        return rows.filter { ControlPolicy.matches(it, query) }
    }

    private fun rowView(row: ControlEntry): View {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val label = TextView(activity).apply {
            text = row.title
            setTextColor(primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val status = TextView(activity).apply {
            text = ControlStatusText.status(row.effective)
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        if (row.id == "diagnostics") {
            // Rendered before the inert-row rule: this row is an action, not a
            // switch, so hiding it from accessibility services would be wrong.
            // The self-test engine lives in the Manager, where the sanitized
            // export and the SAF writer are; this row is the in-WhatsApp door.
            container.addView(
                Button(activity).apply {
                    text = strings.runDiagnostics
                    isAllCaps = false
                    contentDescription = strings.runDiagnostics
                    setOnClickListener { fallbackToManager() }
                },
            )
            container.addView(
                TextView(activity).apply {
                    text = row.description
                    setTextColor(secondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                },
            )
            return container
        }
        if (!row.writable) {
            // Pending / failed rows are inert by design; make that explicit to
            // accessibility services instead of a dead switch.
            container.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val modeControl = row.preferenceKey == ModernHideChatFeature.PREF_ARCHIVE_MODE
        if (modeControl) {
            // A three-state mode, not an on/off switch: tapping cycles
            // disabled -> hide -> hold, which is what the Manager list offers.
            val button = Button(activity).apply {
                isAllCaps = false
                contentDescription = row.title
                setOnClickListener {
                    if (isWindowInteractive()) cycleArchiveMode(row, status)
                }
            }
            container.addView(button)
            status.text = "${row.description} · ${archiveModeLabel(row)}" +
                if (row.requiresRestart) " · restart required" else ""
            container.addView(status)
            return container
        }
        if (row.writable && row.preferenceKey != null) {
            val toggle = Switch(activity).apply {
                text = row.title
                isChecked = row.requested == ControlRequested.ENABLED
                contentDescription = "${row.title}. ${ControlStatusText.status(row.effective)}"
                setOnCheckedChangeListener { button, isChecked ->
                    if (!isWindowInteractive() || !button.isPressed) return@setOnCheckedChangeListener
                    val key = row.preferenceKey
                    persist(key, isChecked, button as Switch, status, row)
                }
            }
            container.addView(toggle)
            status.text = "${row.description} · ${ControlStatusText.status(row.effective)}" +
                if (row.requiresRestart) " · restart required" else ""
        } else {
            container.addView(label)
            status.text = "${row.description} · ${ControlStatusText.status(row.effective)}"
        }
        container.addView(status)
        if (row.writable) {
            val star = Button(activity).apply {
                text = if (row.id in favorites) strings.favoriteToggleOn else strings.favoriteToggleOff
                isAllCaps = false
                contentDescription = strings.markFavorite
                setOnClickListener {
                    if (isWindowInteractive()) toggleFavorite(row)
                }
            }
            container.addView(star)
        }
        return container
    }

    private fun archiveModeLabel(row: ControlEntry): String {
        val stored = currentModes[row.id] ?: ModernHideChatFeature.MODE_DISABLED
        return when (stored) {
            "1" -> strings.hideAfterClicks
            "2" -> strings.hideWhileHolding
            else -> strings.disabled
        }
    }

    private fun cycleArchiveMode(row: ControlEntry, status: TextView) {
        if (!isWindowInteractive()) return
        val stored = currentModes[row.id] ?: ModernHideChatFeature.MODE_DISABLED
        val next = when (stored) {
            ModernHideChatFeature.MODE_DISABLED -> ModernHideChatFeature.MODE_CLICK_TIMES
            "1" -> "2"
            else -> ModernHideChatFeature.MODE_DISABLED
        }
        val context = activity.applicationContext
        val targetPackage = packageName
        val weakShell = WeakReference(this)
        val weakStatus = WeakReference(status)
        taskScope.submit(
            operation = { ModernTargetSettingsClient.writeMode(context, targetPackage, next) },
            onComplete = { saved ->
                val shell = weakShell.get() ?: return@submit
                val statusView = weakStatus.get() ?: return@submit
                if (!shell.isWindowInteractive()) return@submit
                if (saved) {
                    shell.currentModes[row.id] = next
                    statusView.text = "${row.description} · ${shell.archiveModeLabel(row)} · restart required"
                } else {
                    statusView.text = "${row.description} · " +
                        ControlStatusText.status(ControlEffective.ERROR)
                }
            }
        )
    }

    private fun toggleFavorite(row: ControlEntry) {
        if (!isWindowInteractive()) return
        val next = if (row.id in favorites) favorites - row.id else favorites + row.id
        val context = activity.applicationContext
        val targetPackage = packageName
        val serialized = ModernControlCenterCatalog.formatFavorites(next)
        val weakShell = WeakReference(this)
        taskScope.submit(
            operation = { ModernTargetSettingsClient.writeFavorites(context, targetPackage, serialized) },
            onComplete = { saved ->
                val shell = weakShell.get() ?: return@submit
                if (saved && shell.isWindowInteractive()) {
                    shell.favorites = next
                    Log.i(TAG, "CONTROL_CENTER_FAVORITE_SAVED")
                } else if (!saved) {
                    Log.w(TAG, "CONTROL_CENTER_FAVORITE_SAVE_FAILED")
                }
            }
        )
    }

    private fun persist(
        key: String,
        enabled: Boolean,
        toggle: Switch,
        status: TextView,
        row: ControlEntry,
    ) {
        if (!isWindowInteractive()) return
        toggle.isEnabled = false
        val context = activity.applicationContext
        val targetPackage = packageName
        val weakShell = WeakReference(this)
        val weakStatus = WeakReference(status)
        val weakToggle = WeakReference(toggle)
        val accepted = taskScope.submit(
            operation = { ModernTargetSettingsClient.write(context, targetPackage, key, enabled) },
            onComplete = { saved ->
                val shell = weakShell.get() ?: return@submit
                val statusView = weakStatus.get() ?: return@submit
                val switchView = weakToggle.get() ?: return@submit
                if (!shell.isWindowInteractive()) return@submit
                switchView.isEnabled = true
                if (!saved) {
                    // A rejected write must not leave an apparently enabled switch.
                    switchView.isChecked = !enabled
                    Log.w(TAG, "CONTROL_CENTER_SETTING_SAVE_FAILED key=$key")
                }
                statusView.text = "${row.description} · " +
                    ControlStatusText.status(
                        if (saved) ControlEffective.RESTART_REQUIRED else ControlEffective.ERROR,
                    )
            },
        )
        if (!accepted) {
            toggle.isEnabled = true
            toggle.isChecked = !enabled
            status.text = "${row.description} · " + ControlStatusText.status(ControlEffective.ERROR)
        }
    }
    private fun restartWhatsApp() {
        if (!isWindowInteractive()) return
        val weakShell = WeakReference(this)
        val accepted = taskScope.submit(
            operation = { true },
            onComplete = {
                val shell = weakShell.get() ?: return@submit
                if (shell.isWindowInteractive()) shell.restartWhatsAppNow()
            },
        )
        if (!accepted) Log.w(TAG, "CONTROL_CENTER_RESTART_SKIPPED reason=SESSION_CLOSED")
    }

    /** Queued after accepted writes so an immediate restart cannot discard a save in flight. */
    private fun restartWhatsAppNow() {
        if (!isWindowInteractive()) return
        try {
            val launch = activity.packageManager.getLaunchIntentForPackage(packageName)
            val component = launch?.component ?: return
            val restart = Intent.makeRestartActivityTask(component)
            restart.setPackage(packageName)
            activity.startActivity(restart)
            Runtime.getRuntime().exit(0)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG,
                "CONTROL_CENTER_RESTART_FAILED exception=${failure.javaClass.simpleName}")
        }
    }

    private fun themeColor(attr: Int, fallback: Int): Int = try {
        val value = TypedValue()
        if (activity.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) {
                activity.resources.getColor(value.resourceId, activity.theme)
            } else {
                value.data
            }
        } else {
            fallback
        }
    } catch (failure: Throwable) {
        fallback
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun readBoolean(states: Bundle, key: String): Boolean =
        states.getBoolean("pref." + key, false)

    private fun readString(states: Bundle, key: String): String? =
        states.getString("state." + key, null)

    /** The archive mode arrives as a prefixed preference value. */
    private fun readModeFromState(states: Bundle): String =
        states.getString("mode." + ModernHideChatFeature.PREF_ARCHIVE_MODE,
            ModernHideChatFeature.MODE_DISABLED) ?: ModernHideChatFeature.MODE_DISABLED

    companion object {
        private const val TAG = "WA-X ControlCenter"
        private val sessions = ControlCenterSessionRegistry<ModernControlCenterShell>()

        private fun newTaskScope(
            executor: java.util.concurrent.ExecutorService,
            handler: Handler,
        ): ControlCenterTaskScope = ControlCenterTaskScope(
            executor = executor,
            postToMain = { callback -> handler.post(callback) },
            removeMainCallbacks = { handler.removeCallbacksAndMessages(null) },
            onUiPostFailure = {
                Log.w(TAG, "CONTROL_CENTER_UI_UPDATE_DROPPED reason=MAIN_HANDLER_UNAVAILABLE")
            },
        )

        @JvmStatic
        fun showFor(activity: Activity, packageName: String): Boolean {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Log.w(TAG, "WINDOW_FAILED package=$packageName reason=MAIN_THREAD_REQUIRED")
                return false
            }
            if (activity.packageName != packageName || activity.isFinishing || activity.isDestroyed) {
                Log.w(TAG, "WINDOW_FAILED package=$packageName reason=ACTIVITY_UNAVAILABLE")
                return false
            }
            return try {
                val session = sessions.acquire(
                    key = packageName,
                    hostActivity = activity,
                    canReuse = { it.isReusableFor(activity) },
                    create = { ModernControlCenterShell(activity, packageName) },
                    retire = { it.closeForReplacement() },
                )
                session.show()
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                val reason = ControlCenterFailureReason.fromClassName(failure.javaClass.name)
                Log.w(TAG,
                    "WINDOW_FAILED package=$packageName reason=$reason exception=${failure.javaClass.simpleName}")
                false
            }
        }
    }
}

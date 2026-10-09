package com.wax.module.modern

import android.app.Activity
import android.app.Dialog
import android.content.Intent
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

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
 * Failure policy: any construction or inflation problem is caught and turned
 * into a fallback to the external Manager. This UI must never be able to
 * crash WhatsApp.
 */
class ModernControlCenterShell(
    private val activity: Activity,
    private val packageName: String,
) {
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "wax-api102-control-center-write").apply { isDaemon = true }
    }

    private val primary = themeColor(android.R.attr.textColorPrimary, Color.WHITE)
    private val secondary = themeColor(android.R.attr.textColorSecondary, Color.LTGRAY)

    private var dialog: Dialog? = null

    /** Shows the centre. Returns false when the caller must fall back. */
    fun show(): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        return try {
            buildAndShow()
            true
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Embedded control center unavailable; falling back", failure)
            fallbackToManager()
            false
        }
    }

    private fun buildAndShow() {
        val states = ModernTargetStateClient.read(activity, packageName)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val header = TextView(activity).apply {
            text = activity.getString(android.R.string.dialog_alert_title)
            setTextColor(primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        root.addView(header)

        val search = EditText(activity).apply {
            hint = "Search WA X features"
            setTextColor(primary)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        root.addView(search)

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
            text = "Restart WhatsApp"
            setOnClickListener { restartWhatsApp() }
        }
        val manager = Button(activity).apply {
            text = "WA X Manager"
            setOnClickListener { fallbackToManager() }
        }
        footer.addView(restart)
        footer.addView(manager)
        root.addView(footer)

        fun render(query: String) {
            content.removeAllViews()
            val entries = ControlPolicy.group(buildEntries(states, query), query)
            if (entries.isEmpty()) {
                content.addView(TextView(activity).apply {
                    text = "No WA X feature matches your search"
                    setTextColor(secondary)
                    setPadding(0, dp(12), 0, dp(12))
                })
                return
            }
            for ((category, rows) in entries) {
                content.addView(TextView(activity).apply {
                    text = ControlStatusText.categoryTitle(category)
                    setTextColor(secondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, dp(14), 0, dp(4))
                })
                for (row in rows) {
                    content.addView(rowView(row))
                }
            }
        }

        render("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                try {
                    render(s?.toString().orEmpty())
                } catch (failure: Throwable) {
                    Log.w(TAG, "Control center render failed", failure)
                }
            }
        })

        dialog = Dialog(activity).apply {
            setContentView(root)
            setTitle("WA X")
            setOnDismissListener { dialog = null }
            show()
        }
    }

    private fun buildEntries(states: Bundle, query: String): List<ControlEntry> {
        val rows = ArrayList<ControlEntry>()
        for (item in ModernControlCenterCatalog.wired) {
            val requested = when {
                item.preferenceKey.isEmpty() -> ControlRequested.UNKNOWN
                readBoolean(states, item.preferenceKey) -> ControlRequested.ENABLED
                else -> ControlRequested.DISABLED
            }
            val reported = readString(states, item.evidenceKey)
            val effective = ControlPolicy.effectiveFrom(reported, false, requested)
            rows.add(
                ControlEntry(
                    id = item.id,
                    title = item.label,
                    description = item.description,
                    category = item.category,
                    preferenceKey = item.preferenceKey.ifEmpty { null },
                    requested = requested,
                    effective = effective,
                    writable = item.preferenceKey.isNotEmpty() &&
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
        if (row.writable && row.preferenceKey != null) {
            val toggle = Switch(activity).apply {
                text = row.title
                isChecked = row.requested == ControlRequested.ENABLED
                contentDescription = "${row.title}. ${ControlStatusText.status(row.effective)}"
                setOnCheckedChangeListener { button, isChecked ->
                    if (!button.isPressed) return@setOnCheckedChangeListener
                    val key = row.preferenceKey
                    persist(key, isChecked, status, row)
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
        return container
    }

    private fun persist(
        key: String,
        enabled: Boolean,
        status: TextView,
        row: ControlEntry,
    ) {
        writer.execute {
            val saved = ModernTargetSettingsClient.write(activity, packageName, key, enabled)
            Handler(Looper.getMainLooper()).post {
                if (saved) {
                    status.text = "${row.description} · " +
                        ControlStatusText.status(ControlEffective.RESTART_REQUIRED)
                } else {
                    status.text = "${row.description} · " +
                        ControlStatusText.status(ControlEffective.ERROR)
                }
            }
        }
    }

    private fun fallbackToManager() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setClassName(MANAGER_PACKAGE, MANAGER_ACTIVITY)
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            activity.startActivity(intent)
        } catch (failure: Throwable) {
            Log.w(TAG, "WA X Manager fallback unavailable", failure)
        }
    }

    private fun restartWhatsApp() {
        try {
            val launch = activity.packageManager.getLaunchIntentForPackage(packageName)
            val component = launch?.component ?: return
            val restart = Intent.makeRestartActivityTask(component)
            restart.setPackage(packageName)
            activity.startActivity(restart)
            Runtime.getRuntime().exit(0)
        } catch (failure: Throwable) {
            Log.w(TAG, "Restart unavailable", failure)
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

    private companion object {
        const val TAG = "WA-X ControlCenter"
        const val MANAGER_PACKAGE = "com.wax.module"
        const val MANAGER_ACTIVITY = "com.wax.module.activities.MainActivity"
    }
}
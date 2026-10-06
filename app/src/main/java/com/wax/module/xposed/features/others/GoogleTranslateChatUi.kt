package com.wax.module.xposed.features.others

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.wax.module.R
import com.wax.module.xposed.core.ModuleRuntime
import com.wax.module.xposed.core.components.AlertDialogWpp
import com.wax.module.xposed.core.components.FMessageWpp
import com.wax.module.xposed.core.devkit.Unobfuscator
import com.wax.module.xposed.features.listeners.ConversationItemListener
import com.wax.module.xposed.features.providers.ContextMenuActionProvider
import com.wax.module.xposed.utils.DesignUtils
import com.wax.module.xposed.utils.ModuleContextWrapper
import com.wax.module.xposed.utils.ReflectionUtils
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture

/** All view/cache state is confined to the main thread. Never modifies stored messages. */
internal class GoogleTranslateChatUi(
    private val loader: ClassLoader,
    private val translate: (String?, String, String) -> CompletableFuture<String?>,
) {
    private val main = Handler(Looper.getMainLooper())

    private data class RequestKey(
        val chat: String,
        val id: String,
        val text: String,
        val source: String,
        val target: String,
    )

    private data class Rendered(
        val original: CharSequence,
        val rendered: String,
    )

    private val renderedViews = WeakHashMap<TextView, Rendered>()
    private val bound = WeakHashMap<ViewGroup, RequestKey>()
    private val cache =
        object : LinkedHashMap<RequestKey, String>(32, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RequestKey, String>?) = size > 200
        }
    private val pending = HashMap<RequestKey, CompletableFuture<String?>>()
    private val failures =
        object : LinkedHashMap<RequestKey, Long>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RequestKey, Long>?) = size > 200
        }
    private val reported = HashSet<String>()
    private val settings get() = ModuleRuntime.getPrivPrefs()

    private fun key(chat: String?) = "google_translation_v3_" + (chat ?: "default")

    private fun legacyKey(chat: String?) = "google_translation_v2_" + (chat ?: "default")

    private fun config(chat: String?): GoogleTranslationConfig =
        GoogleTranslateSettings.resolve(
            chat?.let { settings.getString(key(it), null) },
            settings.getString(key(null), null),
            chat?.let { settings.getString(legacyKey(it), null) },
            settings.getString(legacyKey(null), null),
        )

    private fun hasOverride(chat: String) = settings.contains(key(chat)) || settings.contains(legacyKey(chat))

    private fun rawJid(jid: Any?): String? =
        runCatching {
            GoogleTranslateSettings.normalizeChatId(
                XposedHelpers.callMethod(
                    jid,
                    "getRawString",
                ) as? String,
            )
        }.getOrNull()

    private fun chatId(jid: Any?): String? {
        val raw = rawJid(jid) ?: return null
        // GroupJid must never be passed through a person/LID conversion.
        if (raw.endsWith("@g.us")) return raw
        return rawJid(ModuleRuntime.getPhoneJidFromUserJid(jid)) ?: raw
    }

    private fun messageChat(message: FMessageWpp): String? {
        // Read the conversation JID, never the participant/sender JID.
        val jid =
            runCatching { XposedHelpers.getObjectField(message.key.thisObject, "A00") }.getOrNull()
        return chatId(jid) ?: chatId(message.key.remoteJid.phoneJid)
            ?: chatId(message.key.remoteJid.userJid)
    }

    private fun reportOnce(reason: String) {
        if (reported.add(reason)) XposedBridge.log("Google Translate: $reason")
    }

    fun install() {
        MenuHome.addMenuItem { menu, activity ->
            if (menu.findItem(R.string.google_translate) == null) {
                menu
                    .add(0, R.string.google_translate, 0, ModuleContextWrapper(activity).getString(R.string.gt_name))
                    .setOnMenuItemClickListener {
                        showSettings(activity, null)
                        true
                    }
            }
        }
        installInfoCard(".ContactInfoActivity", "jid.UserJid")
        installInfoCard(".GroupChatInfoActivity", "jid.GroupJid")
        ConversationItemListener.conversationListeners.add(
            object :
                ConversationItemListener.OnConversationItemListener() {
                override fun onItemBind(
                    fMessage: FMessageWpp,
                    view: ViewGroup,
                    position: Int,
                    convertView: View?,
                ) {
                    bind(fMessage, view)
                }
            },
        )
        installManualAction()
    }

    private fun installInfoCard(
        activitySuffix: String,
        jidSuffix: String,
    ) {
        try {
            val activityClass =
                Unobfuscator.findFirstClassUsingName(
                    loader,
                    StringMatchType.EndsWith,
                    activitySuffix,
                )
            val jidClass =
                Unobfuscator.findFirstClassUsingName(loader, StringMatchType.EndsWith, jidSuffix)
            val jidMethod =
                ReflectionUtils.findMethodUsingFilter(activityClass) {
                    it.parameterCount == 0 && jidClass.isAssignableFrom(it.returnType)
                }
            ModuleRuntime.addListenerActivity { activity, type ->
                if (activityClass.isInstance(activity) && type == ModuleRuntime.ActivityChangeState.ChangeType.STARTED) {
                    // Info layouts can be attached after STARTED; retry briefly without retaining the activity.
                    val weakActivity = WeakReference(activity)

                    fun attach(attempt: Int) {
                        val current = weakActivity.get() ?: return
                        if (current.isFinishing || current.isDestroyed) return
                        val decor = current.window.decorView as? ViewGroup ?: return
                        if (decor.findViewWithTag<View>("wa_google_translate_info") != null) return
                        val host =
                            current.findViewById<ViewGroup>(
                                Utils.getID(
                                    "contact_info_security_card_layout",
                                    "id",
                                ),
                            )
                        val chat =
                            runCatching {
                                chatId(
                                    ReflectionUtils.callMethod(
                                        jidMethod,
                                        current,
                                    ),
                                )
                            }.getOrNull()
                        if (host == null || chat == null) {
                            if (attempt < 5) {
                                main.postDelayed({ attach(attempt + 1) }, 200)
                            } else {
                                reportOnce("$activitySuffix translation card unavailable: ${if (host == null) "layout" else "chat ID"}")
                            }
                            return
                        }
                        val item = infoRow(current, chat)
                        item.tag = "wa_google_translate_info"
                        item.setOnClickListener {
                            showSettings(current, chat) {
                                updateInfoSummary(
                                    item,
                                    chat,
                                )
                            }
                        }
                        host.addView(item)
                    }
                    main.post { attach(0) }
                }
            }
        } catch (e: Exception) {
            reportOnce("$activitySuffix settings hook unavailable (${e.javaClass.simpleName})")
        }
    }

    private fun label(code: String) =
        if (code == "auto") {
            ModuleContextWrapper(Utils.application).getString(R.string.gt_detect_language)
        } else {
            GoogleTranslateLanguages.entries.firstOrNull { it.first == code }?.second ?: code
        }

    private fun summary(chat: String?): String {
        val value = config(chat)
        val state =
            if (value.enabled) {
                "${label(
                    value.source,
                )} → ${label(value.target)}"
            } else {
                ModuleContextWrapper(Utils.application).getString(R.string.gt_automatic_off)
            }
        return if (chat != null &&
            !hasOverride(chat)
        ) {
            ModuleContextWrapper(Utils.application).getString(R.string.gt_global_default_format, state)
        } else {
            state
        }
    }

    private fun updateInfoSummary(
        row: View,
        chat: String,
    ) {
        row.findViewWithTag<TextView>("translation_summary")?.text = summary(chat)
    }

    private fun infoRow(
        activity: Activity,
        chat: String,
    ): View {
        val moduleContext = ModuleContextWrapper(activity)
        val row =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2)
                setPadding(
                    Utils.dipToPixels(16),
                    Utils.dipToPixels(16),
                    Utils.dipToPixels(16),
                    Utils.dipToPixels(16),
                )
                val value = android.util.TypedValue()
                if (activity.theme.resolveAttribute(
                        android.R.attr.selectableItemBackground,
                        value,
                        true,
                    )
                ) {
                    setBackgroundResource(value.resourceId)
                }
            }
        row.addView(
            ImageView(activity).apply {
                setImageDrawable(
                    DesignUtils
                        .getDrawable(R.drawable.ic_translate)
                        .mutate()
                        .apply { setTint(0xff8696a0.toInt()) },
                )
                layoutParams =
                    LinearLayout.LayoutParams(Utils.dipToPixels(24), Utils.dipToPixels(24)).apply {
                        marginStart = Utils.dipToPixels(20)
                        marginEnd = Utils.dipToPixels(32)
                    }
            },
        )
        row.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(
                    TextView(activity).apply {
                        text = moduleContext.getString(R.string.gt_name)
                        textSize =
                            17f
                        setTextColor(DesignUtils.getPrimaryTextColor())
                    },
                )
                addView(
                    TextView(activity).apply {
                        tag = "translation_summary"
                        text = summary(chat)
                        textSize = 14f
                        setTextColor(0xff8696a0.toInt())
                    },
                )
            },
        )
        return row
    }

    private fun showSettings(
        activity: Activity,
        chat: String?,
        onSaved: () -> Unit = {},
    ) {
        val ctx = ModuleContextWrapper(activity)
        var draft = config(chat)
        val layout =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    Utils.dipToPixels(20),
                    Utils.dipToPixels(8),
                    Utils.dipToPixels(20),
                    Utils.dipToPixels(8),
                )
            }
        val inherit =
            MaterialCheckBox(ctx).apply {
                text = ctx.getString(R.string.gt_use_global_settings)
                isChecked = chat != null && !hasOverride(chat)
                visibility = if (chat == null) View.GONE else View.VISIBLE
            }
        val enabled =
            MaterialCheckBox(ctx).apply {
                text = ctx.getString(R.string.gt_auto_translate)
                isChecked = draft.enabled
            }

        fun languageButton() =
            MaterialButton(
                ctx,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isAllCaps = false
            }

        val from = languageButton()
        val to = languageButton()

        fun update() {
            val editable = !inherit.isChecked
            val displayed = if (inherit.isChecked) config(null) else draft
            enabled.isEnabled = editable
            enabled.isChecked = displayed.enabled
            from.isEnabled = editable
            to.isEnabled = editable
            from.text = ctx.getString(R.string.gt_from_format, label(displayed.source))
            to.text = ctx.getString(R.string.gt_to_format, label(displayed.target))
        }
        enabled.setOnCheckedChangeListener { _, checked ->
            if (!inherit.isChecked) draft = draft.copy(enabled = checked)
        }
        inherit.setOnCheckedChangeListener { _, _ -> update() }
        from.setOnClickListener {
            showLanguages(activity, draft.source, true) {
                draft = draft.copy(source = it)
                update()
            }
        }
        to.setOnClickListener {
            showLanguages(activity, draft.target, false) {
                draft = draft.copy(target = it)
                update()
            }
        }
        layout.addView(inherit)
        layout.addView(enabled)
        layout.addView(from)
        layout.addView(to)
        layout.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.gt_privacy_notice)
                textSize = 13f
                setTextColor(DesignUtils.getPrimaryTextColor())
                alpha = .7f
                setPadding(0, Utils.dipToPixels(12), 0, Utils.dipToPixels(8))
            },
        )
        update()
        AlertDialogWpp(activity)
            .setTitle(if (chat == null) ctx.getString(R.string.gt_global_title) else ctx.getString(R.string.gt_name))
            .setView(ScrollView(ctx).apply { addView(layout) })
            .setNegativeButton(ctx.getString(R.string.cancel), null)
            .setPositiveButton(ctx.getString(R.string.save)) { _, _ ->
                settings
                    .edit()
                    .apply {
                        if (chat != null && inherit.isChecked) {
                            remove(key(chat))
                        } else {
                            putString(
                                key(chat),
                                draft.encode(),
                            )
                        }
                        remove(legacyKey(chat))
                    }.apply()
                failures.clear()
                // Invalidate requests from an older selection, including changes made away from the conversation.
                bound.clear()
                renderedViews.entries.toList().forEach { (view, old) ->
                    if (view.text.toString() == old.rendered) view.text = old.original
                }
                renderedViews.clear()
                ConversationItemListener.notifyDataSetChanged()
                onSaved()
            }.show()
    }

    private fun showLanguages(
        activity: Activity,
        current: String,
        source: Boolean,
        selected: (String) -> Unit,
    ) {
        val ctx = ModuleContextWrapper(activity)
        val layout =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Utils.dipToPixels(16), 0, Utils.dipToPixels(16), 0)
            }
        val searchLayout = TextInputLayout(ctx).apply { hint = ctx.getString(R.string.gt_search_languages) }
        val search = TextInputEditText(searchLayout.context).apply { isSingleLine = true }
        searchLayout.addView(search)
        val list = ListView(activity).apply { choiceMode = ListView.CHOICE_MODE_SINGLE }
        val all =
            (if (source) listOf("auto" to ctx.getString(R.string.gt_detect_language)) else emptyList()) +
                GoogleTranslateLanguages.entries.sortedBy { it.second.lowercase(Locale.ROOT) }
        var visible = all
        var choice = current
        val adapter =
            object : ArrayAdapter<String>(
                activity,
                android.R.layout.simple_list_item_single_choice,
                visible.map { it.second }.toMutableList(),
            ) {
                override fun getView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup,
                ): View {
                    val view = super.getView(position, convertView, parent)
                    if (view is CheckedTextView) {
                        view.setTextColor(DesignUtils.getPrimaryTextColor())
                    }
                    return view
                }
            }
        list.adapter = adapter

        fun markSelection() {
            list.clearChoices()
            val index = visible.indexOfFirst { it.first == choice }
            if (index >= 0) list.setItemChecked(index, true)
        }
        markSelection()
        list.setOnItemClickListener { _, _, position, _ -> choice = visible[position].first }
        layout.addView(searchLayout)
        layout.addView(
            list,
            LinearLayout.LayoutParams(
                -1,
                (activity.resources.displayMetrics.heightPixels * .45).toInt(),
            ),
        )
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    val query = s?.toString().orEmpty()
                    visible =
                        all.filter { it.first.contains(query, true) || it.second.contains(query, true) }
                    adapter.clear()
                    adapter.addAll(visible.map { it.second })
                    markSelection()
                }

                override fun afterTextChanged(s: Editable?) = Unit
            },
        )
        AlertDialogWpp(activity)
            .setTitle(if (source) ctx.getString(R.string.gt_translate_from) else ctx.getString(R.string.gt_translate_to))
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(ctx.getString(android.R.string.ok)) { _, _ -> selected(choice) }
            .show()
    }

    private fun messageTextView(
        root: ViewGroup,
        original: String,
    ): TextView? {
        root.findViewById<TextView>(Utils.getID("message_text", "id"))?.let { return it }

        // Group rows can use a different text view. Match content, excluding quote previews.
        fun candidates(view: View): List<TextView> {
            val resource =
                runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
            if (resource.contains("quoted", true) ||
                resource.contains(
                    "quoted_message",
                    true,
                )
            ) {
                return emptyList()
            }
            if (view is TextView &&
                view !is EditText &&
                view.isVisible &&
                (view.text.toString() == original || renderedViews[view]?.original?.toString() == original)
            ) {
                return listOf(view)
            }
            return if (view is ViewGroup) {
                view.children
                    .flatMap { candidates(it) }
                    .toList()
            } else {
                emptyList()
            }
        }
        return candidates(root).singleOrNull()
    }

    private fun bind(
        message: FMessageWpp,
        root: ViewGroup,
    ) {
        bound.remove(root)
        val text = message.messageStr?.takeIf { it.isNotBlank() && it.length <= 4000 } ?: return
        val textView =
            messageTextView(root, text) ?: run {
                reportOnce("Message text view unavailable (${root.javaClass.simpleName})")
                return
            }
        // Restore only our own output; WhatsApp may already have rebound this recycled view.
        renderedViews
            .remove(textView)
            ?.let { if (textView.text.toString() == it.rendered) textView.text = it.original }
        if (message.key.isFromMe) return
        val chat =
            messageChat(message) ?: run {
                reportOnce("Message chat ID unavailable")
                return
            }
        val selection = config(chat)
        if (!selection.enabled || selection.source == selection.target) return
        val request =
            RequestKey(chat, message.key.messageID, text, selection.source, selection.target)
        bound[root] = request
        cache[request]?.let {
            render(textView, it)
            return
        }
        if ((failures[request] ?: 0L) > SystemClock.elapsedRealtime()) return
        val future = request(request)
        val weakRoot = WeakReference(root)
        future.whenComplete { result, error ->
            main.post {
                val view = weakRoot.get() ?: return@post
                if (error != null || result == null) return@post
                if (bound[view] != request) return@post
                if (config(chat) != selection) return@post
                if (!ConversationItemListener.isViewBoundToMessage(view, request.id)) return@post
                messageTextView(view, text)?.let { render(it, result) }
            }
        }
    }

    private fun render(
        view: TextView,
        translation: String,
    ) {
        val original = renderedViews[view]?.original ?: android.text.SpannedString(view.text)
        if (original.toString().trim() == translation.trim()) return
        val output =
            SpannableStringBuilder(
                original,
            ).append("\n\n").append(ModuleContextWrapper(Utils.application).getString(R.string.gt_name)).append("\n").append(translation)
        renderedViews[view] = Rendered(original, output.toString())
        view.text = output
    }

    private fun request(request: RequestKey): CompletableFuture<String?> {
        cache[request]?.let { return CompletableFuture.completedFuture(it) }
        pending[request]?.let { return it }
        if (pending.size >= 32) {
            return CompletableFuture<String?>().apply {
                completeExceptionally(IllegalStateException("Translation queue full"))
            }
        }
        val future = translate(request.text, request.source, request.target)
        pending[request] = future
        future.whenComplete { result, error ->
            main.post {
                pending.remove(request)
                if (error == null && result != null) {
                    cache[request] = result
                } else {
                    failures[request] = SystemClock.elapsedRealtime() + 60_000
                }
            }
        }
        return future
    }

    private fun installManualAction() {
        ContextMenuActionProvider.register { activity, _, wpp ->
            val messageText =
                wpp.messageStr.takeUnless { it.isNullOrBlank() } ?: return@register null
            ContextMenuActionProvider.ContextMenuAction(
                title = ModuleContextWrapper(activity).getString(R.string.gt_translate_action),
            ) {
                val chat =
                    messageChat(wpp)
                        ?: run {
                            reportOnce("Message chat ID unavailable")
                            return@ContextMenuAction
                        }
                val selection = config(chat)
                manual(
                    activity,
                    chat,
                    wpp.key.messageID,
                    messageText,
                    selection.source,
                    selection.target,
                )
            }
        }
    }

    private fun manual(
        activity: Activity,
        chat: String,
        id: String,
        text: String,
        source: String,
        target: String,
    ) {
        val ctx = ModuleContextWrapper(activity)
        if (text.length > 4000) {
            AlertDialogWpp(activity)
                .setTitle(ctx.getString(R.string.gt_name))
                .setMessage(ctx.getString(R.string.gt_message_too_long))
                .setPositiveButton(ctx.getString(R.string.gt_close), null)
                .show()
            return
        }
        val output =
            TextView(activity).apply {
                this.text = ctx.getString(R.string.gt_translating)
                setPadding(
                    32,
                    24,
                    32,
                    24,
                )
                setTextIsSelectable(true)
            }
        val scroll = ScrollView(activity).apply { addView(output) }
        val dialog =
            AlertDialogWpp(activity)
                .setTitle(ctx.getString(R.string.gt_translator))
                .setView(scroll)
                .setPositiveButton("Close", null)
                .create()
        dialog.show()
        request(RequestKey(chat, id, text, source, target)).whenComplete { result, error ->
            main.post {
                if (!activity.isDestroyed && dialog.isShowing) {
                    output.text =
                        if (error == null &&
                            result != null
                        ) {
                            result
                        } else {
                            ctx.getString(R.string.gt_unavailable)
                        }
                }
            }
        }
    }
}

package com.wax.module.preference

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.core.content.edit
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wax.module.R
import com.wax.module.utils.WhatsAppContactPickerLauncher
import com.wax.module.xposed.utils.Utils
import java.util.ArrayList

class ContactPickerPreference :
    Preference,
    Preference.OnPreferenceClickListener {
    /** Resource id of the summary shown while nothing is selected, or 0 for none. */
    private var summaryOffRes: Int = 0

    /**
     * Resource id of the summary shown once something is selected, or 0 for none.
     *
     * May be a `@plurals` resource: the count of selected contacts is exactly the case a
     * plural exists for, and a single formatted string has to lie about one contact.
     */
    private var summaryOnRes: Int = 0

    private var contacts: ArrayList<String>? = null

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
        super(context, attrs, defStyleAttr, defStyleRes) {
        init(context, attrs)
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        val preferenceKey = key ?: return true
        val selectedContacts = contacts?.let(::ArrayList) ?: arrayListOf()
        val installedPackages = WhatsAppContactPickerLauncher.getInstalledWhatsAppPackages(context)
        when (installedPackages.size) {
            1 -> startSelectContacts(installedPackages[0], preferenceKey, selectedContacts)
            in 2..Int.MAX_VALUE -> showPackageSelectionDialog(installedPackages, preferenceKey, selectedContacts)
        }
        return true
    }

    private fun showPackageSelectionDialog(
        installedPackages: ArrayList<String>,
        preferenceKey: String,
        selectedContacts: ArrayList<String>,
    ) {
        val items =
            Array<CharSequence?>(installedPackages.size) { index ->
                WhatsAppContactPickerLauncher.getPackageLabel(installedPackages[index])
            }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.select_whatsapp_app)
            .setItems(items) { _, which ->
                startSelectContacts(installedPackages[which], preferenceKey, ArrayList(selectedContacts))
            }.show()
    }

    private fun startSelectContacts(
        packageName: String,
        preferenceKey: String,
        selectedContacts: ArrayList<String>,
    ) {
        try {
            val intent =
                WhatsAppContactPickerLauncher.createPickerIntent(
                    context,
                    packageName,
                    preferenceKey,
                    selectedContacts,
                )
            (context as Activity).startActivityForResult(intent, REQUEST_CONTACT_PICKER)
        } catch (exception: Exception) {
            Utils.showToast(exception.message, 1)
        }
    }

    private fun init(
        context: Context,
        attrs: AttributeSet?,
    ) {
        onPreferenceClickListener = this
        val typedArray =
            context.theme.obtainStyledAttributes(
                attrs,
                R.styleable.ContactPickerPreference,
                0,
                0,
            )
        summaryOffRes = typedArray.getResourceId(R.styleable.ContactPickerPreference_contactPickerSummaryOff, 0)
        summaryOnRes = typedArray.getResourceId(R.styleable.ContactPickerPreference_contactPickerSummaryOn, 0)
        typedArray.recycle()

        val preferenceKey = key
        val namesString =
            PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString(preferenceKey, "")
                .orEmpty()
        if (namesString.length > 2) {
            contacts = ArrayList(namesString.substring(1, namesString.length - 1).split(", ").map(String::trim))
        }
        updateSummary()
    }

    fun handleActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent,
    ) {
        if (requestCode == REQUEST_CONTACT_PICKER && resultCode == Activity.RESULT_OK) {
            contacts = data.getStringArrayListExtra("contacts")
            getSharedPreferences()!!.edit { putString(key, contacts.toString()) }
            updateSummary()
        }
    }

    private fun updateSummary() {
        val selected = contacts
        val count = selected?.size ?: 0
        summary =
            if (count > 0) {
                resolveSummary(summaryOnRes, count)
            } else {
                resolveSummary(summaryOffRes, 0)
            }
    }

    /**
     * Resolves one summary attribute for the current selection size.
     *
     * A `@plurals` resource is resolved through `Resources.getQuantityString` so the
     * per-language quantity form is chosen for the real count; anything else is a plain
     * string. An absent attribute yields null so the preference shows whatever summary it
     * already had instead of the literal text "null".
     */
    private fun resolveSummary(
        resourceId: Int,
        count: Int,
    ): CharSequence? {
        if (resourceId == 0) return null
        val resources = context.resources
        return if (resources.getResourceTypeName(resourceId) == "plurals") {
            resources.getQuantityString(resourceId, count, count)
        } else {
            resources.getText(resourceId)
        }
    }

    companion object {
        const val REQUEST_CONTACT_PICKER = 0xff2515
    }
}

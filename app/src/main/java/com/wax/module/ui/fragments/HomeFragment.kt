package com.wax.module.ui.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wax.module.BuildConfig
import com.wax.module.ModuleApplication
import com.wax.module.R
import com.wax.module.adapter.LogLineAdapter
import com.wax.module.compat.TargetVersions
import com.wax.module.compat.UpdateOffer
import com.wax.module.config.BackupEntry
import com.wax.module.config.ConfigBackupSchema
import com.wax.module.config.ConfigValue
import com.wax.module.databinding.DialogDiagnosticsLogBinding
import com.wax.module.databinding.FragmentHomeBinding
import com.wax.module.ui.fragments.base.BaseFragment
import com.wax.module.utils.FilePicker
import com.wax.module.utils.RootDiagnostics
import com.wax.module.xposed.core.FeatureLoader
import com.wax.module.xposed.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import rikka.core.util.IOUtils
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HomeFragment : BaseFragment() {
    private var currentBinding: FragmentHomeBinding? = null
    private val binding get() = currentBinding!!
    private var statusReceiverRegistered = false

    private val statusReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                try {
                    when (intent.getStringExtra("PKG")) {
                        FeatureLoader.PACKAGE_WPP -> receiverBroadcastWpp(context, intent)
                        FeatureLoader.PACKAGE_BUSINESS -> receiverBroadcastBusiness(context, intent)
                    }
                } catch (_: Exception) {
                }
            }
        }

    override fun onStart() {
        super.onStart()
        if (!statusReceiverRegistered) {
            val intentFilter = IntentFilter("${BuildConfig.APPLICATION_ID}.RECEIVER_WPP")
            ContextCompat.registerReceiver(
                requireContext(),
                statusReceiver,
                intentFilter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            statusReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (statusReceiverRegistered) {
            runCatching { requireContext().unregisterReceiver(statusReceiver) }
            statusReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        currentBinding = FragmentHomeBinding.inflate(inflater, container, false)

        checkStateWpp(requireActivity())

        binding.rebootBtn.setOnClickListener { view ->
            animateClick(view)
            ModuleApplication.instance.restartApp(FeatureLoader.PACKAGE_WPP)
            disableWpp()
        }

        binding.scrollDiagBtn.setOnClickListener { view ->
            animateClick(view)
            binding.nestedScrollView.post {
                currentBinding?.let { b ->
                    b.nestedScrollView.smoothScrollTo(0, b.diagCard.top)
                }
            }
        }

        binding.rebootBtn2.setOnClickListener { view ->
            animateClick(view)
            ModuleApplication.instance.restartApp(FeatureLoader.PACKAGE_BUSINESS)
            disableBusiness()
        }

        binding.exportBtn.setOnClickListener { view ->
            animateClick(view)
            saveConfigs(requireContext())
        }

        binding.importBtn.setOnClickListener { view ->
            animateClick(view)
            importConfigs(requireContext())
        }

        binding.resetBtn.setOnClickListener { view ->
            animateClick(view)
            resetConfigs(requireContext())
        }

        binding.updateCard.setOnClickListener { view ->
            animateClick(view)
            Utils.openLink(requireActivity(), "https://t.me/Alaa91h")
        }

        binding.diagBtn.setOnClickListener { view ->
            animateClick(view)
            showDiagnosticsDialog()
        }

        checkForUpdates()
        startCardAnimations()

        return binding.root
    }

    private fun startCardAnimations() {
        val context = context ?: return
        val slideUp = AnimationUtils.loadAnimation(context, R.anim.slide_up)
        val fadeIn = AnimationUtils.loadAnimation(context, R.anim.fade_in)

        binding.status.startAnimation(slideUp)

        binding.status2.postDelayed({
            if (!isAdded || currentBinding == null) return@postDelayed
            val anim = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_up)
            binding.status2.startAnimation(anim)
        }, 100)

        binding.status3.postDelayed({
            if (!isAdded || currentBinding == null) return@postDelayed
            val anim = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_up)
            binding.status3.startAnimation(anim)
        }, 200)

        binding.infoCard.postDelayed({
            if (!isAdded || currentBinding == null) return@postDelayed
            binding.infoCard.startAnimation(fadeIn)
        }, 300)

        binding.updateCard.postDelayed({
            if (!isAdded || currentBinding == null) return@postDelayed
            val anim = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_up)
            binding.updateCard.startAnimation(anim)
        }, 400)
    }

    private fun animateClick(view: View) {
        val scaleIn = AnimationUtils.loadAnimation(context, R.anim.scale_in)
        view.startAnimation(scaleIn)
    }

    override fun onResume() {
        super.onResume()
        setDisplayHomeAsUpEnabled(false)
        updatePackageStatuses(requireContext())
    }

    private fun receiverBroadcastBusiness(
        context: Context,
        intent: Intent,
    ) {
        if (ModuleApplication.isOriginalPackage) binding.status3.visibility = View.VISIBLE
        binding.statusTitle3.setText(R.string.business_in_background)
        val version = intent.getStringExtra("VERSION")
        val supportedList = context.resources.getStringArray(R.array.supported_versions_business).toList()
        if (isSupportedVersion(version, supportedList)) {
            binding.statusSummary3.text = getString(R.string.version_s, version)
            binding.status3.getChildAt(0).setBackgroundResource(R.drawable.gradient_success)
        } else {
            binding.statusSummary3.text = getString(R.string.version_s_not_listed, version)
            binding.status3.getChildAt(0).setBackgroundResource(R.drawable.gradient_warning)
        }
        binding.rebootBtn2.visibility = View.VISIBLE
        binding.statusSummary3.visibility = View.VISIBLE
        binding.statusIcon3.setImageResource(R.drawable.ic_round_check_circle_24)
    }

    private fun receiverBroadcastWpp(
        context: Context,
        intent: Intent,
    ) {
        binding.statusTitle2.setText(R.string.whatsapp_in_background)
        val version = intent.getStringExtra("VERSION")
        val supportedList = context.resources.getStringArray(R.array.supported_versions_wpp).toList()

        if (isSupportedVersion(version, supportedList)) {
            binding.statusSummary1.text = getString(R.string.version_s, version)
            binding.status2.getChildAt(0).setBackgroundResource(R.drawable.gradient_success)
        } else {
            binding.statusSummary1.text = getString(R.string.version_s_not_listed, version)
            binding.status2.getChildAt(0).setBackgroundResource(R.drawable.gradient_warning)
        }
        binding.rebootBtn.visibility = View.VISIBLE
        binding.statusSummary1.visibility = View.VISIBLE
        binding.statusIcon2.setImageResource(R.drawable.ic_round_check_circle_24)
    }

    private fun resetConfigs(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit {
            prefs.all.keys.forEach { key -> remove(key) }
        }
        ModuleApplication.instance.restartApp(FeatureLoader.PACKAGE_WPP)
        ModuleApplication.instance.restartApp(FeatureLoader.PACKAGE_BUSINESS)
        Utils.showToast(context.getString(R.string.configs_reset), Toast.LENGTH_SHORT)
    }

    @Throws(JSONException::class)
    private fun getJsonObject(prefs: SharedPreferences): JSONObject {
        val entries = prefs.all
        val jsonObject = JSONObject()
        for ((key, value) in entries) {
            // The type vocabulary is owned by ConfigBackupSchema so that the name written
            // here is exactly the name its parser accepts.
            val typeName = ConfigBackupSchema.typeNameOf(value) ?: continue
            var keyValue: Any? = value
            if (keyValue is Set<*>) {
                keyValue = JSONArray(ArrayList(keyValue))
            }
            val type = JSONObject()
            type.put(ConfigBackupSchema.FIELD_TYPE, typeName)
            type.put(ConfigBackupSchema.FIELD_VALUE, keyValue)
            jsonObject.put(key, type)
        }
        return jsonObject
    }

    /**
     * Reads a backup document into flat entries without touching preferences.
     *
     * @return the entries, or null when any single entry cannot be represented, which is
     *   the caller's signal to abort before anything is removed
     */
    private fun readBackupEntries(jsonObject: JSONObject): List<BackupEntry>? {
        val entries = ArrayList<BackupEntry>()
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val keyName = keys.next()
            var value = jsonObject.get(keyName)
            var typeName: String? = value.javaClass.simpleName
            var rawValue: Any? = value
            if (value is JSONObject) {
                typeName = value.optString(ConfigBackupSchema.FIELD_TYPE)
                rawValue = value.opt(ConfigBackupSchema.FIELD_VALUE)
                if (rawValue is JSONArray) {
                    rawValue = (0 until rawValue.length()).map { rawValue.getString(it) }
                }
            }
            entries.add(BackupEntry(keyName, typeName, rawValue))
        }
        return entries
    }

    private fun saveConfigs(context: Context) {
        FilePicker.setOnUriPickedListener { uri ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        val jsonObject = getJsonObject(prefs)
                        output.write(jsonObject.toString(4).toByteArray())
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.configs_saved), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val formattedDate = dateFormat.format(Date())
        FilePicker.fileSalve.launch("wpp_enhacer_configs_$formattedDate.json")
    }

    private fun importConfigs(context: Context) {
        FilePicker.setOnUriPickedListener { uri ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val data = IOUtils.toString(input)
                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        val jsonObject = JSONObject(data)

                        // Decode the whole document before touching preferences. The old
                        // order removed every existing key first and only then applied the
                        // file, so a partial or unrecognised document silently wiped the
                        // user's settings. Restoring is now all or nothing.
                        val entries =
                            readBackupEntries(jsonObject)
                                ?: throw JSONException("Unsupported configuration entry type")
                        val decoded =
                            ConfigBackupSchema.decodeAll(entries)
                                ?: throw JSONException("Unsupported configuration entry type")

                        prefs.edit {
                            prefs.all.keys.forEach { key -> remove(key) }
                            decoded.forEach { (key, value) ->
                                when (value) {
                                    is ConfigValue.Text -> putString(key, value.value)
                                    is ConfigValue.Flag -> putBoolean(key, value.value)
                                    is ConfigValue.Whole -> putInt(key, value.value)
                                    is ConfigValue.Wide -> putLong(key, value.value)
                                    is ConfigValue.Decimal -> putFloat(key, value.value)
                                    is ConfigValue.Texts -> putStringSet(key, value.value)
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.configs_imported), Toast.LENGTH_SHORT).show()
                        ModuleApplication.instance.restartApp(FeatureLoader.PACKAGE_WPP)
                        ModuleApplication.instance.restartApp(FeatureLoader.PACKAGE_BUSINESS)
                    }
                } catch (e: Exception) {
                    Log.e("importConfigs", e.message ?: "", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        FilePicker.fileCapture.launch(arrayOf("application/json"))
    }

    private fun checkStateWpp(activity: FragmentActivity) {
        if (ModuleApplication.instance.isXposedEnabled()) {
            binding.statusIcon.setImageResource(R.drawable.ic_round_check_circle_24)
            binding.statusTitle.setText(R.string.module_enabled)
            binding.statusSummary.text = String.format(getString(R.string.version_s), BuildConfig.VERSION_NAME)
            binding.status.getChildAt(0).setBackgroundResource(R.drawable.gradient_success)
        } else {
            binding.statusIcon.setImageResource(R.drawable.ic_round_error_outline_24)
            binding.statusTitle.setText(R.string.module_disabled)
            binding.status.getChildAt(0).setBackgroundResource(R.drawable.gradient_error)
            binding.statusSummary.visibility = View.GONE
        }
        if (isInstalled(FeatureLoader.PACKAGE_WPP) && ModuleApplication.isOriginalPackage) {
            disableWpp()
        } else {
            binding.status2.visibility = View.GONE
        }
        if (ModuleApplication.isOriginalPackage) {
            binding.status3.visibility = View.GONE
        }
        checkWpp(activity)
        binding.deviceName.text = Build.MANUFACTURER
        binding.sdk.text = Build.VERSION.SDK_INT.toString()
        binding.modelName.text = Build.DEVICE
        if (ModuleApplication.isOriginalPackage) {
            binding.listWpp.text = activity.resources.getStringArray(R.array.supported_versions_wpp).contentToString()
        } else {
            binding.listWppTitle.visibility = View.GONE
            binding.listWpp.visibility = View.GONE
        }
        binding.listBusiness.text = activity.resources.getStringArray(R.array.supported_versions_business).contentToString()
        updatePackageStatuses(activity)
    }

    private fun updatePackageStatuses(context: Context) {
        updatePackageStatus(
            context,
            binding.whatsappPackageSummary,
            binding.whatsappPackageIcon,
            FeatureLoader.PACKAGE_WPP,
            context.resources.getStringArray(R.array.supported_versions_wpp).toList(),
        )
        updatePackageStatus(
            context,
            binding.businessPackageSummary,
            binding.businessPackageIcon,
            FeatureLoader.PACKAGE_BUSINESS,
            context.resources.getStringArray(R.array.supported_versions_business).toList(),
        )
    }

    private fun updatePackageStatus(
        context: Context,
        summary: android.widget.TextView,
        icon: android.widget.ImageView,
        packageName: String,
        supportedVersions: List<String>,
    ) {
        val packageInfo =
            try {
                context.packageManager.getPackageInfo(packageName, 0)
            } catch (_: Exception) {
                null
            }

        if (packageInfo == null) {
            summary.setText(R.string.app_not_installed)
            icon.setImageResource(R.drawable.ic_round_error_outline_24)
            return
        }

        val version = packageInfo.versionName
        if (version.isNullOrBlank()) {
            summary.setText(R.string.app_installed_version_unknown)
            icon.setImageResource(R.drawable.ic_round_warning_24)
            return
        }

        val supported = isSupportedVersion(version, supportedVersions)
        summary.text =
            getString(
                if (supported) R.string.app_version_s_supported else R.string.app_version_s_unsupported,
                version,
            )
        icon.setImageResource(
            if (supported) R.drawable.ic_round_check_circle_24 else R.drawable.ic_round_warning_24,
        )
    }

    private fun isInstalled(packageWpp: String): Boolean =
        try {
            ModuleApplication.instance.packageManager.getPackageInfo(packageWpp, 0)
            true
        } catch (_: Exception) {
            false
        }

    private fun isSupportedVersion(
        version: String?,
        supportedVersions: List<String>,
    ): Boolean = TargetVersions.isSupported(version, supportedVersions)

    private fun disableBusiness() {
        binding.statusIcon3.setImageResource(R.drawable.ic_round_error_outline_24)
        binding.statusTitle3.setText(R.string.business_is_not_running_or_has_not_been_activated_in_lsposed)
        binding.status3.getChildAt(0).setBackgroundResource(R.drawable.gradient_error)
        binding.statusSummary3.visibility = View.GONE
        binding.rebootBtn2.visibility = View.GONE
    }

    private fun disableWpp() {
        binding.statusIcon2.setImageResource(R.drawable.ic_round_error_outline_24)
        binding.statusTitle2.setText(R.string.whatsapp_is_not_running_or_has_not_been_activated_in_lsposed)
        binding.status2.getChildAt(0).setBackgroundResource(R.drawable.gradient_error)
        binding.statusSummary1.visibility = View.GONE
        binding.rebootBtn.visibility = View.GONE
    }

    private fun checkWpp(activity: FragmentActivity) {
        listOf(FeatureLoader.PACKAGE_WPP, FeatureLoader.PACKAGE_BUSINESS).forEach { packageName ->
            val checkWpp =
                Intent("${BuildConfig.APPLICATION_ID}.CHECK_WPP").apply {
                    setPackage(packageName)
                }
            activity.sendBroadcast(checkWpp)
        }
    }

    private fun checkForUpdates() {
        if (context == null) return
        binding.updateSummary.text = getString(R.string.current_version_s, BuildConfig.VERSION_NAME)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client =
                    OkHttpClient
                        .Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()

                val request =
                    Request
                        .Builder()
                        .url("https://api.github.com/repos/Alaa91H/WA X/releases/latest")
                        .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        updateCardState(success = false, isUpToDate = false, newVersion = null)
                        return@use
                    }

                    val body = response.body
                    val content = body.string()
                    val release = JSONObject(content)
                    val tagName = release.optString("tag_name", "")

                    if (tagName.isBlank()) {
                        updateCardState(success = true, isUpToDate = true, newVersion = null)
                        return@use
                    }

                    val releaseVersion = UpdateOffer.normaliseTag(tagName)
                    val currentVersion = UpdateOffer.normaliseModuleVersion(BuildConfig.VERSION_NAME)
                    val isNewVersion = releaseVersion.isNotEmpty() && releaseVersion != currentVersion

                    updateCardState(success = true, isUpToDate = !isNewVersion, newVersion = tagName)
                }
            } catch (_: UnknownHostException) {
                updateCardState(success = false, isUpToDate = false, newVersion = null)
            } catch (_: Exception) {
                updateCardState(success = false, isUpToDate = false, newVersion = null)
            }
        }
    }

    private suspend fun updateCardState(
        success: Boolean,
        isUpToDate: Boolean,
        newVersion: String?,
    ) {
        withContext(Dispatchers.Main) {
            if (currentBinding == null || !isAdded) return@withContext

            if (!success) {
                binding.updateIcon.setImageResource(R.drawable.ic_round_error_outline_24)
                binding.updateTitle.setText(R.string.update_check_failed)
                binding.updateSummary.setText(R.string.update_check_failed_summary)
                binding.updateCard.getChildAt(0).setBackgroundResource(R.drawable.gradient_warning)
            } else if (isUpToDate) {
                binding.updateIcon.setImageResource(R.drawable.ic_round_check_circle_24)
                binding.updateTitle.setText(R.string.up_to_date)
                binding.updateSummary.text = getString(R.string.current_version_s, BuildConfig.VERSION_NAME)
                binding.updateCard.getChildAt(0).setBackgroundResource(R.drawable.gradient_success)
            } else {
                binding.updateIcon.setImageResource(R.drawable.ic_round_update_24)
                binding.updateTitle.setText(R.string.update_available)
                binding.updateSummary.text = getString(R.string.update_available_summary, newVersion)
                binding.updateCard.getChildAt(0).setBackgroundResource(R.drawable.gradient_update)
            }
        }
    }

    private fun showDiagnosticsDialog() {
        val context = requireContext()
        val dialogBinding = DialogDiagnosticsLogBinding.inflate(LayoutInflater.from(context))
        val logAdapter = LogLineAdapter()

        dialogBinding.logRecycler.layoutManager = LinearLayoutManager(context)
        dialogBinding.logRecycler.adapter = logAdapter

        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.diag_dialog_title)
                .setView(dialogBinding.root)
                .setPositiveButton(R.string.diag_close, null)
                .setCancelable(true)
                .show()

        val handler = Handler(Looper.getMainLooper())
        val queue = ArrayList<RootDiagnostics.LogEntry>()

        RootDiagnostics.runDiagnostics(context) { entry ->
            if (!isAdded) return@runDiagnostics
            queue.add(entry)
        }

        val poller =
            object : Runnable {
                private var emptyCycles = 0

                override fun run() {
                    if (!isAdded || currentBinding == null || !dialog.isShowing) return

                    if (queue.isNotEmpty()) {
                        emptyCycles = 0
                        logAdapter.add(queue.removeAt(0))
                        dialogBinding.logRecycler.smoothScrollToPosition(logAdapter.itemCount - 1)
                        handler.postDelayed(this, 120)
                    } else if (emptyCycles < 50) {
                        emptyCycles++
                        handler.postDelayed(this, 120)
                    }
                }
            }
        handler.postDelayed(poller, 120)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        currentBinding = null
    }
}

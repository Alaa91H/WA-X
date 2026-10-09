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
import com.wax.module.activation.ActivationAction
import com.wax.module.activation.ActivationMonitor
import com.wax.module.activation.ActivationState
import com.wax.module.activation.ActivationStatus
import com.wax.module.activation.ActivationStatusResolver
import com.wax.module.activation.TargetHeartbeatCodec
import com.wax.module.activation.TargetProcessObserver
import com.wax.module.adapter.LogLineAdapter
import com.wax.module.compat.TargetVersions
import com.wax.module.compat.UpdateOffer
import com.wax.module.compat.UpdateReleaseClient
import com.wax.module.compat.VersionStatusTone
import com.wax.module.config.BackupEntry
import com.wax.module.config.ConfigBackupSchema
import com.wax.module.config.ConfigValue
import com.wax.module.databinding.DialogDiagnosticsLogBinding
import com.wax.module.databinding.FragmentHomeBinding
import com.wax.module.modern.ModernManagerRuntimeStatus
import com.wax.module.modern.ModernRuntimePreferenceRelay
import com.wax.module.ui.fragments.base.BaseFragment
import com.wax.module.utils.FilePicker
import com.wax.module.utils.RootDiagnostics
import com.wax.module.xposed.core.FeatureLoader
import com.wax.module.xposed.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import rikka.core.util.IOUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : BaseFragment() {
    private var currentBinding: FragmentHomeBinding? = null
    private val binding get() = currentBinding!!
    private var statusReceiverRegistered = false
    private val activationProbeHandler = Handler(Looper.getMainLooper())
    private val pendingActivationProbes = mutableListOf<Runnable>()

    // WhatsApp's initial DexKit / feature startup can outlast the Manager opening.
    // Bound retries to the visible Home screen; do not poll in the background.
    private val activationProbeRetryDelaysMillis = longArrayOf(4_000L, 12_000L, 24_000L)

    /**
     * The activation model this screen reads.
     *
     * Created lazily from the fragment's context because the store is a file in the Manager's
     * own storage, and the screen is the only thing in this process that receives heartbeats.
     * Everything the status cards show is resolved through it, so no card can compute its own
     * verdict - which is the defect the single boolean used to be.
     */
    private val activation: ActivationMonitor by lazy { ActivationMonitor.forContext(requireContext()) }

    private val statusReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                try {
                    // Filed before anything is rendered, so the reply the screen shows and the
                    // record it leaves behind cannot disagree. A payload this version cannot
                    // read is dropped rather than guessed at.
                    val reportedPackage = intent.getStringExtra("PKG")
                    val heartbeat = TargetHeartbeatCodec.decode(intent.getStringExtra(FeatureLoader.EXTRA_HEARTBEAT))
                    if (heartbeat == null) {
                        Log.w("WA-X Activation", "Probe reply from $reportedPackage has no valid runtime heartbeat")
                    } else {
                        activation.accept(heartbeat)
                        Log.i("WA-X Activation", "Probe reply from $reportedPackage: stage=${heartbeat.stage}, state=${heartbeat.state}")
                    }
                    // Both target and aggregate cards must reflect the received state. The old
                    // receiver only repainted the target and left the module card red forever.
                    renderActivation()
                } catch (e: Exception) {
                    Log.w("WA-X Activation", "Could not process runtime probe reply", e)
                }
            }
        }

    override fun onStart() {
        super.onStart()
        if (BuildConfig.MODERN_XPOSED) {
            renderModernActivation()
            scheduleModernStatusRetries()
            return
        }
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
        // The receiver must exist BEFORE the first request. Sending in onCreateView lost
        // fast replies on cold starts because the Fragment had not reached onStart yet.
        checkWpp(requireActivity())
        scheduleActivationProbeRetries()
    }

    private fun scheduleModernStatusRetries() {
        activationProbeRetryDelaysMillis.forEach { delayMillis ->
            val retry =
                Runnable {
                    if (isAdded && currentBinding != null) renderModernActivation()
                }
            pendingActivationProbes.add(retry)
            activationProbeHandler.postDelayed(retry, delayMillis)
        }
    }

    private fun scheduleActivationProbeRetries() {
        activationProbeRetryDelaysMillis.forEach { delayMillis ->
            val retry =
                Runnable {
                    if (statusReceiverRegistered && currentBinding != null && isAdded) {
                        checkWpp(requireActivity())
                    }
                }
            pendingActivationProbes.add(retry)
            activationProbeHandler.postDelayed(retry, delayMillis)
        }
    }

    override fun onStop() {
        pendingActivationProbes.forEach(activationProbeHandler::removeCallbacks)
        pendingActivationProbes.clear()
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
            // Re-rendered rather than forced into an error state: after a restart the runtime
            // has not reported yet, and painting "not running" during that window would be a
            // claim the module cannot make.
            renderTarget(FeatureLoader.PACKAGE_WPP)
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
            renderTarget(FeatureLoader.PACKAGE_BUSINESS)
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
            Utils.openLink(requireActivity(), UpdateReleaseClient.LATEST_RELEASE_PAGE)
        }

        binding.diagBtn.setOnClickListener { view ->
            animateClick(view)
            showDiagnosticsDialog()
        }

        if (BuildConfig.MODERN_XPOSED) {
            binding.status.setOnClickListener { showModernCustomTimeDialog() }
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
        renderActivation()
    }

    /**
     * Resolves and renders every card from the activation model.
     *
     * Called on entry, on resume and whenever a probe reply arrives. The previous
     * implementation branched on one boolean here and left the target cards at whatever the
     * last broadcast said; both facts came from different processes and neither was re-checked
     * against the other.
     */
    private fun renderActivation() {
        if (BuildConfig.MODERN_XPOSED) {
            renderModernActivation()
            return
        }
        val context = context ?: return
        val legacy = ModuleApplication.instance.isLegacySelfHookSignal()

        renderTarget(FeatureLoader.PACKAGE_WPP)
        if (ModuleApplication.isOriginalPackage) {
            renderTarget(FeatureLoader.PACKAGE_BUSINESS)
        } else {
            binding.status3.visibility = View.GONE
        }

        renderModule(
            listOf(FeatureLoader.PACKAGE_WPP, FeatureLoader.PACKAGE_BUSINESS)
                .map { statusOf(context, it, legacy) },
            legacy,
        )
    }

    /**
     * API 102 uses framework remote preferences, not the old Xposed self-hook broadcast.
     * Read Binder-backed values off the UI thread and never show READY just for a Service bind.
     */
    private fun renderModernActivation() {
        if (!isAdded || currentBinding == null) return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val snapshot = ModernManagerRuntimeStatus.inspect()
            withContext(Dispatchers.Main) {
                if (!isAdded || currentBinding == null) return@withContext
                binding.statusTitle.text =
                    if (snapshot.connected) {
                        getString(R.string.modern_framework_connected, (snapshot.frameworkApi ?: 102).toString())
                    } else {
                        getString(R.string.modern_framework_waiting)
                    }
                binding.statusSummary.text =
                    buildString {
                        append(getString(R.string.modern_framework_status, BuildConfig.VERSION_NAME))
                        append('\n')
                        append(snapshot.frameworkName ?: "Vector/LSPosed")
                        snapshot.connectionProblem?.let { append(": ").append(it) }
                        append('\n')
                        append(getString(R.string.modern_framework_features_pending))
                        append('\n')
                        append(getString(R.string.modern_pilot_tap))
                    }
                binding.statusIcon.setImageResource(
                    if (snapshot.connected) R.drawable.ic_round_check_circle_24 else R.drawable.ic_round_warning_24,
                )
                binding.status.getChildAt(0).setBackgroundResource(
                    if (snapshot.connected) R.drawable.gradient_success else R.drawable.gradient_warning,
                )

                snapshot.targets.forEach { target ->
                    val business = target.packageName == FeatureLoader.PACKAGE_BUSINESS
                    val title = if (business) binding.statusTitle3 else binding.statusTitle2
                    val summary = if (business) binding.statusSummary3 else binding.statusSummary1
                    val icon = if (business) binding.statusIcon3 else binding.statusIcon2
                    val card = if (business) binding.status3 else binding.status2
                    val restart = if (business) binding.rebootBtn2 else binding.rebootBtn
                    val label =
                        if (business) {
                            getString(R.string.whatsapp_business_package)
                        } else {
                            getString(R.string.whatsapp_app_label)
                        }
                    val installed = isInstalled(target.packageName)
                    title.text = getString(R.string.modern_target_title, label)
                    summary.text =
                        if (!installed) {
                            getString(R.string.app_not_installed)
                        } else {
                            val evidence =
                                when (target.evidence) {
                                    ModernManagerRuntimeStatus.Evidence.FRESH_BOOTSTRAP -> {
                                        getString(R.string.modern_target_loaded)
                                    }

                                    ModernManagerRuntimeStatus.Evidence.STALE_BOOTSTRAP -> {
                                        getString(R.string.modern_target_stale)
                                    }

                                    ModernManagerRuntimeStatus.Evidence.BOOT_MISMATCH,
                                    ModernManagerRuntimeStatus.Evidence.CLOCK_MISMATCH,
                                    -> {
                                        getString(R.string.modern_target_boot_mismatch)
                                    }

                                    ModernManagerRuntimeStatus.Evidence.NOT_REPORTED -> {
                                        getString(R.string.modern_target_no_report)
                                    }
                                }
                            evidence + "\n" +
                                (if (target.bootstrapMilestones.isNotEmpty()) {
                                    target.bootstrapMilestones.joinToString(" → ") + "\n"
                                } else {
                                    "NO_TARGET_LIFECYCLE_SIGNAL\n"
                                }) +
                                getString(
                                    R.string.modern_target_feature_status,
                                    target.customTimeInstallation ?: "NOT_REPORTED",
                                ) + "\n" +
                                getString(
                                    R.string.modern_target_share_limit_status,
                                    target.shareLimitInstallation ?: "NOT_REPORTED",
                                )
                        }
                    val reported = target.evidence == ModernManagerRuntimeStatus.Evidence.FRESH_BOOTSTRAP
                    icon.setImageResource(
                        if (reported) R.drawable.ic_round_check_circle_24 else R.drawable.ic_round_warning_24,
                    )
                    card.getChildAt(0).setBackgroundResource(
                        if (reported) R.drawable.gradient_success else R.drawable.gradient_warning,
                    )
                    restart.visibility = if (reported) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun showModernCustomTimeDialog() {
        if (!BuildConfig.MODERN_XPOSED || !isAdded) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val active = prefs.getBoolean(ModernRuntimePreferenceRelay.ENABLE_KEY, false)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.modern_pilot_title)
            .setMessage(R.string.modern_pilot_message)
            .setPositiveButton(
                if (active) R.string.modern_pilot_disable else R.string.modern_pilot_enable,
            ) { _, _ ->
                prefs.edit {
                    putBoolean(ModernRuntimePreferenceRelay.ENABLE_KEY, !active)
                }
                Toast.makeText(requireContext(), R.string.modern_pilot_restart, Toast.LENGTH_LONG).show()
                renderModernActivation()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun statusOf(
        context: Context,
        packageName: String,
        legacy: Boolean,
    ): ActivationStatus =
        activation.status(
            packageName = packageName,
            installed = isInstalled(packageName),
            process = TargetProcessObserver.observe(context, packageName),
            legacySelfHookSignal = legacy,
        )

    private fun renderTarget(packageName: String) {
        val context = context ?: return
        val legacy = ModuleApplication.instance.isLegacySelfHookSignal()
        val status = statusOf(context, packageName, legacy)
        val business = packageName == FeatureLoader.PACKAGE_BUSINESS
        val label = if (business) getString(R.string.whatsapp_business_package) else getString(R.string.whatsapp_app_label)

        val title = binding.statusTitle3.takeIf { business } ?: binding.statusTitle2
        val summary = binding.statusSummary3.takeIf { business } ?: binding.statusSummary1
        val icon = binding.statusIcon3.takeIf { business } ?: binding.statusIcon2
        val card = binding.status3.takeIf { business } ?: binding.status2
        val restart = binding.rebootBtn2.takeIf { business } ?: binding.rebootBtn

        if (business && !ModuleApplication.isOriginalPackage) {
            binding.status3.visibility = View.GONE
            return
        }

        title.text = label.statusTitle(status.state)
        icon.setImageResource(status.icon())
        card.getChildAt(0).setBackgroundResource(status.background())
        summary.text = label.statusSummary(context, status)
        summary.visibility = View.VISIBLE
        // The restart button only means something once the runtime is in the target, because
        // restarting is the action that makes the runtime load into it.
        restart.visibility = if (status.state.isInjected) View.VISIBLE else View.GONE
    }

    private fun renderModule(
        targetStatuses: List<ActivationStatus>,
        legacy: Boolean,
    ) {
        val status = ActivationStatusResolver.resolveModuleStatus(legacy, targetStatuses)
        binding.statusIcon.setImageResource(status.icon())
        binding.statusTitle.text =
            getString(
                when (status.state) {
                    ActivationState.READY -> R.string.module_state_ready
                    ActivationState.DEGRADED -> R.string.module_state_degraded
                    ActivationState.FAILED -> R.string.module_state_failed
                    ActivationState.RUNNING_NOT_INJECTED -> R.string.module_state_framework_only
                    else -> R.string.module_state_unknown
                },
                targetLabel(targetStatuses),
            )
        binding.status.getChildAt(0).setBackgroundResource(status.background())
        binding.statusSummary.text =
            buildString {
                append(getString(R.string.module_state_summary, BuildConfig.VERSION_NAME, status.signal.name))
                append('\n')
                append(
                    getString(
                        if (legacy) R.string.module_state_signal_legacy else R.string.module_state_signal_absent,
                    ),
                )
                status.failureCode?.let {
                    append('\n')
                    append(getString(R.string.activation_action_open_diagnostics, it.name))
                }
            }
        binding.statusSummary.visibility = View.VISIBLE
    }

    /**
     * The targets that actually reported, named.
     *
     * The module card says "Running in WhatsApp" rather than "Module enabled" because that is
     * the whole claim: a module is running *somewhere*, and naming the somewhere is what makes
     * the sentence checkable.
     */
    private fun targetLabel(statuses: List<ActivationStatus>): String {
        val injected =
            statuses
                .filter { it.state.isInjected }
                .map { status ->
                    if (status.packageName == FeatureLoader.PACKAGE_BUSINESS) {
                        getString(R.string.whatsapp_business_package)
                    } else {
                        getString(R.string.whatsapp_app_label)
                    }
                }
        return when (injected.size) {
            0 -> getString(R.string.module_state_unknown)
            1 -> injected.first()
            else -> injected.joinToString(", ")
        }
    }

    private fun String.statusTitle(state: ActivationState): String =
        when (state) {
            ActivationState.NOT_INSTALLED -> getString(R.string.activation_state_not_installed, this)
            ActivationState.NOT_RUNNING -> getString(R.string.activation_state_not_running, this)
            ActivationState.RUNNING_NOT_INJECTED -> getString(R.string.activation_state_running_not_injected, this)
            ActivationState.BOOTSTRAPPING -> getString(R.string.activation_state_bootstrapping, this)
            ActivationState.DEGRADED -> getString(R.string.activation_state_degraded, this)
            ActivationState.FAILED -> getString(R.string.activation_state_failed, this)
            ActivationState.READY -> getString(R.string.activation_state_ready, this)
            ActivationState.UNKNOWN -> getString(R.string.activation_state_unknown, this)
        }

    /**
     * The line under the title: the action, then the evidence behind the claim.
     *
     * The action comes first because it is what the reader needs, and the evidence follows
     * because it is what makes the action checkable. The version tone the old summary showed is
     * preserved, so nothing the card used to tell the user is lost.
     */
    private fun String.statusSummary(
        context: Context,
        status: ActivationStatus,
    ): String {
        val lines =
            mutableListOf(
                when (status.action) {
                    ActivationAction.NONE -> {
                        status.evidence().orEmpty()
                    }

                    ActivationAction.INSTALL_TARGET -> {
                        getString(R.string.activation_action_install_target, this)
                    }

                    ActivationAction.OPEN_TARGET -> {
                        getString(R.string.activation_action_open_target, this)
                    }

                    ActivationAction.ENABLE_IN_FRAMEWORK -> {
                        getString(R.string.activation_action_enable_in_framework, this)
                    }

                    ActivationAction.WAIT -> {
                        getString(R.string.activation_action_wait)
                    }

                    ActivationAction.OPEN_DIAGNOSTICS -> {
                        getString(R.string.activation_action_open_diagnostics, status.failureCode?.name ?: "UNKNOWN")
                    }
                },
            )
        // The version tone the card always showed, kept. It is the one fact here that comes
        // from the target rather than from the runtime, and dropping it would lose something
        // the user could act on.
        versionTone(context, status.packageName)?.let { lines += it }
        status.evidence()?.let { lines += it }
        return lines.filter { it.isNotBlank() }.joinToString("\n")
    }

    /**
     * The supported/unsupported verdict for the target version, or null when unknown.
     *
     * Read from the heartbeat when there is one, and from the package manager otherwise, so a
     * target that has never started still gets its version line.
     */
    private fun versionTone(
        context: Context,
        packageName: String,
    ): String? {
        val heartbeat = activation.heartbeatFor(packageName)
        val version =
            heartbeat?.targetVersionName
                ?: runCatching { context.packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        if (version.isNullOrBlank()) return null
        val supported =
            context.resources
                .getStringArray(
                    if (packageName == FeatureLoader.PACKAGE_BUSINESS) {
                        R.array.supported_versions_business
                    } else {
                        R.array.supported_versions_wpp
                    },
                ).toList()
        return when (versionTone(version, supported)) {
            VersionStatusTone.SUPPORTED -> getString(R.string.app_version_s_supported, version)
            VersionStatusTone.UNVERIFIED -> getString(R.string.version_s_unverified, version)
            VersionStatusTone.UNSUPPORTED -> getString(R.string.app_version_s_unsupported, version)
        }
    }

    /** The technical detail behind a claim: stage, failure and session, when there is any. */
    private fun ActivationStatus.evidence(): String? {
        val heartbeat = activation.heartbeatFor(packageName) ?: return null
        val parts =
            mutableListOf<String>()
        parts += getString(R.string.activation_stage, heartbeat.stage)
        heartbeat.failureCode?.let { parts += it.name }
        parts +=
            getString(
                R.string.activation_session,
                heartbeat.targetSessionId,
                heartbeat.pid,
                heartbeat.freshnessAt(System.currentTimeMillis()).name,
            )
        return parts.joinToString(" · ")
    }

    private fun ActivationStatus.icon(): Int =
        when (state) {
            ActivationState.READY -> R.drawable.ic_round_check_circle_24
            ActivationState.DEGRADED, ActivationState.BOOTSTRAPPING, ActivationState.UNKNOWN -> R.drawable.ic_round_warning_24
            else -> R.drawable.ic_round_error_outline_24
        }

    private fun ActivationStatus.background(): Int =
        when (state) {
            ActivationState.READY -> R.drawable.gradient_success
            ActivationState.DEGRADED, ActivationState.BOOTSTRAPPING, ActivationState.UNKNOWN -> R.drawable.gradient_warning
            else -> R.drawable.gradient_error
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
        if (isInstalled(FeatureLoader.PACKAGE_WPP) && ModuleApplication.isOriginalPackage) {
            binding.status2.visibility = View.VISIBLE
        } else {
            binding.status2.visibility = View.GONE
        }
        // The probe is asked for *after* the cards have rendered what is already known, so the
        // screen shows the last known truth immediately and improves when the answer arrives
        // rather than sitting on "checking" until a broadcast comes back.
        renderActivation()
        binding.deviceName.text = Build.MANUFACTURER
        binding.sdk.text = String.format(Locale.getDefault(), "%d", Build.VERSION.SDK_INT)
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

        val tone = versionTone(version, supportedVersions)
        summary.text =
            getString(
                when (tone) {
                    VersionStatusTone.SUPPORTED -> R.string.app_version_s_supported
                    VersionStatusTone.UNVERIFIED -> R.string.version_s_unverified
                    VersionStatusTone.UNSUPPORTED -> R.string.app_version_s_unsupported
                },
                version,
            )
        icon.setImageResource(
            if (tone == VersionStatusTone.SUPPORTED) {
                R.drawable.ic_round_check_circle_24
            } else {
                R.drawable.ic_round_warning_24
            },
        )
    }

    private fun isInstalled(packageWpp: String): Boolean =
        try {
            ModuleApplication.instance.packageManager.getPackageInfo(packageWpp, 0)
            true
        } catch (_: Exception) {
            false
        }

    private fun versionTone(
        version: String?,
        supportedVersions: List<String>,
    ): VersionStatusTone = TargetVersions.assess(version, supportedVersions).tone

    /**
     * Asks each target to report.
     *
     * A target that answers proves the module's code is executing inside it, because only code
     * running there can answer. A target that does not answer proves nothing by itself, which
     * is why the probe is a request for evidence rather than a check - and why silence renders
     * as "nothing reported" rather than as a failure.
     */
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
                val release = UpdateReleaseClient.fetchLatestRelease()
                val comparison =
                    UpdateOffer.compareVersions(
                        releaseVersion = release.version,
                        currentVersion = BuildConfig.VERSION_NAME,
                    )

                if (comparison == null) {
                    updateCardState(success = false, isUpToDate = false, newVersion = null)
                    return@launch
                }

                updateCardState(
                    success = true,
                    isUpToDate = comparison <= 0,
                    newVersion = release.tagName,
                )
            } catch (e: Exception) {
                Log.w("WA-X Update", "Unable to check GitHub release metadata", e)
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

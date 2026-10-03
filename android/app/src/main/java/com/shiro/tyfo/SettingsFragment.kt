package com.shiro.tyfo

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.shiro.tyfo.config.ConfigRepository
import com.shiro.tyfo.config.ProfileRepository
import com.shiro.tyfo.config.ProviderProfile
import com.shiro.tyfo.ui.MemoryActivity

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var config: ConfigRepository
    private lateinit var profileRepo: ProfileRepository

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "tyfo_config"
        preferenceManager.sharedPreferencesMode = android.content.Context.MODE_PRIVATE

        migrateIntKeys()
        setPreferencesFromResource(R.xml.settings_root, rootKey)

        config = ConfigRepository(requireContext())
        profileRepo = ProfileRepository(config, requireContext())
        profileRepo.ensureMigrated()

        setupProfilePreference()
        setupTextSummaries()
        setupMaxStepsPreference()
        setupAutoSaveOnChange()
        setupMemoryPreference()
        setupBlacklistPreference()
        setupBackendPreferences()
        setupAssistantPreference()
        setupVersionInfo()
    }

    /** Migrate Int-stored SharedPreferences values to String so EditTextPreference doesn't crash. */
    private fun migrateIntKeys() {
        val prefs = preferenceManager.sharedPreferences ?: return
        val editor = prefs.edit()
        for (key in listOf("max_steps")) {
            try {
                val v = prefs.getInt(key, Int.MIN_VALUE)
                if (v != Int.MIN_VALUE) {
                    editor.remove(key).putString(key, v.toString())
                }
            } catch (_: ClassCastException) {
                // Already a String
            }
        }
        editor.apply()
    }

    // --- Provider Profiles ---

    private fun setupProfilePreference() {
        // Outer PreferenceScreen on main page — just update summary
        findPreference<Preference>("subscreen_profile")?.let { refreshProfileSummary(it) }

        // Inner Preference inside sub-page — clicking shows profile dialog
        val pref = findPreference<Preference>("provider_profile") ?: return
        refreshProfileSummary(pref)
        pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            showProfileDialog()
            true
        }
    }

    private fun refreshProfileSummary(pref: Preference? = null) {
        val active = config.activeProfileName
        val summary = if (active.isNotEmpty()) {
            getString(R.string.pref_profile_summary_active, active)
        } else {
            getString(R.string.pref_profile_summary_none)
        }
        // Update whichever preference is visible in the current fragment
        (pref ?: findPreference<Preference>("provider_profile")
            ?: findPreference<Preference>("subscreen_profile"))?.let {
            it.summary = summary
        }
    }

    private fun showProfileDialog() {
        val profiles = profileRepo.listProfiles()
        val activeProfileName = config.activeProfileName

        val names = profiles.map { it.name }.toMutableList()
        val subtitles = profiles.map { "${it.model} · ${android.net.Uri.parse(it.baseUrl).host ?: it.baseUrl}" }.toMutableList()
        names.add(getString(R.string.profile_dialog_new))
        subtitles.add("")

        val items = names.mapIndexed { i, name ->
            if (i < profiles.size) {
                val check = if (name == activeProfileName) "● " else "○ "
                "$check$name\n   ${subtitles[i]}"
            } else {
                name
            }
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_dialog_title)
            .setItems(items) { _, which ->
                if (which < profiles.size) {
                    val selected = profiles[which]
                    if (selected.name == activeProfileName) {
                        showProfileOptionsDialog(selected.name)
                    } else {
                        profileRepo.switchTo(selected.name)
                        refreshAllProviderFields()
                        refreshProfileSummary()
                        Toast.makeText(requireContext(),
                            getString(R.string.profile_switched, selected.name),
                            Toast.LENGTH_SHORT).show()
                    }
                } else {
                    showSaveProfileDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSaveProfileDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.profile_name_hint)
            setText(profileRepo.generateProfileName(config.apiProvider, config.model))
            selectAll()
            setPadding(60, 40, 60, 20)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_save_as_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.profile_name_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (profileRepo.getProfile(name) != null) {
                    Toast.makeText(requireContext(),
                        getString(R.string.profile_name_exists, name),
                        Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                profileRepo.saveCurrentAsProfile(name)
                refreshProfileSummary()
                Toast.makeText(requireContext(),
                    getString(R.string.profile_saved, name),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileOptionsDialog(profileName: String) {
        val options = arrayOf("Delete")
        AlertDialog.Builder(requireContext())
            .setTitle(profileName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        AlertDialog.Builder(requireContext())
                            .setMessage(getString(R.string.profile_delete_confirm, profileName))
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                profileRepo.deleteProfile(profileName)
                                refreshProfileSummary()
                                refreshAllProviderFields()
                                Toast.makeText(requireContext(),
                                    getString(R.string.profile_deleted, profileName),
                                    Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun refreshAllProviderFields() {
        findPreference<EditTextPreference>("model")?.text = config.model
        findPreference<EditTextPreference>("base_url")?.text = config.baseUrl
        findPreference<EditTextPreference>("api_key")?.text = config.apiKey
    }

    private fun setupAutoSaveOnChange() {
        for (key in listOf("api_key", "model", "base_url")) {
            val pref = findPreference<EditTextPreference>(key) ?: continue
            val existing = pref.onPreferenceChangeListener
            pref.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { p, newValue ->
                    val result = existing?.onPreferenceChange(p, newValue) ?: true
                    if (result) {
                        val active = config.activeProfileName
                        if (active.isNotEmpty()) {
                            val profile = ProviderProfile(
                                name = active,
                                provider = config.apiProvider,
                                apiKey = if (key == "api_key") newValue as String else config.apiKey,
                                model = if (key == "model") newValue as String else config.model,
                                baseUrl = if (key == "base_url") newValue as String else config.baseUrl
                            )
                            profileRepo.saveProfile(profile)
                        }
                    }
                    result
                }
        }
    }

    // --- Text summaries (SimpleSummaryProvider for plain, custom mask for secrets) ---

    private fun setupTextSummaries() {
        val simpleProvider = EditTextPreference.SimpleSummaryProvider.getInstance()

        // Plain text fields — show value as summary
        for (key in listOf("model", "base_url")) {
            findPreference<EditTextPreference>(key)?.summaryProvider = simpleProvider
        }

        // Sensitive fields — show masked value
        val maskProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val v = pref.text
            if (v.isNullOrBlank()) getString(R.string.pref_not_set)
            else if (v.length <= 8) "••••"
            else "${v.take(4)}••••${v.takeLast(4)}"
        }
        for (key in listOf("api_key")) {
            findPreference<EditTextPreference>(key)?.summaryProvider = maskProvider
        }
    }

    // --- Max Steps: EditTextPreference writes String, ConfigRepository reads Int ---

    private fun setupMaxStepsPreference() {
        val pref = findPreference<EditTextPreference>("max_steps") ?: return
        pref.text = config.maxSteps.toString()
        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            val v = it.text?.toIntOrNull() ?: ConfigRepository.DEFAULT_MAX_STEPS
            getString(R.string.pref_max_steps_summary) + " ($v)"
        }

        pref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                val input = (newValue as? String)?.trim()?.toIntOrNull()
                    ?: ConfigRepository.DEFAULT_MAX_STEPS
                val clamped = maxOf(1, input)
                config.maxSteps = clamped
                pref.text = clamped.toString()
                false
            }
    }

    // --- Manage Memory ---

    private fun setupMemoryPreference() {
        findPreference<Preference>("manage_memory")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                startActivity(Intent(requireContext(), MemoryActivity::class.java))
                true
            }
    }

    private fun setupBlacklistPreference() {
        val pref = findPreference<Preference>("manage_blacklist") ?: return
        updateBlacklistSummary(pref)
        pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            showBlacklistDialog()
            true
        }
    }

    private fun updateBlacklistSummary(pref: Preference) {
        val current = config.blacklistedPackages
        pref.summary = if (current.isEmpty()) {
            getString(R.string.pref_app_blacklist_none)
        } else {
            val pm = requireContext().packageManager
            current.take(3).mapNotNull { pkg ->
                try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                catch (_: Exception) { pkg }
            }.joinToString(", ") + if (current.size > 3) " (+${current.size - 3})" else ""
        }
    }

    private fun showBlacklistDialog() {
        val pm = requireContext().packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchableApps = pm.queryIntentActivities(mainIntent, 0)
            .map { it.activityInfo }
            .filter { it.packageName != requireContext().packageName }
            .sortedBy { pm.getApplicationLabel(it.applicationInfo).toString().lowercase() }

        val labels = launchableApps.map { pm.getApplicationLabel(it.applicationInfo).toString() }
            .toTypedArray()
        val packages = launchableApps.map { it.packageName }
        val currentBlacklist = config.blacklistedPackages
        val checkedItems = packages.map { it in currentBlacklist }.toBooleanArray()

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pref_app_blacklist))
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = packages.filterIndexed { i, _ -> checkedItems[i] }.toSet()
                config.blacklistedPackages = selected
                updateBlacklistSummary(findPreference("manage_blacklist")!!)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Backend status and revoke ---

    private fun refreshBackendStatus() {
        val statusPref = findPreference<Preference>("backend_status") ?: return
        val revokePref = findPreference<Preference>("backend_revoke") ?: return

        val backendName = App.agentService?.activeBackendName
        statusPref.summary = when (backendName) {
            "root"       -> "Root — tap to change"
            "shizuku"    -> "Shizuku — tap to change"
            "adb-direct" -> "ADB Direct — tap to change"
            null, "none" -> "Not connected — tap to set up"
            else         -> backendName
        }
        revokePref.isVisible = (backendName != null && backendName != "none")
    }

    private fun setupBackendPreferences() {
        val statusPref = findPreference<Preference>("backend_status") ?: return
        val revokePref = findPreference<Preference>("backend_revoke") ?: return

        refreshBackendStatus()

        statusPref.setOnPreferenceClickListener {
            val name = App.agentService?.activeBackendName
            if (name == "system") {
                Toast.makeText(requireContext(), "System backend is fixed for this build", Toast.LENGTH_SHORT).show()
                return@setOnPreferenceClickListener true
            }
            val intent = android.content.Intent(requireContext(),
                com.shiro.tyfo.backend.SetupActivity::class.java)
            if (name != null && name != "none" && name != "system") {
                intent.putExtra(
                    com.shiro.tyfo.backend.SetupActivity.EXTRA_FORCE_METHOD_CHOOSER,
                    true
                )
            }
            startActivity(intent)
            true
        }

        revokePref.setOnPreferenceClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Revoke Access")
                .setMessage(
                    "Disconnect the privilege backend.\n\nTo fully revoke:\n" +
                    "• Shizuku: Revoke permission in Shizuku app\n" +
                    "• ADB: Disable wireless debugging in Developer Options"
                )
                .setPositiveButton("Revoke") { _, _ ->
                    App.agentService?.revokeBackend()
                    refreshBackendStatus()
                    Toast.makeText(requireContext(), "Backend disconnected", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    // --- Version info ---

    private fun setupVersionInfo() {
        val pref = findPreference<Preference>("version_info") ?: return
        try {
            val info = requireContext().packageManager.getPackageInfo(
                requireContext().packageName, 0
            )
            pref.summary = "v${info.versionName}"
        } catch (_: Exception) {
            pref.summary = "1.0"
        }
    }

    // --- Refresh from deep link import ---

    fun refreshFromConfig() {
        val maxStepsPref = findPreference<EditTextPreference>("max_steps")
        maxStepsPref?.text = config.maxSteps.toString()
        refreshAllProviderFields()
        refreshProfileSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshBackendStatus()
        refreshAssistantSummary()
    }

    // --- Voice assistant (long-press power) ---

    private fun setupAssistantPreference() {
        findPreference<Preference>("assistant_power_key")?.setOnPreferenceClickListener {
            if (!com.shiro.tyfo.assistant.AssistantRole.isHeld(requireContext())) {
                Toast.makeText(requireContext(), R.string.pref_assistant_guide, Toast.LENGTH_LONG).show()
            }
            com.shiro.tyfo.assistant.AssistantRole.openSystemSettings(requireContext())
            true
        }
        findPreference<Preference>("island_accessibility")?.setOnPreferenceClickListener {
            openAccessibilityServiceSettings()
            true
        }
        refreshAssistantSummary()
    }

    private fun refreshAssistantSummary() {
        findPreference<Preference>("assistant_power_key")?.summary = getString(
            if (com.shiro.tyfo.assistant.AssistantRole.isHeld(requireContext())) R.string.pref_assistant_summary_on
            else R.string.pref_assistant_summary_off
        )
        findPreference<Preference>("island_accessibility")?.summary = getString(
            if (com.shiro.tyfo.accessibility.VdAccessibilityService.instance != null) R.string.pref_island_a11y_summary_on
            else R.string.pref_island_a11y_summary_off
        )
    }

    /** 浮窗走辅助功能悬浮窗需要用户在系统里开启 Tyfo 的无障碍服务，这里直接带到该服务的详情页。 */
    private fun openAccessibilityServiceSettings() {
        val component = android.content.ComponentName(
            requireContext(), com.shiro.tyfo.accessibility.VdAccessibilityService::class.java
        ).flattenToString()
        val intents = listOf(
            // 系统设置里单个无障碍服务详情页的 action（未公开常量）
            android.content.Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(android.content.Intent.EXTRA_COMPONENT_NAME, component),
            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: android.content.ActivityNotFoundException) {
            }
        }
    }
}

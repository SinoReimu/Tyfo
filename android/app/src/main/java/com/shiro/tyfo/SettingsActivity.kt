package com.shiro.tyfo

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.shiro.tyfo.config.ConfigDeepLink
import com.shiro.tyfo.config.ConfigRepository
import com.shiro.tyfo.config.ProfileRepository

class SettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    lateinit var config: ConfigRepository
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        config = ConfigRepository(this)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        val onSurfaceColor = ContextCompat.getColor(this, R.color.on_surface)
        toolbar.setTitleTextColor(onSurfaceColor)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.action_settings)
        // Tint back arrow after setDisplayHomeAsUpEnabled has set the icon
        toolbar.navigationIcon?.setTint(onSurfaceColor)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }

        handleConfigIntent(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!supportFragmentManager.popBackStackImmediate()) {
            finish()
        }
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleConfigIntent(intent)
    }

    // --- Sub-screen navigation ---

    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        prefScreen: PreferenceScreen
    ): Boolean {
        val fragment = SettingsFragment()
        val args = Bundle()
        args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, prefScreen.key)
        fragment.arguments = args
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(prefScreen.key)
            .commit()
        return true
    }

    // --- Deep link handling ---

    fun handleConfigIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (!isConfigUri(data)) return
        intent.data = null

        val params = data.queryParameterNames.associateWith { data.getQueryParameter(it) }
        val imported = ConfigDeepLink.parse(params).getOrElse { error ->
            Toast.makeText(this, error.message ?: "Invalid config link", Toast.LENGTH_LONG).show()
            return
        }

        if (isDebuggableBuild()) {
            applyImportedConfig(imported)
            Toast.makeText(this, "API configuration imported", Toast.LENGTH_SHORT).show()
        } else {
            showImportConfirmation(imported)
        }
    }

    private fun isConfigUri(uri: Uri): Boolean =
        uri.scheme == "tyfo" && uri.host == "config"

    private fun isDebuggableBuild(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun showImportConfirmation(imported: ConfigDeepLink.ImportedConfig) {
        val message = """
            Provider: ${imported.provider}
            Model: ${imported.model}
            Base URL: ${imported.baseUrl}
            Max steps: ${imported.maxSteps ?: config.maxSteps}
            API key: ${ConfigDeepLink.redactedApiKey(imported.apiKey)}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Import API configuration?")
            .setMessage(message)
            .setPositiveButton("Import") { _, _ ->
                applyImportedConfig(imported)
                Toast.makeText(this, "API configuration imported", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyImportedConfig(imported: ConfigDeepLink.ImportedConfig) {
        config.apiProvider = imported.provider
        config.apiKey = imported.apiKey
        config.model = imported.model
        config.baseUrl = imported.baseUrl
        imported.maxSteps?.let { config.maxSteps = it }

        val profileRepo = ProfileRepository(config, this)
        val profileName = imported.profile
            ?: profileRepo.generateProfileName(imported.provider, imported.model)
        profileRepo.saveCurrentAsProfile(profileName)

        val fragment = supportFragmentManager.findFragmentById(R.id.settings_container) as? SettingsFragment
        fragment?.refreshFromConfig()
    }
}
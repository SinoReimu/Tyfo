package com.shiro.tyfo.config

import android.content.Context
import androidx.annotation.MainThread

class ProfileRepository(private val config: ConfigRepository, context: Context) {

    companion object {
        private const val PREFS_NAME = "tyfo_profiles"
        private const val KEY_PROFILES = "profiles_json"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    constructor(context: Context) : this(ConfigRepository(context), context)

    fun listProfiles(): List<ProviderProfile> {
        val json = prefs.getString(KEY_PROFILES, "") ?: ""
        return ProviderProfile.fromJsonArray(json)
    }

    fun getProfile(name: String): ProviderProfile? =
        listProfiles().find { it.name == name }

    fun saveCurrentAsProfile(name: String) {
        val profile = ProviderProfile(
            name = name,
            provider = config.apiProvider,
            apiKey = config.apiKey,
            model = config.model,
            baseUrl = config.baseUrl
        )
        saveProfile(profile)
        config.activeProfileName = name
    }

    fun saveProfile(profile: ProviderProfile) {
        val profiles = listProfiles().toMutableList()
        val idx = profiles.indexOfFirst { it.name == profile.name }
        if (idx >= 0) profiles[idx] = profile else profiles.add(profile)
        persist(profiles)
    }

    @MainThread
    fun switchTo(name: String): Boolean {
        val target = getProfile(name) ?: return false
        val currentName = config.activeProfileName
        if (currentName.isNotEmpty()) {
            saveCurrentAsProfile(currentName)
        }
        config.applyProfile(target.provider, target.apiKey, target.model, target.baseUrl, name)
        return true
    }

    fun deleteProfile(name: String) {
        val profiles = listProfiles().toMutableList()
        profiles.removeAll { it.name == name }
        persist(profiles)
        if (config.activeProfileName == name) {
            if (profiles.isNotEmpty()) {
                switchTo(profiles.first().name)
            } else {
                config.activeProfileName = ""
                ensureMigrated()
            }
        }
    }

    fun generateProfileName(provider: String, model: String): String {
        val base = "${provider}/${model}".take(30)
        val existing = listProfiles().map { it.name }.toSet()
        if (base !in existing) return base
        var i = 2
        while ("$base ($i)" in existing) i++
        return "$base ($i)"
    }

    fun ensureMigrated() {
        dropUnsupportedProfiles()
        if (config.activeProfileName.isEmpty()) {
            val name = generateProfileName(config.apiProvider, config.model)
            saveCurrentAsProfile(name)
        }
    }

    /**
     * 以前其他协议的 profile（OpenAI 兼容 / Anthropic / Ollama / Kimi）现在用不了：指向方舟端点的改成方舟，
     * 其余删掉；当前用的 profile 被删时清掉“当前 profile”，配置本身已由 ConfigRepository 迁到方舟。
     */
    private fun dropUnsupportedProfiles() {
        val profiles = listProfiles()
        val kept = profiles.mapNotNull { p ->
            when {
                p.provider in ConfigRepository.SUPPORTED_PROVIDERS -> p
                "volces.com" in p.baseUrl -> p.copy(provider = ConfigRepository.PROVIDER_ARK)
                else -> null
            }
        }
        if (kept == profiles) return
        persist(kept)
        if (kept.none { it.name == config.activeProfileName }) config.activeProfileName = ""
    }

    private fun persist(profiles: List<ProviderProfile>) {
        prefs.edit().putString(KEY_PROFILES, ProviderProfile.toJsonArray(profiles)).apply()
    }
}

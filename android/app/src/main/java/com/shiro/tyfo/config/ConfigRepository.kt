package com.shiro.tyfo.config

import android.content.Context
import android.content.SharedPreferences

class ConfigRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "tyfo_config"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_API_PROVIDER = "api_provider"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_BLACKLISTED_PACKAGES = "blacklisted_packages"

        /**
         * 模型服务的协议。目前只支持火山方舟；加服务商时在这里加一个值，
         * 并在 AgentService.createModelClient 里分派到对应的适配器。
         */
        const val PROVIDER_ARK = "ark"
        val SUPPORTED_PROVIDERS = listOf(PROVIDER_ARK)

        /** 首次运行的默认配置；API Key 来自 local.properties（BuildConfig.PRESET_API_KEY）。 */
        const val DEFAULT_MODEL = "ark-code-latest"
        const val DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/plan/v3"
        const val DEFAULT_MAX_STEPS = 100
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Seed on first run; otherwise SettingsFragment persists the XML defaultValues first.
        if (!prefs.contains(KEY_API_PROVIDER)) {
            prefs.edit()
                .putString(KEY_API_PROVIDER, PROVIDER_ARK)
                .putString(KEY_API_KEY, com.shiro.tyfo.BuildConfig.PRESET_API_KEY)
                .putString(KEY_MODEL, DEFAULT_MODEL)
                .putString(KEY_BASE_URL, DEFAULT_BASE_URL)
                .apply()
        } else if (prefs.getString(KEY_API_PROVIDER, null) !in SUPPORTED_PROVIDERS) {
            // 以前的 openai / anthropic / ollama / kimi 配置：协议都改成方舟，端点、Key、模型保留
            prefs.edit().putString(KEY_API_PROVIDER, PROVIDER_ARK).apply()
        }
    }

    /** Migrate a preference stored as Int to String (for EditTextPreference compat). */
    private fun migrateIntToString(key: String, default: Int): Int {
        val value = prefs.getInt(key, default)
        prefs.edit().remove(key).putString(key, value.toString()).apply()
        return value
    }

    var activeProfileName: String
        get() = prefs.getString(KEY_ACTIVE_PROFILE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE, value).apply()

    fun applyProfile(provider: String, apiKey: String, model: String, baseUrl: String, profileName: String) {
        prefs.edit()
            .putString(KEY_API_PROVIDER, provider.takeIf { it in SUPPORTED_PROVIDERS } ?: PROVIDER_ARK)
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_MODEL, model)
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_ACTIVE_PROFILE, profileName)
            .apply()
    }

    /** 模型服务的协议，取值见 [SUPPORTED_PROVIDERS]；不认识的值当作方舟。 */
    var apiProvider: String
        get() = prefs.getString(KEY_API_PROVIDER, PROVIDER_ARK)?.takeIf { it in SUPPORTED_PROVIDERS } ?: PROVIDER_ARK
        set(value) = prefs.edit().putString(KEY_API_PROVIDER, value.takeIf { it in SUPPORTED_PROVIDERS } ?: PROVIDER_ARK).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var maxSteps: Int
        get() {
            return try {
                prefs.getString(KEY_MAX_STEPS, null)?.toIntOrNull()
                    ?: migrateIntToString(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
            } catch (_: ClassCastException) {
                migrateIntToString(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
            }
        }
        set(value) = prefs.edit().putString(KEY_MAX_STEPS, value.toString()).apply()

    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()

    var blacklistedPackages: Set<String>
        get() = prefs.getString(KEY_BLACKLISTED_PACKAGES, "")!!
            .split(",").filter { it.isNotBlank() }.toSet()
        set(value) = prefs.edit().putString(KEY_BLACKLISTED_PACKAGES, value.joinToString(",")).apply()
}

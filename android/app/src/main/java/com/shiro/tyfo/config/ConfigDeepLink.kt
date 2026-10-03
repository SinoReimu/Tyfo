package com.shiro.tyfo.config

object ConfigDeepLink {

    data class ImportedConfig(
        val provider: String,
        val apiKey: String,
        val model: String,
        val baseUrl: String,
        val maxSteps: Int?,
        val profile: String? = null
    )

    fun parse(params: Map<String, String?>): Result<ImportedConfig> {
        val provider = params["provider"]?.trim()?.lowercase().orEmpty()
            .ifEmpty { ConfigRepository.PROVIDER_ARK }

        if (provider !in ConfigRepository.SUPPORTED_PROVIDERS) {
            return Result.failure(IllegalArgumentException("Unsupported provider: $provider"))
        }

        val apiKey = firstParam(params, "api_key", "key")?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            return Result.failure(IllegalArgumentException("API key is required"))
        }

        val maxSteps = params["max_steps"]?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            raw.toIntOrNull()?.takeIf { it >= 1 }
                ?: return Result.failure(IllegalArgumentException("max_steps must be a positive integer"))
        }

        val profile = params["profile"]?.trim()?.ifEmpty { null }

        return Result.success(
            ImportedConfig(
                provider = provider,
                apiKey = apiKey,
                model = params["model"]?.trim()?.ifEmpty { null } ?: ConfigRepository.DEFAULT_MODEL,
                baseUrl = firstParam(params, "base_url", "url")?.trim()?.ifEmpty { null } ?: ConfigRepository.DEFAULT_BASE_URL,
                maxSteps = maxSteps,
                profile = profile
            )
        )
    }

    fun redactedApiKey(apiKey: String): String {
        if (apiKey.isBlank()) return "(empty)"
        if (apiKey.length <= 8) return "..."
        return "${apiKey.take(3)}...${apiKey.takeLast(4)}"
    }

    private fun firstParam(params: Map<String, String?>, vararg names: String): String? =
        names.firstNotNullOfOrNull { params[it] }

}

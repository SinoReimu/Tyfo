package com.shiro.tyfo.config

import org.junit.Assert.*
import org.junit.Test

class ConfigDeepLinkProfileTest {

    @Test
    fun `parse extracts profile name`() {
        val params = mapOf(
            "provider" to "ark",
            "api_key" to "sk-test",
            "model" to "claude-sonnet-4-7-20250415",
            "base_url" to "https://api.anthropic.com",
            "profile" to "my-work-profile"
        )
        val result = ConfigDeepLink.parse(params).getOrThrow()
        assertEquals("my-work-profile", result.profile)
    }

    @Test
    fun `parse returns null profile when absent`() {
        val params = mapOf(
            "provider" to "ark",
            "api_key" to "sk-test",
            "model" to "gpt-5.5",
            "base_url" to "https://api.openai.com/v1"
        )
        val result = ConfigDeepLink.parse(params).getOrThrow()
        assertNull(result.profile)
    }

    @Test
    fun `parse returns null profile when empty`() {
        val params = mapOf(
            "provider" to "ark",
            "api_key" to "sk-test",
            "profile" to ""
        )
        val result = ConfigDeepLink.parse(params).getOrThrow()
        assertNull(result.profile)
    }
}

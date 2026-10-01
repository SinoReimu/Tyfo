package com.shiro.tyfo.config

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Tests for ConfigRepository using mocked SharedPreferences.
 */
class ConfigRepositoryTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    // Store values in a map to simulate SharedPreferences
    private val prefsStore = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        prefsStore.clear()

        mockEditor = mock {
            on { putString(any(), any()) } doAnswer { invocation ->
                prefsStore[invocation.getArgument(0)] = invocation.getArgument<String>(1)
                mockEditor
            }
            on { putInt(any(), any()) } doAnswer { invocation ->
                prefsStore[invocation.getArgument(0)] = invocation.getArgument<Int>(1)
                mockEditor
            }
            on { putBoolean(any(), any()) } doAnswer { invocation ->
                prefsStore[invocation.getArgument(0)] = invocation.getArgument<Boolean>(1)
                mockEditor
            }
            on { remove(any()) } doAnswer { invocation ->
                prefsStore.remove(invocation.getArgument(0))
                mockEditor
            }
            on { apply() } doAnswer { /* no-op */ }
            on { commit() } doReturn true
        }

        mockPrefs = mock {
            on { getString(any(), anyOrNull()) } doAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val default = invocation.getArgument<String?>(1)
                prefsStore[key] as? String ?: default
            }
            on { getInt(any(), any()) } doAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val default = invocation.getArgument<Int>(1)
                prefsStore[key] as? Int ?: default
            }
            on { getBoolean(any(), any()) } doAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val default = invocation.getArgument<Boolean>(1)
                prefsStore[key] as? Boolean ?: default
            }
            on { contains(any()) } doAnswer { invocation ->
                prefsStore.containsKey(invocation.getArgument<String>(0))
            }
            on { edit() } doReturn mockEditor
        }

        mockContext = mock {
            on { getSharedPreferences(any(), any()) } doReturn mockPrefs
        }
    }

    @Test
    fun `first run seeds preset provider config`() {
        val repo = ConfigRepository(mockContext)
        assertEquals(ConfigRepository.PRESET_PROVIDER, repo.apiProvider)
        assertEquals(com.shiro.tyfo.BuildConfig.PRESET_API_KEY, repo.apiKey)
        assertEquals("ark-code-latest", repo.model)
        assertEquals("https://ark.cn-beijing.volces.com/api/plan/v3", repo.baseUrl)
    }

    @Test
    fun `existing config is not overwritten by preset`() {
        prefsStore["api_provider"] = "anthropic"
        prefsStore["model"] = "custom-model"
        val repo = ConfigRepository(mockContext)
        assertEquals("anthropic", repo.apiProvider)
        assertEquals("custom-model", repo.model)
    }

    @Test
    fun `getter fallbacks still use OpenAI defaults when keys are missing`() {
        prefsStore["api_provider"] = "openai"
        val repo = ConfigRepository(mockContext)
        assertEquals("", repo.apiKey)
        assertEquals(ConfigRepository.DEFAULT_MODEL, repo.model)
        assertEquals(ConfigRepository.DEFAULT_BASE_URL, repo.baseUrl)
    }

    @Test
    fun `default maxSteps is 100`() {
        val repo = ConfigRepository(mockContext)
        assertEquals(ConfigRepository.DEFAULT_MAX_STEPS, repo.maxSteps)
        assertEquals(100, repo.maxSteps)
    }

    @Test
    fun `default debugMode is false`() {
        val repo = ConfigRepository(mockContext)
        assertFalse(repo.debugMode)
    }

    @Test
    fun `save and load apiKey`() {
        val repo = ConfigRepository(mockContext)
        repo.apiKey = "test-api-key-123"
        assertEquals("test-api-key-123", repo.apiKey)
    }

    @Test
    fun `save and load model`() {
        val repo = ConfigRepository(mockContext)
        repo.model = "custom-model-v2"
        assertEquals("custom-model-v2", repo.model)
    }

    @Test
    fun `save and load baseUrl`() {
        val repo = ConfigRepository(mockContext)
        repo.baseUrl = "https://custom.api.com/v1"
        assertEquals("https://custom.api.com/v1", repo.baseUrl)
    }

    @Test
    fun `save and load maxSteps`() {
        val repo = ConfigRepository(mockContext)
        repo.maxSteps = 10
        assertEquals(10, repo.maxSteps)
    }

    @Test
    fun `save and load debugMode`() {
        val repo = ConfigRepository(mockContext)
        repo.debugMode = true
        assertTrue(repo.debugMode)
    }

    @Test
    fun `overwriting a value replaces the old one`() {
        val repo = ConfigRepository(mockContext)
        repo.apiKey = "first-key"
        assertEquals("first-key", repo.apiKey)
        repo.apiKey = "second-key"
        assertEquals("second-key", repo.apiKey)
    }

    @Test
    fun `SharedPreferences is created with correct name`() {
        ConfigRepository(mockContext)
        verify(mockContext).getSharedPreferences(eq("tyfo_config"), eq(Context.MODE_PRIVATE))
    }

    @Test
    fun `setting apiKey calls editor putString and apply`() {
        val repo = ConfigRepository(mockContext)
        clearInvocations(mockEditor)
        repo.apiKey = "my-key"
        verify(mockEditor).putString(eq("api_key"), eq("my-key"))
        verify(mockEditor).apply()
    }

    @Test
    fun `setting maxSteps calls editor putString and apply`() {
        val repo = ConfigRepository(mockContext)
        clearInvocations(mockEditor)
        repo.maxSteps = 20
        verify(mockEditor).putString(eq("max_steps"), eq("20"))
        verify(mockEditor).apply()
    }

    @Test
    fun `setting debugMode calls editor putBoolean and apply`() {
        val repo = ConfigRepository(mockContext)
        clearInvocations(mockEditor)
        repo.debugMode = true
        verify(mockEditor).putBoolean(eq("debug_mode"), eq(true))
        verify(mockEditor).apply()
    }

    // ── isDefaultModelOrUrl ───────────────────────────────────────────────────

    @Test
    fun `isDefaultModelOrUrl returns true for each known default model`() {
        assertTrue(ConfigRepository.isDefaultModelOrUrl(ConfigRepository.DEFAULT_MODEL, ""))
        assertTrue(ConfigRepository.isDefaultModelOrUrl(ConfigRepository.DEFAULT_ANTHROPIC_MODEL, ""))
        assertTrue(ConfigRepository.isDefaultModelOrUrl(ConfigRepository.DEFAULT_OLLAMA_MODEL, ""))
    }

    @Test
    fun `isDefaultModelOrUrl returns true for each known default base URL`() {
        assertTrue(ConfigRepository.isDefaultModelOrUrl("", ConfigRepository.DEFAULT_BASE_URL))
        assertTrue(ConfigRepository.isDefaultModelOrUrl("", ConfigRepository.DEFAULT_ANTHROPIC_BASE_URL))
        assertTrue(ConfigRepository.isDefaultModelOrUrl("", ConfigRepository.DEFAULT_OLLAMA_BASE_URL))
    }

    @Test
    fun `isDefaultModelOrUrl returns false for custom model and url`() {
        assertFalse(ConfigRepository.isDefaultModelOrUrl("qwen3.6-plus", "https://custom.api.com/v1"))
    }

    @Test
    fun `isDefaultModelOrUrl returns false for empty strings`() {
        assertFalse(ConfigRepository.isDefaultModelOrUrl("", ""))
    }

    // ── Settings persistence regression (spinner race condition) ──────────────

    @Test
    fun `custom model is not classified as a default and survives provider change`() {
        // Regression: spinner onItemSelected used to reset the model to provider
        // defaults whenever the activity opened, overwriting user-set custom values.
        // isDefaultModelOrUrl returning false for a custom model means SettingsActivity
        // will NOT overwrite it when the provider spinner initialises.
        val customModel = "qwen3.6-plus"
        val customUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/custom"
        assertFalse(ConfigRepository.isDefaultModelOrUrl(customModel, customUrl))

        val repo = ConfigRepository(mockContext)
        repo.model = customModel
        assertEquals(customModel, repo.model)
    }

    @Test
    fun `multiple properties can be set independently`() {
        val repo = ConfigRepository(mockContext)
        repo.apiKey = "key123"
        repo.model = "model-x"
        repo.baseUrl = "https://example.com"
        repo.maxSteps = 15

        assertEquals("key123", repo.apiKey)
        assertEquals("model-x", repo.model)
        assertEquals("https://example.com", repo.baseUrl)
        assertEquals(15, repo.maxSteps)
    }
}

package com.shiro.tyfo.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 火山方舟（Ark）的 Chat API（/chat/completions，OpenAI 兼容格式 + 方舟的扩展），流式。
 *
 * 方舟特有的处理：
 * - 思考：`thinking` 开关；回复里的 `reasoning_content`（摘要）和 `encrypted_content`（真正的思考，加密）
 *   在后续请求里要原样放回 assistant 消息，不放会降低效果，改动会报签名错误。
 * - tool 消息的 content 只能是字符串：工具结果里的屏幕观察（截图 + 元素清单）挪到这组工具结果之后的一条 user 消息里。
 * - 截图默认按 detail=high 计费，固定 1280 token，与分辨率无关；所以截图给清楚一点不额外花钱。
 */
class ArkClient(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
    private val thinking: Boolean = true,
) : ModelClient {

    override val profile = ModelProfile(
        provider = PROVIDER,
        model = model,
        screenshotMaxWidth = 720,
        screenshotQuality = 75,
        // 至少留最近 2 次观察，模型能对比操作前后的画面；攒到 5 次时一次删掉最旧的 3 次。
        // 删除那一步从第一张被删的图开始重算（之前的前缀照常命中），其余步纯追加。
        // 比只留最新一次多约 40% 输入（30 步模拟），换来前后对比
        keepScreenshots = 2,
        pruneScreenshotsEvery = 3,
    )

    companion object {
        const val PROVIDER = "ark"
        private const val TAG = "ArkClient"
        private const val MAX_RETRIES = 5
        private const val MAX_OUTPUT_TOKENS = 4096
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val READ_TIMEOUT_MS = 120_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 中立消息 → 方舟 Chat API 的 messages。
         *
         * 方舟的 tool 消息只能是字符串：工具结果里的屏幕观察（截图和元素清单）挪到这组工具结果之后的
         * 一条 user 消息里；后面紧跟着用户消息（如补充说明）时合成一条，不出现两条连着的 user 消息。
         */
        fun toJson(messages: List<LlmMessage>): JSONArray {
            val array = JSONArray()
            val pending = mutableListOf<Pair<String, List<LlmMessage.Part>>>()
            fun flushObservation(nextUser: LlmMessage.User?) {
                val parts = JSONArray()
                pending.forEach { (tool, observation) ->
                    parts.put(textPart("Screen after $tool:"))
                    observation.forEach { parts.put(partJson(it)) }
                }
                pending.clear()
                nextUser?.parts?.forEach { parts.put(partJson(it)) }
                array.put(JSONObject().put("role", "user").put("content", parts))
            }
            for (m in messages) {
                if (m !is LlmMessage.ToolResult && pending.isNotEmpty()) {
                    if (m is LlmMessage.User) {
                        flushObservation(m)
                        continue
                    }
                    flushObservation(null)
                }
                when (m) {
                    is LlmMessage.System -> array.put(JSONObject().put("role", "system").put("content", m.text))
                    is LlmMessage.User -> array.put(
                        JSONObject().put("role", "user").put(
                            "content",
                            if (m.parts.all { it is LlmMessage.Part.Text }) m.text
                            else JSONArray().apply { m.parts.forEach { put(partJson(it)) } }
                        )
                    )
                    is LlmMessage.Assistant -> {
                        val o = JSONObject().put("role", "assistant").put("content", m.text ?: "")
                        if (m.toolCalls.isNotEmpty()) {
                            o.put("tool_calls", JSONArray().apply {
                                m.toolCalls.forEach {
                                    put(
                                        JSONObject()
                                            .put("id", it.id)
                                            .put("type", "function")
                                            .put("function", JSONObject().put("name", it.name).put("arguments", it.arguments))
                                    )
                                }
                            })
                        }
                        m.reasoning?.summary?.let { o.put("reasoning_content", it) }
                        m.reasoning?.opaque?.let { o.put("encrypted_content", it) }
                        array.put(o)
                    }
                    is LlmMessage.ToolResult -> {
                        val observation = m.parts.filter { it is LlmMessage.Part.Image || (it as LlmMessage.Part.Text).screenState }
                        val text = m.parts.filterIsInstance<LlmMessage.Part.Text>().filter { !it.screenState }
                            .joinToString("\n") { it.text }
                            .ifEmpty { if (observation.isNotEmpty()) "Done; the screen is below." else "Done." }
                        array.put(
                            JSONObject().put("role", "tool")
                                .put("tool_call_id", m.callId)
                                .put("content", if (m.isError) "Error: $text" else text)
                        )
                        if (observation.isNotEmpty()) pending.add(m.name to observation)
                    }
                }
            }
            if (pending.isNotEmpty()) flushObservation(null)
            return array
        }

        private fun partJson(part: LlmMessage.Part): JSONObject = when (part) {
            is LlmMessage.Part.Text -> textPart(part.text)
            is LlmMessage.Part.Image -> imagePart(part)
        }

        private fun textPart(text: String) = JSONObject().put("type", "text").put("text", text)

        private fun imagePart(image: LlmMessage.Part.Image) = JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${image.base64Jpeg}"))
    }

    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)  // we handle retries ourselves
        .build()

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolSpec>,
        listener: ReplyListener?,
    ): LlmReply = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", toJson(messages))
            put("tools", JSONArray(tools.map { it.toOpenAi() }))
            put("tool_choice", "required")
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
        }.toString()

        for (attempt in 0 until MAX_RETRIES) {
            try {
                Log.d(TAG, "Request to $baseUrl/chat/completions (attempt ${attempt + 1}), payload ${payload.length} chars")
                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()

                val response = httpClient.newCall(request).execute()
                val code = response.code
                if (code !in 200..299) {
                    val errorBody = response.body?.string()?.take(500) ?: ""
                    response.close()
                    if (code in 400..499 && code != 429) {
                        throw ModelException("Ark API error ($code): $errorBody")
                    }
                    if (attempt == MAX_RETRIES - 1) {
                        throw ModelException("Ark API failed after $MAX_RETRIES attempts: HTTP $code")
                    }
                    Log.w(TAG, "HTTP $code on attempt ${attempt + 1}, retrying...")
                    delay((1L shl attempt) * 1000)
                    continue
                }

                val reply = parseStream(response, listener)
                if (reply != null) return@withContext reply
                if (attempt == MAX_RETRIES - 1) throw ModelException("Ark returned an empty reply")
                Log.w(TAG, "Empty reply on attempt ${attempt + 1}, retrying...")
            } catch (e: ModelException) {
                throw e
            } catch (e: IOException) {
                if (attempt == MAX_RETRIES - 1) {
                    throw ModelException("Ark API failed after $MAX_RETRIES attempts: ${e.message}", e)
                }
                Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                delay((1L shl attempt) * 1000)
            } catch (e: Exception) {
                throw ModelException("Ark API call failed: ${e.message}", e)
            }
        }
        throw ModelException("Ark API call failed: exhausted retries")
    }

    /** 流式返回里同一个工具调用的几段，按 index 拼起来。 */
    private class CallBuilder {
        var id: String? = null
        var name = ""
        val arguments = StringBuilder()
    }

    /**
     * 解析流式返回。每段：`{"choices":[{"delta":{"content","reasoning_content","encrypted_content",
     * "tool_calls":[{"index","id","function":{"name","arguments"}}]},"finish_reason"}]}`，最后一段带 usage。
     * 一个工具调用的第一段带 id 和名字，之后的段只追加参数。
     *
     * @return 回复；什么都没拿到时返回 null
     */
    private fun parseStream(response: okhttp3.Response, listener: ReplyListener?): LlmReply? {
        val body = response.body ?: return null
        val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
        val calls = sortedMapOf<Int, CallBuilder>()
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val encrypted = StringBuilder()
        var finishReason: String? = null
        var usage: JSONObject? = null

        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
                event.optJSONObject("usage")?.let { usage = it }
                val choice = event.optJSONArray("choices")?.optJSONObject(0) ?: continue
                choice.stringOrNull("finish_reason")?.let { finishReason = it }
                val delta = choice.optJSONObject("delta") ?: continue

                delta.stringOrNull("reasoning_content")?.let {
                    reasoning.append(it)
                    listener?.onReasoning(reasoning.toString())
                }
                delta.stringOrNull("encrypted_content")?.let { encrypted.append(it) }
                delta.stringOrNull("content")?.let {
                    content.append(it)
                    listener?.onText(content.toString())
                }
                val toolCalls = delta.optJSONArray("tool_calls") ?: continue
                for (i in 0 until toolCalls.length()) {
                    val chunk = toolCalls.getJSONObject(i)
                    val call = calls.getOrPut(chunk.optInt("index", i)) { CallBuilder() }
                    chunk.stringOrNull("id")?.let { call.id = it }
                    val function = chunk.optJSONObject("function") ?: continue
                    // 名字只在第一段里给（有的服务每段都重复一遍，不能拼接）
                    function.stringOrNull("name")?.let { if (call.name.isEmpty()) call.name = it }
                    function.stringOrNull("arguments")?.let { call.arguments.append(it) }
                }
            }
        } finally {
            reader.close()
            body.close()
        }

        val toolCalls = calls.values.map { ToolCall(it.id ?: ResponseParser.newCallId(), it.name, it.arguments.toString()) }
        val text = content.toString().trim().takeIf { it.isNotEmpty() }
        val thought = Reasoning(
            summary = reasoning.toString().trim().takeIf { it.isNotEmpty() },
            opaque = encrypted.toString().takeIf { it.isNotEmpty() },
        ).takeIf { it.summary != null || it.opaque != null }
        val cached = usage?.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens")
        Log.d(TAG, "Reply: finish_reason=$finishReason, ${toolCalls.size} call(s), text ${text?.length ?: 0} chars, " +
            "prompt=${usage?.optInt("prompt_tokens")} cached=$cached completion=${usage?.optInt("completion_tokens")}")
        if (finishReason == "length") Log.w(TAG, "Reply cut off by max_tokens")
        if (toolCalls.isEmpty() && text == null) return null
        return LlmReply(text, toolCalls, thought)
    }

    /** optString 遇到 JSON null 会返回字符串 "null"。 */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }

    override fun shutdown() {
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (_: Exception) {
            // Ignore — may already be shut down
        }
    }
}

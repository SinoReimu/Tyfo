package com.shiro.tyfo.llm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 模型输出的容错解析：工具参数不是合法 JSON 时尽量救回来，模型没走工具调用、把调用写成正文时也尽量认出来。
 */
object ResponseParser {

    private const val TAG = "ResponseParser"

    fun newCallId(): String = "call_" + UUID.randomUUID().toString().replace("-", "").take(16)

    /**
     * 解析一次工具调用的参数。依次尝试：直接解析、补上被截断的 `}`、按 [spec] 声明的参数逐个用正则抠
     * （流式传输偶尔把字符串里的转义引号弄坏，或者模型写出 `"x": 382 896` 这样的值）。全都失败返回 null。
     */
    fun parseArguments(raw: String, spec: ToolSpec?): Map<String, Any?>? {
        val text = raw.trim()
        if (text.isEmpty()) return emptyMap()
        parseObject(text)?.let { return it }
        if (!text.endsWith("}")) parseObject("$text}")?.let { return it }
        val salvaged = spec?.let { salvage(text, it) }
        if (salvaged != null) Log.w(TAG, "Salvaged ${salvaged.keys} from malformed arguments: ${text.take(200)}")
        return salvaged
    }

    /**
     * 模型没调用工具、而是把调用写在正文里时，从文字里找出调用。认这几种写法：
     * `{"name": "tap", "arguments": {...}}`（也认 parameters / input）、
     * 旧的单工具格式 `{"action_type": "tap", "x": 1, ...}`、`{"action": "tap", ...}`，
     * 可以裹在 ``` 代码块或 `<tool_call>` 标签里。
     */
    fun toolCallsFromText(text: String): List<ToolCall> =
        jsonObjectsIn(text).mapNotNull { obj -> toolCallFrom(jsonObjectToMap(obj)) }

    private fun toolCallFrom(map: Map<String, Any?>): ToolCall? {
        val name = map["name"] as? String
        if (!name.isNullOrBlank()) {
            val args = map["arguments"] ?: map["parameters"] ?: map["input"] ?: emptyMap<String, Any?>()
            val json = when (args) {
                is String -> args
                is Map<*, *> -> JSONObject(args).toString()
                else -> "{}"
            }
            return ToolCall(newCallId(), name.trim(), json)
        }
        for (key in listOf("action_type", "action")) {
            val legacy = map[key] as? String
            if (!legacy.isNullOrBlank()) {
                return ToolCall(newCallId(), legacy.trim(), JSONObject(map - key).toString())
            }
        }
        return null
    }

    /**
     * 文字里所有能解析的 JSON 对象（括号配对时跳过字符串里的内容）。从某个 `{` 开始配不上对、
     * 或配上了却不是合法 JSON（比如正文里随手写的花括号），就从下一个 `{` 重新找。
     */
    private fun jsonObjectsIn(text: String): List<JSONObject> {
        val found = mutableListOf<JSONObject>()
        var from = 0
        while (true) {
            val start = text.indexOf('{', from)
            if (start < 0) break
            val end = matchingBrace(text, start)
            val obj = if (end < 0) null else runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
            if (obj != null) found.add(obj)
            from = if (obj != null) end + 1 else start + 1
        }
        return found
    }

    /** 和 [start] 处的 `{` 配对的 `}` 的位置，配不上返回 -1。 */
    private fun matchingBrace(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    private fun parseObject(text: String): Map<String, Any?>? =
        runCatching { jsonObjectToMap(JSONObject(text)) }.getOrNull()

    /** 按声明的参数逐个抠值。字符串值抠到下一个 `,"键":` 为止，这样值里混了没转义的引号也能拿到。 */
    private fun salvage(text: String, spec: ToolSpec): Map<String, Any?>? {
        val map = mutableMapOf<String, Any?>()
        for (param in spec.params) {
            val key = Regex.escape(param.name)
            when (param) {
                is ToolParam.Integer -> Regex("\"$key\"\\s*:\\s*\"?(-?\\d+(?:\\.\\d+)?)")
                    .find(text)?.let { map[param.name] = it.groupValues[1].toDouble().toInt() }
                is ToolParam.Text -> {
                    val value = Regex(
                        "\"$key\"\\s*:\\s*\"(.*?)\"\\s*(?=,\\s*\"\\w+\"\\s*:|\\}\\s*$)",
                        RegexOption.DOT_MATCHES_ALL
                    ).find(text)?.groupValues?.get(1)
                        ?: Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)?.groupValues?.get(1)
                    if (value != null) map[param.name] = unescape(value)
                }
                is ToolParam.Bool -> Regex("\"$key\"\\s*:\\s*\"?(true|false)")
                    .find(text)?.let { map[param.name] = it.groupValues[1] == "true" }
                // 字符串数组：抠出 [...] 里的所有带引号的字符串
                is ToolParam.TextList -> Regex("\"$key\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
                    .find(text)?.groupValues?.get(1)?.let { body ->
                        val items = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(body)
                            .map { unescape(it.groupValues[1]) }.toList()
                        if (items.isNotEmpty()) map[param.name] = items
                    }
                // 点：键后面的头两个数，括号、引号、逗号写没写对都不管（豆包常写成 [270 54]，偶尔漏掉 ]）
                is ToolParam.Point -> Regex("\"$key\"\\s*:\\s*[\\[\"(]?\\s*(?:<point>)?\\s*(-?\\d+(?:\\.\\d+)?)\\s*[, ]\\s*(-?\\d+(?:\\.\\d+)?)")
                    .find(text)?.let { map[param.name] = listOf(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
            }
        }
        return map.ifEmpty { null }
    }

    private fun unescape(s: String): String =
        s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")

    fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = fromJson(json.get(key))
        }
        return map
    }

    private fun fromJson(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> (0 until value.length()).map { fromJson(value.get(it)) }
        else -> value
    }
}

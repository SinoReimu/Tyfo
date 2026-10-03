package com.shiro.tyfo.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具参数的声明（JSON Schema 的一个子集）。发给模型的 schema 和对模型参数的校验都从这里来，
 * 两边不会对不上。
 *
 * [default]：模型没给时用它。必填参数也可以有默认值——schema 里照样标必填，催模型填，
 * 但漏了也不至于让整个调用失败（如 thought）。
 */
sealed class ToolParam(
    val name: String,
    val description: String,
    val required: Boolean,
    val default: Any?,
) {
    /** 整数；超出 [min]..[max] 的值会被夹到范围内，而不是拒绝。 */
    class Integer(
        name: String,
        description: String,
        required: Boolean = true,
        val min: Int? = null,
        val max: Int? = null,
        default: Int? = null,
    ) : ToolParam(name, description, required, default)

    /** 字符串；有 [enum] 时只接受其中之一（不区分大小写）。 */
    class Text(
        name: String,
        description: String,
        required: Boolean = true,
        val enum: List<String>? = null,
        default: String? = null,
    ) : ToolParam(name, description, required, default)

    /** 开关。 */
    class Bool(
        name: String,
        description: String,
        required: Boolean = false,
        default: Boolean? = null,
    ) : ToolParam(name, description, required, default)

    /** 一组短字符串（JSON 数组），如 ask_user 的候选答案；最多 [maxItems] 个，多的截掉。 */
    class TextList(
        name: String,
        description: String,
        required: Boolean = false,
        val maxItems: Int? = null,
    ) : ToolParam(name, description, required, null)

    /**
     * 屏幕上的一个点 `[x, y]`，坐标范围 0..[maxX] / 0..[maxY]，超出的夹回范围内。
     * 用数组而不是两个参数：豆包常把一对坐标塞进 x 一个字段（`"x": "208 661"`），数组形式它写得很规整。
     */
    class Point(
        name: String,
        description: String,
        val maxX: Int,
        val maxY: Int,
        required: Boolean = true,
    ) : ToolParam(name, description, required, null)

    fun schema(): JSONObject = JSONObject().apply {
        when (this@ToolParam) {
            is Integer -> {
                put("type", "integer")
                min?.let { put("minimum", it) }
                max?.let { put("maximum", it) }
            }
            is Text -> {
                put("type", "string")
                enum?.let { put("enum", JSONArray(it)) }
            }
            is Bool -> put("type", "boolean")
            is TextList -> {
                put("type", "array")
                put("items", JSONObject().put("type", "string"))
                maxItems?.let { put("maxItems", it) }
            }
            is Point -> {
                put("type", "array")
                put("items", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", maxOf(maxX, maxY)))
                put("minItems", 2)
                put("maxItems", 2)
            }
        }
        put("description", description)
        // 必填参数的默认值只是兜底，不告诉模型，免得它觉得可以不填
        if (!required && default != null) put("default", default)
    }
}

/** 一个工具的声明，相当于 MCP tools/list 里的一项：名字、说明、入参 schema。 */
class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam>,
) {
    fun inputSchema(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { params.forEach { put(it.name, it.schema()) } })
        put("required", JSONArray(params.filter { it.required }.map { it.name }))
    }

    /** OpenAI chat/completions 格式（方舟 Chat API 用它）的 tools 项。其他格式由各自的适配器从 [inputSchema] 拼。 */
    fun toOpenAi(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function", JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", inputSchema())
        )
}

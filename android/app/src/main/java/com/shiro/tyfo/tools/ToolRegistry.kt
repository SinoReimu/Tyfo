package com.shiro.tyfo.tools

import com.shiro.tyfo.action.Action
import com.shiro.tyfo.llm.ResponseParser
import com.shiro.tyfo.llm.ToolCall
import com.shiro.tyfo.llm.ToolParam
import com.shiro.tyfo.llm.ToolSpec
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** 工具在一次回复里的角色，决定一次回复里的多个调用怎么执行（见 AgentEngine）。 */
enum class ToolKind {
    /** 不碰屏幕：记笔记、记忆、查应用。随时可以执行。 */
    INFO,
    /** 操作屏幕。一次回复里可以连着做几个，按顺序执行，遇到失败就停；做完后模型会收到一张新截图。 */
    SCREEN,
    /** 结束任务（finish）或停下来等用户（ask_user / handoff_user）。不能跟在屏幕操作后面，要先看到操作的结果。 */
    CONTROL,
}

/** 工具执行的结果。[Result] 回给模型后继续；其余几种交给引擎处理流程。 */
sealed interface ToolOutcome {
    data class Result(val text: String, val isError: Boolean = false) : ToolOutcome
    data class Finish(val answer: String, val success: Boolean) : ToolOutcome
    data class AskUser(val question: String) : ToolOutcome
    data class Handoff(val reason: String) : ToolOutcome
}

/** 工具执行时能用的能力，由引擎在每次调用时提供。坐标都是 0-1000（[Points]）。 */
interface ToolContext {
    /** 在虚拟屏上执行一个操作，先过 [com.shiro.tyfo.engine.ActionRepeatGuard] 的重复检查。 */
    suspend fun perform(action: Action): ToolOutcome.Result

    /** 不经重复检查直接执行（不改变屏幕的操作，如查应用列表）。 */
    suspend fun execute(action: Action): ToolOutcome.Result

    /** 这个位置（0-1000）上的界面元素叫什么（文字或描述），用来告诉模型和用户点到了什么。不知道返回 null。 */
    fun labelAt(x: Int, y: Int): String?

    fun note(key: String, value: String)

    suspend fun remember(key: String, value: String, category: String)
}

/**
 * 一个注册好的工具：声明（[spec]）+ 实现。和 MCP 的 tool 一样自描述，
 * 引擎不认识具体的工具，只按 [kind] 安排执行顺序。
 *
 * @param settleMs 执行后等多久再截图，让界面稳定下来
 * @param chainDelayMs 同一次回复里后面还有屏幕操作时，等多久再做下一个（计算器连按不用每下都等界面稳定）
 * @param changesScreen 执行后屏幕应该有变化；没变时提醒模型换个办法
 */
class PhoneTool(
    val spec: ToolSpec,
    val kind: ToolKind,
    val settleMs: Long = 0,
    val changesScreen: Boolean = false,
    val chainDelayMs: Long = settleMs,
    private val handler: suspend (ToolArgs, ToolContext) -> ToolOutcome,
) {
    val name: String get() = spec.name

    suspend fun call(args: ToolArgs, context: ToolContext): ToolOutcome = handler(args, context)
}

/** 校验过的参数：类型已经转好，范围已经夹好，缺省的填了默认值。 */
class ToolArgs(private val values: Map<String, Any?>) {
    fun int(name: String): Int = values[name] as Int
    fun intOrNull(name: String): Int? = values[name] as? Int
    fun string(name: String): String = values[name] as? String ?: ""
    fun stringOrNull(name: String): String? = (values[name] as? String)?.takeIf { it.isNotBlank() }
    fun bool(name: String): Boolean = values[name] as? Boolean ?: false

    @Suppress("UNCHECKED_CAST")
    fun point(name: String): Pair<Int, Int> = values[name] as Pair<Int, Int>

    @Suppress("UNCHECKED_CAST")
    fun pointOrNull(name: String): Pair<Int, Int>? = values[name] as? Pair<Int, Int>

    /** 存进对话历史的参数：规范化后的合法 JSON，原始参数再乱，回放给模型时也不会出错。 */
    fun toJson(): String = JSONObject().apply {
        values.forEach { (k, v) ->
            when (v) {
                null -> Unit
                is Pair<*, *> -> put(k, JSONArray().put(v.first).put(v.second))
                else -> put(k, v)
            }
        }
    }.toString()
}

/**
 * 工具注册表：对外给出所有工具的声明（发给模型），对内把模型的调用解析、校验后交给对应的工具。
 * 参数有问题时返回给模型看得懂的错误，让它自己改正重试。
 */
class ToolRegistry(tools: List<PhoneTool>) {

    private val byName: Map<String, PhoneTool> = tools.associateBy { it.name }

    val tools: List<PhoneTool> = tools
    val specs: List<ToolSpec> = tools.map { it.spec }

    operator fun get(name: String): PhoneTool? = byName[name.trim().lowercase()]

    sealed interface Resolution {
        val call: ToolCall

        data class Ok(override val call: ToolCall, val tool: PhoneTool, val args: ToolArgs) : Resolution
        data class Invalid(override val call: ToolCall, val error: String) : Resolution
    }

    fun resolve(call: ToolCall): Resolution {
        val tool = get(call.name)
            ?: return Resolution.Invalid(
                call, "Unknown tool '${call.name}'. Available tools: ${byName.keys.joinToString(", ")}."
            )
        val raw = ResponseParser.parseArguments(call.arguments, tool.spec)
            ?: return Resolution.Invalid(call, "The arguments for ${tool.name} aren't valid JSON. Call it again with a JSON object.")
        return when (val result = validate(tool.spec, raw)) {
            is Validation.Ok -> Resolution.Ok(call, tool, result.args)
            is Validation.Error -> Resolution.Invalid(call, "${tool.name}: ${result.message}")
        }
    }

    private sealed interface Validation {
        data class Ok(val args: ToolArgs) : Validation
        data class Error(val message: String) : Validation
    }

    private fun validate(spec: ToolSpec, raw: Map<String, Any?>): Validation {
        val input = pointFromXY(spec, raw)
        val values = mutableMapOf<String, Any?>()
        for (param in spec.params) {
            val given = input[param.name].takeUnless { it == null || (it is String && it.isBlank()) }
            if (given == null) {
                when {
                    param.default != null -> values[param.name] = param.default
                    param.required -> return Validation.Error("missing required parameter '${param.name}'.")
                }
                continue
            }
            values[param.name] = when (param) {
                is ToolParam.Integer -> {
                    val number = toInt(given)
                        ?: return Validation.Error("parameter '${param.name}' must be an integer, got $given.")
                    number.coerceIn(param.min ?: Int.MIN_VALUE, param.max ?: Int.MAX_VALUE)
                }
                is ToolParam.Text -> {
                    val text = given as? String ?: given.toString()
                    val options = param.enum
                    if (options == null) text.trim()
                    else options.firstOrNull { it.equals(text.trim(), ignoreCase = true) }
                        ?: return Validation.Error("parameter '${param.name}' must be one of: ${options.joinToString(", ")}.")
                }
                is ToolParam.Bool -> toBool(given)
                    ?: return Validation.Error("parameter '${param.name}' must be true or false, got $given.")
                is ToolParam.Point -> {
                    val (x, y) = toPoint(given)
                        ?: return Validation.Error("parameter '${param.name}' must be a point [x, y], got $given.")
                    x.coerceIn(0, param.maxX) to y.coerceIn(0, param.maxY)
                }
            }
        }
        return Validation.Ok(ToolArgs(values))
    }

    private fun toInt(value: Any): Int? = when (value) {
        is Number -> value.toDouble().roundToInt()
        is String -> (value.trim().toDoubleOrNull() ?: LEADING_NUMBER.find(value.trim())?.value?.toDouble())?.roundToInt()
        is List<*> -> value.firstOrNull()?.let { toInt(it) }
        else -> null
    }

    private fun toBool(value: Any): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.trim().lowercase().let { if (it == "true") true else if (it == "false") false else null }
        else -> null
    }

    /**
     * 一个点的各种写法：`[208, 661]`、`"208 661"`、`"<point>208 661</point>"`、`"(208, 661)"`、`{"x": 208, "y": 661}`。
     * 豆包 GUI 训练用的是 `<point>x y</point>`，偶尔会带出这种写法。
     */
    private fun toPoint(value: Any): Pair<Int, Int>? = when (value) {
        is List<*> -> if (value.size >= 2) {
            val x = value[0]?.let { toInt(it) }
            val y = value[1]?.let { toInt(it) }
            if (x != null && y != null) x to y else null
        } else (value.firstOrNull() as? String)?.let { toPoint(it) }
        is String -> NUMBER.findAll(value).map { it.value.toDouble().roundToInt() }.toList()
            .takeIf { it.size >= 2 }?.let { it[0] to it[1] }
        is Map<*, *> -> {
            val x = value["x"]?.let { toInt(it) }
            val y = value["y"]?.let { toInt(it) }
            if (x != null && y != null) x to y else null
        }
        else -> null
    }

    /** 只有一个点参数的工具，模型偶尔把它写成分开的 x、y（旧习惯），收成一个点。 */
    private fun pointFromXY(spec: ToolSpec, raw: Map<String, Any?>): Map<String, Any?> {
        val point = spec.params.filterIsInstance<ToolParam.Point>().singleOrNull() ?: return raw
        if (raw[point.name] != null || raw["x"] == null || raw["y"] == null) return raw
        return raw + (point.name to listOf(raw["x"], raw["y"]))
    }

    companion object {
        private val NUMBER = Regex("-?\\d+(?:\\.\\d+)?")
        private val LEADING_NUMBER = Regex("^-?\\d+(?:\\.\\d+)?")
    }
}

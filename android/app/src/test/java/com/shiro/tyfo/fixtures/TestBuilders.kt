package com.shiro.tyfo.fixtures

import com.shiro.tyfo.engine.StepResult
import com.shiro.tyfo.llm.LlmReply
import com.shiro.tyfo.llm.ToolCall
import org.json.JSONArray
import org.json.JSONObject

private var callCounter = 0

/** One tool call as the model would send it. Pairs become [x, y] arrays. */
fun toolCall(name: String, args: Map<String, Any?> = emptyMap()): ToolCall {
    val json = JSONObject()
    args.forEach { (k, v) ->
        when (v) {
            null -> Unit
            is Pair<*, *> -> json.put(k, JSONArray().put(v.first).put(v.second))
            else -> json.put(k, v)
        }
    }
    return ToolCall("call_${++callCounter}", name, json.toString())
}

/** A model reply: a narration sentence plus the given calls, in order. */
fun reply(vararg calls: ToolCall, text: String? = "working on it") = LlmReply(text, calls.toList())

private fun single(name: String, narration: String, vararg args: Pair<String, Any?>) =
    reply(toolCall(name, mapOf(*args)), text = narration)

fun buildFinishResponse(answer: String = "task done", success: Boolean = true, narration: String = "done"): LlmReply =
    single("finish", narration, "answer" to answer, "success" to success)

fun buildTapResponse(x: Int, y: Int, narration: String = "tapping"): LlmReply =
    single("tap", narration, "point" to (x to y))

fun buildLongPressResponse(x: Int, y: Int, narration: String = "long pressing"): LlmReply =
    single("long_press", narration, "point" to (x to y))

fun buildOpenAppResponse(appName: String, narration: String = "opening app"): LlmReply =
    single("open_app", narration, "app_name" to appName)

fun buildSwipeResponse(
    startX: Int, startY: Int, endX: Int, endY: Int,
    durationMs: Int? = null, narration: String = "swiping"
): LlmReply = single("swipe", narration, "from" to (startX to startY), "to" to (endX to endY), "duration_ms" to durationMs)

fun buildTypeTextResponse(text: String, submit: Boolean = false, narration: String = "typing"): LlmReply =
    single("type_text", narration, "text" to text, "submit" to submit)

fun buildKeyEventResponse(key: String, narration: String = "pressing key"): LlmReply =
    single("press_key", narration, "key" to key)

fun buildWaitResponse(narration: String = "waiting"): LlmReply =
    single("wait", narration, "seconds" to 1)

fun buildFailResponse(reason: String, narration: String = "giving up"): LlmReply =
    buildFinishResponse(reason, success = false, narration = narration)

fun buildAskUserResponse(question: String, narration: String = "asking user"): LlmReply =
    single("ask_user", narration, "question" to question)

fun buildNoteResponse(key: String, value: String, narration: String = "noting"): LlmReply =
    single("note", narration, "key" to key, "value" to value)

fun buildHandoffResponse(reason: String, narration: String = "handing off"): LlmReply =
    single("handoff_user", narration, "reason" to reason)

fun buildRememberResponse(key: String, value: String, category: String = "", narration: String = "remembering"): LlmReply =
    single("remember", narration, "key" to key, "value" to value, "category" to category)

fun stepResult(
    step: Int = 1,
    actionType: String = "tap",
    thought: String = "test",
    success: Boolean = true,
    detail: String = "",
    durationMs: Long = 100,
    completed: Boolean = false
) = StepResult(step, actionType, thought, success, detail, durationMs, completed)

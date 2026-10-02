package com.shiro.tyfo.llm

/**
 * 发给模型的一条消息，和服务商无关；各个 client 自己转成 OpenAI / Anthropic / Ollama 的格式。
 *
 * 一步的形状：[Assistant]（旁白 + 工具调用）→ 每个调用一条 [ToolResult]。操作屏幕的工具，
 * 结果里带着操作后的截图；服务商不支持工具结果里放图片时，client 把图片挪到随后的一条用户消息里。
 */
sealed class LlmMessage {

    data class System(val text: String) : LlmMessage()

    /** 用户侧消息：任务（带第一张截图）、用户中途的补充、要求模型调用工具的提醒。 */
    data class User(val parts: List<Part>) : LlmMessage() {
        constructor(text: String) : this(listOf(Part.Text(text)))
    }

    /**
     * 模型的回复。[text] 是给用户看的旁白（进度说明），[toolCalls] 是这一步要做的事，
     * [reasoning] 是模型的思考（服务商要求原样回传的部分也在里面）。
     */
    data class Assistant(
        val text: String?,
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoning: Reasoning? = null,
    ) : LlmMessage()

    /** 一次工具调用的结果，跟在发起它的 [Assistant] 后面，每个调用都要有一条。 */
    data class ToolResult(
        val callId: String,
        val name: String,
        val parts: List<Part>,
        val isError: Boolean = false,
    ) : LlmMessage() {
        constructor(callId: String, name: String, text: String, isError: Boolean = false) :
            this(callId, name, listOf(Part.Text(text)), isError)
    }

    sealed class Part {
        /**
         * [screenState]：属于某一步的屏幕观察（界面元素清单）。只对当时的屏幕有用，
         * 删旧截图时一起删，也不存进会话记录。
         */
        data class Text(val text: String, val screenState: Boolean = false) : Part()

        data class Image(val base64Jpeg: String) : Part()
    }
}

/** 消息里的文字部分，图片忽略。 */
val LlmMessage.User.text: String
    get() = parts.textOnly()

val LlmMessage.ToolResult.text: String
    get() = parts.textOnly()

val LlmMessage.User.images: List<LlmMessage.Part.Image>
    get() = parts.filterIsInstance<LlmMessage.Part.Image>()

val LlmMessage.ToolResult.images: List<LlmMessage.Part.Image>
    get() = parts.filterIsInstance<LlmMessage.Part.Image>()

private fun List<LlmMessage.Part>.textOnly(): String =
    filterIsInstance<LlmMessage.Part.Text>().joinToString("\n") { it.text }

/** 模型发起的一次工具调用。[arguments] 是原样的 JSON 文本，可能不合法，由 ToolRegistry 解析和校验。 */
data class ToolCall(val id: String, val name: String, val arguments: String)

/**
 * 模型的思考。[summary] 是可读的摘要（可以给用户看）；[opaque] 是服务商要求在后续请求里原样回传的
 * 内容（Ark 的 encrypted_content、Anthropic thinking 的 signature 之类），只有发出它的那个服务商认得。
 */
data class Reasoning(val summary: String?, val opaque: String?)

/** 模型的一次回复。 */
data class LlmReply(
    val text: String?,
    val toolCalls: List<ToolCall>,
    val reasoning: Reasoning? = null,
)

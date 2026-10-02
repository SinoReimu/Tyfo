package com.shiro.tyfo.llm

/**
 * 一个模型服务的适配器（协议层）。引擎只认中立的格式（[LlmMessage]、[ToolSpec]、[LlmReply]），
 * 每个服务商一个实现，负责转成线上格式、发请求、流式解析，以及服务商特有的处理
 * （思考内容回传、工具结果里的图片放哪……）。
 *
 * 引擎和提示词里需要按服务商调整的地方都从 [profile] 读，不在引擎里写死；
 * 加一个服务商 = 写一个实现 + 声明它的 [ModelProfile]。
 */
interface ModelClient {

    val profile: ModelProfile

    /**
     * 调用一次模型。[tools] 是这次可用的工具，模型必须调用其中至少一个
     * （不遵守的模型可能只回文字，由调用方处理）。[listener] 收流式进度。
     *
     * @throws ModelException 请求最终失败
     */
    suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolSpec>,
        listener: ReplyListener? = null,
    ): LlmReply

    fun shutdown()
}

/** 流式回复的进度，参数都是到目前为止的全文。 */
interface ReplyListener {
    /** 旁白（给用户看的进度说明）。 */
    fun onText(text: String) {}

    /** 思考摘要。 */
    fun onReasoning(summary: String) {}
}

/**
 * 服务商 / 模型的特性：决定截图拍多大、上下文里留几张截图。
 *
 * @param screenshotMaxWidth 截图缩到的宽度
 * @param screenshotQuality 截图的 JPEG 质量
 * @param keepScreenshots 上下文里至少保留最近这么多次屏幕观察（截图 + outline）
 * @param pruneScreenshotsEvery 观察删的时候一次删这么多次。注意删任何一次都会让它之后的前缀缓存失效：
 *   只留 1 次、每步删上一次时，被改的永远是最末尾，代价最小；要留多次时才需要成批删
 */
data class ModelProfile(
    val provider: String,
    val model: String,
    val screenshotMaxWidth: Int,
    val screenshotQuality: Int,
    val keepScreenshots: Int,
    val pruneScreenshotsEvery: Int,
)

class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

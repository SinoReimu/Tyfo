package com.shiro.tyfo.fixtures

import com.shiro.tyfo.llm.LlmMessage
import com.shiro.tyfo.llm.LlmReply
import com.shiro.tyfo.llm.ModelClient
import com.shiro.tyfo.llm.ModelProfile
import com.shiro.tyfo.llm.ReplyListener
import com.shiro.tyfo.llm.ToolSpec

/** A scripted model: replies come from a queue, every request is recorded. */
class FakeModelClient(
    override val profile: ModelProfile = TEST_PROFILE,
) : ModelClient {
    private val responseQueue = ArrayDeque<LlmReply>()
    /** The messages of every request, as they were at the time of the call. */
    val requestHistory = mutableListOf<List<LlmMessage>>()
    val toolsOffered = mutableListOf<List<ToolSpec>>()

    fun enqueue(vararg responses: LlmReply) {
        responseQueue.addAll(responses)
    }

    override suspend fun chat(messages: List<LlmMessage>, tools: List<ToolSpec>, listener: ReplyListener?): LlmReply {
        requestHistory.add(messages.toList())
        toolsOffered.add(tools)
        val reply = responseQueue.removeFirstOrNull()
            ?: error("FakeModelClient: no more responses queued (${requestHistory.size} calls made)")
        reply.reasoning?.summary?.let { listener?.onReasoning(it) }
        reply.text?.let { listener?.onText(it) }
        return reply
    }

    override fun shutdown() {}

    companion object {
        val TEST_PROFILE = ModelProfile(
            provider = "test",
            model = "test-model",
            screenshotMaxWidth = 540,
            screenshotQuality = 75,
            keepScreenshots = 2,
            pruneScreenshotsEvery = 3,
        )
    }
}

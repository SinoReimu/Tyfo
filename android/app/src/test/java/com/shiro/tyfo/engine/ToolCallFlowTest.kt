package com.shiro.tyfo.engine

import com.shiro.tyfo.action.Action
import com.shiro.tyfo.action.ActionExecutor
import com.shiro.tyfo.capture.ScreenCapture
import com.shiro.tyfo.fixtures.FakeModelClient
import com.shiro.tyfo.fixtures.buildFinishResponse
import com.shiro.tyfo.fixtures.buildTapResponse
import com.shiro.tyfo.fixtures.reply
import com.shiro.tyfo.fixtures.toolCall
import com.shiro.tyfo.llm.LlmMessage
import com.shiro.tyfo.llm.LlmReply
import com.shiro.tyfo.llm.ModelProfile
import com.shiro.tyfo.llm.images
import com.shiro.tyfo.llm.text
import com.shiro.tyfo.tools.UiElement
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** How the engine runs tool calls and what it sends back to the model. */
class ToolCallFlowTest {

    private lateinit var executor: ActionExecutor
    private val failing = mutableSetOf<String>()

    @Before
    fun setUp() {
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64(any(), any(), any(), any()) } returns SCREENSHOT
        executor = mockk(relaxed = true)
        coEvery { executor.execute(any(), any()) } answers {
            val action = firstArg<Action>()
            val ok = action.typeName !in failing
            StepResult(secondArg(), action.typeName, "", ok, if (ok) "did ${action.typeName}" else "${action.typeName} failed", 0, false)
        }
    }

    @After
    fun tearDown() {
        unmockkObject(ScreenCapture)
    }

    private class Run(val model: FakeModelClient, val engine: AgentEngine, val steps: List<StepResult>)

    private fun run(
        vararg replies: LlmReply,
        elements: (() -> List<UiElement>?)? = null,
        profile: ModelProfile = FakeModelClient.TEST_PROFILE,
    ): Run = runBlocking {
        val model = FakeModelClient(profile)
        model.enqueue(*replies)
        val engine = AgentEngine(
            model, executor, maxSteps = 10,
            elementsProvider = elements?.let { provide -> { _, _, _ -> provide() } },
            environment = AgentPrompts.Environment(screenWidth = 1080, screenHeight = 2340),
        )
        val steps = mutableListOf<StepResult>()
        val collector = launch { engine.stepResults.collect { steps.add(it) } }
        delay(50)
        engine.start("check the weather")
        val deadline = System.currentTimeMillis() + 20_000
        while (engine.isLoopActive) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("engine didn't finish")
            delay(20)
        }
        delay(50)
        collector.cancel()
        Run(model, engine, steps)
    }

    private fun results(messages: List<LlmMessage>) = messages.filterIsInstance<LlmMessage.ToolResult>()

    private fun imageCount(messages: List<LlmMessage>) = messages.sumOf {
        when (it) {
            is LlmMessage.User -> it.images.size
            is LlmMessage.ToolResult -> it.images.size
            else -> 0
        }
    }

    @Test
    fun `the task opens with a screenshot and an action's result carries the next one`() {
        val run = run(buildTapResponse(500, 300), buildFinishResponse("sunny"))

        val (system, task, assistant, result) = run.model.requestHistory[1]
        assertTrue(system is LlmMessage.System)
        task as LlmMessage.User
        assertEquals(AgentPrompts.task("check the weather"), task.text)
        assertEquals(1, task.images.size)
        assistant as LlmMessage.Assistant
        assertEquals("tapping", assistant.text)
        result as LlmMessage.ToolResult
        assertEquals(assistant.toolCalls.single().id, result.callId)
        assertEquals("Tapped [500, 300]", result.text)
        assertEquals(1, result.images.size)
    }

    @Test
    fun `narration is the progress text of every step`() {
        val run = run(buildTapResponse(1, 2, narration = "Opening the weather app"), buildFinishResponse("sunny"))
        assertEquals("Opening the weather app", run.steps.first { it.actionType == "tap" }.thought)
        val finish = run.steps.last()
        assertEquals("finish", finish.actionType)
        assertEquals("sunny", finish.detail)
        assertEquals("sunny", (run.engine.state.value as AgentState.Idle).resultMessage)
    }

    @Test
    fun `points in odd shapes are normalized and stored as arrays`() {
        val run = run(reply(toolCall("TAP", mapOf("point" to "<point>208 661</point>"))), buildFinishResponse("ok"))
        coVerify { executor.execute(Action.Tap(208, 661), any()) }
        val stored = (run.model.requestHistory[1][2] as LlmMessage.Assistant).toolCalls.single()
        assertEquals("tap", stored.name)
        assertEquals("""{"point":[208,661]}""", stored.arguments)
    }

    @Test
    fun `chained actions run in order and the screenshot follows the last one`() {
        val run = run(
            reply(
                toolCall("tap", mapOf("point" to (500 to 100))),
                toolCall("type_text", mapOf("text" to "weather", "submit" to true)),
            ),
            buildFinishResponse("done"),
        )
        coVerifyOrder {
            executor.execute(Action.Tap(500, 100), any())
            executor.execute(Action.TypeText("weather", submit = true), any())
        }
        val (tap, type) = results(run.model.requestHistory[1])
        assertTrue(tap.images.isEmpty())
        assertEquals(1, type.images.size)
    }

    @Test
    fun `a failed action stops the rest of the reply`() {
        failing.add("tap")
        val run = run(
            reply(toolCall("tap", mapOf("point" to (1 to 2))), toolCall("type_text", mapOf("text" to "x"))),
            buildFinishResponse("done"),
        )
        coVerify(exactly = 0) { executor.execute(match { it is Action.TypeText }, any()) }
        val (tap, type) = results(run.model.requestHistory[1])
        assertTrue(tap.isError)
        assertTrue(tap.text, tap.text.startsWith("tap failed"))
        assertEquals("the screenshot shows the state after the failure", 1, tap.images.size)
        assertEquals(AgentPrompts.NOT_RUN_AFTER_FAILURE, type.text)
    }

    @Test
    fun `notes run alongside actions`() {
        val run = run(
            reply(toolCall("note", mapOf("key" to "JD price", "value" to "5999")), toolCall("tap", mapOf("point" to (1 to 2)))),
            buildFinishResponse("done"),
        )
        assertEquals(listOf("Noted.", "Tapped [1, 2]"), results(run.model.requestHistory[1]).map { it.text })
    }

    @Test
    fun `finish after a screen action in the same reply waits for the screenshot`() {
        val run = run(
            reply(toolCall("tap", mapOf("point" to (1 to 2))), toolCall("finish", mapOf("answer" to "too early"))),
            buildFinishResponse("verified"),
        )
        assertEquals(2, run.model.requestHistory.size)
        assertEquals(AgentPrompts.NOT_RUN_CONTROL_AFTER_ACTIONS, results(run.model.requestHistory[1]).last().text)
        assertEquals("verified", (run.engine.state.value as AgentState.Idle).resultMessage)
    }

    @Test
    fun `finish with success false ends the task as failed`() {
        val run = run(buildFinishResponse("The app isn't installed", success = false))
        assertEquals("The app isn't installed", (run.engine.state.value as AgentState.Error).message)
        val last = run.steps.last()
        assertEquals("fail", last.actionType)
        assertTrue(last.completed)
    }

    @Test
    fun `invalid arguments go back to the model without a new screenshot`() {
        val run = run(reply(toolCall("tap", mapOf("y" to 500))), buildFinishResponse("ok"))
        coVerify(exactly = 0) { executor.execute(any(), any()) }
        val last = run.model.requestHistory[1].last() as LlmMessage.ToolResult
        assertTrue(last.isError)
        assertTrue(last.text, last.text.contains("'point'"))
        assertTrue(last.images.isEmpty())
        verify(exactly = 1) { ScreenCapture.captureBase64(any(), any(), any(), any()) }
    }

    @Test
    fun `list_apps answers right away without a new screenshot`() {
        val run = run(reply(toolCall("list_apps", mapOf("keyword" to "weather"))), buildFinishResponse("ok"))
        coVerify { executor.execute(Action.ListApps("weather"), any()) }
        assertEquals("did list_apps", (run.model.requestHistory[1].last() as LlmMessage.ToolResult).text)
        verify(exactly = 1) { ScreenCapture.captureBase64(any(), any(), any(), any()) }
    }

    @Test
    fun `a reply without a tool call is nudged, and calls written as text are recovered`() {
        val run = run(
            LlmReply("Let me think about it.", emptyList()),
            LlmReply("""{"name": "finish", "arguments": {"answer": "recovered"}}""", emptyList()),
        )
        val second = run.model.requestHistory[1]
        assertEquals(LlmMessage.Assistant("Let me think about it."), second[second.size - 2])
        assertEquals(LlmMessage.User(AgentPrompts.CALL_A_TOOL), second.last())
        assertEquals("recovered", (run.engine.state.value as AgentState.Idle).resultMessage)
    }

    @Test
    fun `history is append-only until old screenshots are pruned in a batch`() {
        val run = run(
            buildTapResponse(100, 100), buildTapResponse(200, 200), buildTapResponse(300, 300),
            buildTapResponse(400, 400), buildFinishResponse("done"),
        )
        val requests = run.model.requestHistory
        // keep 2, prune 3 at a time (FakeModelClient.TEST_PROFILE)
        assertEquals(listOf(1, 2, 3, 4, 2), requests.map { imageCount(it) })
        // Until the prune, every request starts with the previous one: the cached prefix stays valid
        for (i in 1..3) assertEquals(requests[i - 1], requests[i].subList(0, requests[i - 1].size))
        assertFalse(requests[4].subList(0, requests[3].size) == requests[3])
        val omitted = requests[4].count { m ->
            (m as? LlmMessage.ToolResult)?.text?.contains(AgentPrompts.SCREENSHOT_OMITTED) == true ||
                (m as? LlmMessage.User)?.text?.contains(AgentPrompts.SCREENSHOT_OMITTED) == true
        }
        assertEquals(3, omitted)
    }

    @Test
    fun `keeping only the latest observation changes nothing but the end of the history`() {
        val run = run(
            buildTapResponse(100, 100), buildTapResponse(200, 200), buildTapResponse(300, 300), buildFinishResponse("done"),
            profile = FakeModelClient.TEST_PROFILE.copy(keepScreenshots = 1, pruneScreenshotsEvery = 1),
        )
        val requests = run.model.requestHistory
        assertEquals(listOf(1, 1, 1, 1), requests.map { imageCount(it) })
        // Only the last message of the previous request (the one that held its screenshot) changes, so the
        // cached prefix covers everything before it
        for (i in 1 until requests.size) {
            val previous = requests[i - 1]
            assertEquals(previous.dropLast(1), requests[i].take(previous.size - 1))
        }
    }

    private val download = UiElement("Button", text = "Download", left = 106, top = 745, right = 974, bottom = 871, clickable = true)

    @Test
    fun `every screenshot comes with the outline and taps are named after the element`() {
        var screen = listOf(download)
        val run = run(
            buildTapResponse(500, 345), buildFinishResponse("done"),
            elements = { screen.also { screen = emptyList() } },
        )
        val first = run.model.requestHistory[0].last() as LlmMessage.User
        val outline = first.parts.filterIsInstance<LlmMessage.Part.Text>().single { it.screenState }
        assertEquals(AgentPrompts.outline("Button \"Download\" (500,345)\n# 1 element"), outline.text)

        val tap = run.model.requestHistory[1].last() as LlmMessage.ToolResult
        assertEquals("Tapped [500, 345] on \"Download\"", tap.text.lines().first())
        assertTrue(tap.parts.any { it is LlmMessage.Part.Text && it.screenState && it.text.contains("# no elements") })
    }

    @Test
    fun `an outline that didn't change after an action is pointed out`() {
        val run = run(buildTapResponse(500, 345), buildFinishResponse("done"), elements = { listOf(download) })
        val tap = run.model.requestHistory[1].last() as LlmMessage.ToolResult
        assertTrue(tap.text, tap.text.contains(AgentPrompts.SCREEN_UNCHANGED))
    }

    @Test
    fun `old outlines are pruned together with their screenshots`() {
        val run = run(
            buildTapResponse(100, 100), buildTapResponse(200, 200), buildTapResponse(300, 300),
            buildTapResponse(400, 400), buildFinishResponse("done"),
            elements = { listOf(download) },
        )
        val last = run.model.requestHistory[4]
        val outlines = last.sumOf { m ->
            val parts = (m as? LlmMessage.User)?.parts ?: (m as? LlmMessage.ToolResult)?.parts ?: emptyList()
            parts.count { it is LlmMessage.Part.Text && it.screenState }
        }
        assertEquals(imageCount(last), outlines)
    }

    companion object {
        private const val SCREENSHOT = "/9j/4AAQSkZJRg=="
    }
}

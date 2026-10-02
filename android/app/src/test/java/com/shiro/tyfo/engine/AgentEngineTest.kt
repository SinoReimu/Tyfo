package com.shiro.tyfo.engine

import com.shiro.tyfo.action.Action
import com.shiro.tyfo.action.ActionExecutor
import com.shiro.tyfo.capture.ScreenCapture
import com.shiro.tyfo.fixtures.FakeModelClient
import com.shiro.tyfo.fixtures.buildAskUserResponse
import com.shiro.tyfo.fixtures.buildFinishResponse
import com.shiro.tyfo.fixtures.buildOpenAppResponse
import com.shiro.tyfo.fixtures.buildTapResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentEngineTest {

    @Before
    fun setUp() {
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64() } returns FAKE_SCREENSHOT
        every { ScreenCapture.captureBase64(any(), any(), any(), any()) } returns FAKE_SCREENSHOT
    }

    @After
    fun tearDown() {
        unmockkObject(ScreenCapture)
    }

    // --- AgentState data class tests ---

    @Test
    fun `initial state is Idle`() {
        val state: AgentState = AgentState.Idle()
        assertTrue(state is AgentState.Idle)
    }

    @Test
    fun `Running state holds step and thought`() {
        val state = AgentState.Running(step = 3, thought = "opening settings")
        assertEquals(3, state.step)
        assertEquals("opening settings", state.thought)
    }

    @Test
    fun `Error state holds message`() {
        val state = AgentState.Error(message = "API timeout")
        assertEquals("API timeout", state.message)
    }

    @Test
    fun `WaitingForUser state holds question and step`() {
        val state = AgentState.WaitingForUser(question = "Which contact?", step = 3)
        assertEquals("Which contact?", state.question)
        assertEquals(3, state.step)
    }

    @Test
    fun `WaitingForHandoff state holds reason and step`() {
        val state = AgentState.WaitingForHandoff(reason = "need login", step = 5)
        assertEquals("need login", state.reason)
        assertEquals(5, state.step)
    }

    @Test
    fun `Paused is a singleton object`() {
        assertSame(AgentState.Paused, AgentState.Paused)
    }

    // --- StepResult tests ---

    @Test
    fun `StepResult holds all properties`() {
        val result = StepResult(
            step = 1, actionType = "tap", thought = "tapping button",
            success = true, detail = "tapped at (500, 300)", durationMs = 150L,
            completed = false
        )
        assertEquals(1, result.step)
        assertEquals("tap", result.actionType)
        assertTrue(result.success)
        assertFalse(result.completed)
    }

    @Test
    fun `StepResult completed flag for finish action`() {
        val result = StepResult(
            step = 5, actionType = "finish", thought = "task complete",
            success = true, detail = "completed", durationMs = 50L, completed = true
        )
        assertTrue(result.completed)
    }

    // --- Engine integration tests with FakeModelClient + MockK ---

    @Test
    fun `engine starts in Idle state`() {
        val engine = createEngine()
        val state = engine.state.value
        assertTrue("Engine should start Idle", state is AgentState.Idle)
    }

    @Test
    fun `engine with finish-only response completes`() = runTest {
        val fakeLlm = FakeModelClient()
        fakeLlm.enqueue(buildFinishResponse("done"))

        val actionExecutor = mockk<ActionExecutor>(relaxed = true)
        val engine = AgentEngine(
            model = fakeLlm,
            actionExecutor = actionExecutor,
            maxSteps = 10
        )

        engine.start("say hello")

        // Wait for engine to finish
        eventually { assertTrue(engine.state.value is AgentState.Idle) }

        // finish action should NOT call actionExecutor.execute (intercepted by engine)
        coVerify(exactly = 0) { actionExecutor.execute(any(), any()) }
        assertEquals(1, fakeLlm.requestHistory.size)
    }

    @Test
    fun `engine respects maxSteps limit`() = runTest {
        val fakeLlm = FakeModelClient()
        // Queue more tap responses than maxSteps allows
        repeat(10) { fakeLlm.enqueue(buildTapResponse(500, 500)) }

        val actionExecutor = mockk<ActionExecutor>(relaxed = true)
        coEvery { actionExecutor.execute(any(), any()) } returns StepResult(
            step = 1, actionType = "tap", thought = "tap", success = true,
            detail = "", durationMs = 10, completed = false
        )

        val engine = AgentEngine(
            model = fakeLlm,
            actionExecutor = actionExecutor,
            maxSteps = 3
        )

        engine.start("infinite task")
        eventually { assertTrue(engine.state.value is AgentState.Idle) }

        assertTrue("Should not exceed maxSteps", fakeLlm.requestHistory.size <= 4)
    }

    @Test
    fun `takeover while asking keeps the question after resume`() = runTest {
        val fakeLlm = FakeModelClient()
        fakeLlm.enqueue(buildAskUserResponse("continue?"))
        fakeLlm.enqueue(buildFinishResponse("done"))
        val engine = AgentEngine(
            model = fakeLlm,
            actionExecutor = mockk(relaxed = true),
            maxSteps = 10
        )

        engine.start("ask first")
        eventually { assertTrue("state=${engine.state.value}", engine.state.value is AgentState.WaitingForUser) }

        // User takes over and hands control back: the loop is still blocked on the answer
        engine.pause()
        assertEquals(AgentState.Paused, engine.state.value)
        engine.resume()
        val state = engine.state.value
        assertTrue("question should be restored, was $state", state is AgentState.WaitingForUser)
        assertEquals("continue?", (state as AgentState.WaitingForUser).question)

        engine.submitUserResponse("yes")
        eventually { assertTrue("state=${engine.state.value}", engine.state.value is AgentState.Idle) }
        assertEquals(2, fakeLlm.requestHistory.size)
    }

    // --- Helpers ---

    companion object {
        private const val FAKE_SCREENSHOT = "/9j/4AAQSkZJRg=="
    }

    private fun createEngine(
        fakeLlm: FakeModelClient = FakeModelClient(),
        maxSteps: Int = 100
    ): AgentEngine {
        val actionExecutor = mockk<ActionExecutor>(relaxed = true)
        return AgentEngine(
            model = fakeLlm,
            actionExecutor = actionExecutor,
            maxSteps = maxSteps
        )
    }

    private suspend fun eventually(
        timeoutMs: Long = 10000,
        intervalMs: Long = 100,
        block: () -> Unit
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try { block(); return }
            catch (e: Throwable) {
                lastError = e
                kotlinx.coroutines.delay(intervalMs)
            }
        }
        throw AssertionError("Timed out after ${timeoutMs}ms", lastError)
    }
}

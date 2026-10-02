package com.shiro.tyfo.action

import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import com.shiro.tyfo.backend.PrivilegeBackend
import com.shiro.tyfo.input.InputInjector
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ActionExecutor]: each [Action] reaches the right [InputInjector] / [AppLauncher] call
 * and comes back as a [com.shiro.tyfo.engine.StepResult] the model can read.
 *
 * The injector and launcher are created inside the executor, so they're intercepted with
 * mockkConstructor; the Context only needs to hand out the system services their constructors ask for.
 */
class ActionExecutorTest {

    private lateinit var context: Context
    private val backend = mockk<PrivilegeBackend>(relaxed = true)

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns mockk<ClipboardManager>(relaxed = true)
        every { context.getSystemService(Context.WINDOW_SERVICE) } returns mockk<WindowManager>(relaxed = true)
        mockkConstructor(InputInjector::class, AppLauncher::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun executor(
        displayId: Int = 0,
        blacklisted: Set<String> = emptySet(),
        onOpenAppSuccess: ((String) -> Unit)? = null,
    ) = ActionExecutor(
        context, displayId,
        onOpenAppSuccess = onOpenAppSuccess,
        backend = backend,
        blacklistedPackages = blacklisted,
    )

    // ── Data model ──────────────────────────────────────────────────────────

    @Test
    fun `actions carry their type names and defaults`() {
        assertEquals("tap", Action.Tap(1, 2).typeName)
        assertEquals("long_press", Action.LongPress(1, 2).typeName)
        assertEquals("swipe", Action.Swipe(1, 2, 3, 4).typeName)
        assertEquals("key_event", Action.KeyEvent("back").typeName)
        assertEquals("type_text", Action.TypeText("a").typeName)
        assertEquals("open_app", Action.OpenApp("a").typeName)
        assertEquals("list_apps", Action.ListApps().typeName)
        assertEquals("wait", Action.Wait().typeName)
        assertEquals(Action.SWIPE_DEFAULT_MS, Action.Swipe(1, 2, 3, 4).durationMs)
        assertEquals(2, Action.Wait().seconds)
        assertEquals(1000L, Action.LongPress(1, 2).durationMs)
        assertFalse(Action.TypeText("a").submit)
        assertEquals("", Action.Tap(1, 2).thought)
    }

    // ── Touch ───────────────────────────────────────────────────────────────

    @Test
    fun `tap forwards normalized coordinates and keeps the thought`() = runTest {
        every { anyConstructed<InputInjector>().tap(120, 340) } returns true

        val result = executor().execute(Action.Tap(120, 340, thought = "press OK"), step = 3)

        assertTrue(result.success)
        assertEquals(3, result.step)
        assertEquals("tap", result.actionType)
        assertEquals("press OK", result.thought)
        assertEquals("Tapped at (120, 340)", result.detail)
        assertFalse(result.completed)
    }

    @Test
    fun `failed tap is reported as a failure`() = runTest {
        every { anyConstructed<InputInjector>().tap(any(), any()) } returns false

        val result = executor().execute(Action.Tap(1, 2), step = 1)

        assertFalse(result.success)
    }

    @Test
    fun `long press forwards the point and duration`() = runTest {
        coEvery { anyConstructed<InputInjector>().longPress(50, 60, 1500L) } returns true

        val result = executor().execute(Action.LongPress(50, 60, durationMs = 1500L), step = 1)

        assertTrue(result.success)
        assertEquals("Long pressed at (50, 60)", result.detail)
        coVerify { anyConstructed<InputInjector>().longPress(50, 60, 1500L) }
    }

    @Test
    fun `swipe forwards start, end and duration`() = runTest {
        coEvery { anyConstructed<InputInjector>().swipe(620, 440, 620, 395, 800L) } returns true

        val result = executor().execute(Action.Swipe(620, 440, 620, 395, 800L), step = 1)

        assertTrue(result.success)
        assertEquals("Swiped (620, 440) -> (620, 395) in 800ms", result.detail)
    }

    @Test
    fun `injector exception becomes a failed step with the message`() = runTest {
        every { anyConstructed<InputInjector>().tap(any(), any()) } throws IllegalStateException("boom")

        val result = executor().execute(Action.Tap(1, 2), step = 1)

        assertFalse(result.success)
        assertEquals("Error: boom", result.detail)
    }

    // ── Keys and text ───────────────────────────────────────────────────────

    @Test
    fun `key event is injected on the physical display`() = runTest {
        every { anyConstructed<InputInjector>().keyEvent("home") } returns true

        val result = executor(displayId = 0).execute(Action.KeyEvent("home"), step = 1)

        assertTrue(result.success)
        assertEquals("Key event: home", result.detail)
    }

    @Test
    fun `home and recent are rejected on a virtual display without injecting`() = runTest {
        for (key in listOf("home", "recent")) {
            val result = executor(displayId = 7).execute(Action.KeyEvent(key), step = 1)
            assertFalse("$key should be rejected", result.success)
            assertTrue(result.detail.contains("open_app"))
        }
        verify(exactly = 0) { anyConstructed<InputInjector>().keyEvent(any()) }
    }

    @Test
    fun `delete reaches the injector on a virtual display`() = runTest {
        every { anyConstructed<InputInjector>().keyEvent("delete") } returns true

        val result = executor(displayId = 7).execute(Action.KeyEvent("delete"), step = 1)

        assertTrue(result.success)
        assertEquals("Key event: delete", result.detail)
        verify { anyConstructed<InputInjector>().keyEvent("delete") }
    }

    @Test
    fun `back still works on a virtual display`() = runTest {
        every { anyConstructed<InputInjector>().keyEvent("back") } returns true

        val result = executor(displayId = 7).execute(Action.KeyEvent("back"), step = 1)

        assertTrue(result.success)
    }

    @Test
    fun `type text forwards the whole string without pressing enter`() = runTest {
        coEvery { anyConstructed<InputInjector>().typeText("你好 world") } returns true

        val result = executor().execute(Action.TypeText("你好 world"), step = 1)

        assertTrue(result.success)
        assertEquals("Typed: 你好 world", result.detail)
        verify(exactly = 0) { anyConstructed<InputInjector>().keyEvent(any()) }
    }

    @Test
    fun `type text with submit types and then presses enter`() = runTest {
        coEvery { anyConstructed<InputInjector>().typeText("电池") } returns true
        every { anyConstructed<InputInjector>().keyEvent("enter") } returns true

        val result = executor(displayId = 7).execute(Action.TypeText("电池", submit = true), step = 1)

        assertTrue(result.success)
        assertEquals("Typed: 电池 + enter", result.detail)
        coVerifyOrder {
            anyConstructed<InputInjector>().typeText("电池")
            anyConstructed<InputInjector>().keyEvent("enter")
        }
    }

    @Test
    fun `submit isn't pressed when typing failed`() = runTest {
        coEvery { anyConstructed<InputInjector>().typeText(any()) } returns false

        val result = executor().execute(Action.TypeText("x", submit = true), step = 1)

        assertFalse(result.success)
        verify(exactly = 0) { anyConstructed<InputInjector>().keyEvent(any()) }
    }

    @Test
    fun `a failed enter after typing fails the step`() = runTest {
        coEvery { anyConstructed<InputInjector>().typeText(any()) } returns true
        every { anyConstructed<InputInjector>().keyEvent("enter") } returns false

        val result = executor().execute(Action.TypeText("x", submit = true), step = 1)

        assertFalse(result.success)
    }

    @Test
    fun `wait sleeps for the requested seconds`() = runTest {
        val result = executor().execute(Action.Wait(seconds = 3), step = 1)

        assertTrue(result.success)
        assertEquals("Waited 3s", result.detail)
        assertEquals(3000L, testScheduler.currentTime)
    }

    // ── Apps ────────────────────────────────────────────────────────────────

    @Test
    fun `open app reports the launch and notifies the launched package`() = runTest {
        every { anyConstructed<AppLauncher>().launch("微信") } returns LaunchResult("Opened 微信", "com.tencent.mm")
        val launched = mutableListOf<String>()

        val result = executor(onOpenAppSuccess = { launched += it }).execute(Action.OpenApp("微信"), step = 1)

        assertTrue(result.success)
        assertEquals("Opened 微信", result.detail)
        assertEquals(listOf("com.tencent.mm"), launched)
    }

    @Test
    fun `open app that isn't found fails with the launcher's description`() = runTest {
        every { anyConstructed<AppLauncher>().launch(any()) } returns LaunchResult("App not found: foo", null)

        val result = executor().execute(Action.OpenApp("foo"), step = 1)

        assertFalse(result.success)
        assertEquals("App not found: foo", result.detail)
    }

    @Test
    fun `open app of a protected package is refused`() = runTest {
        every { anyConstructed<AppLauncher>().launch(any()) } returns LaunchResult("Opened Bank", "com.bank")
        val launched = mutableListOf<String>()

        val result = executor(blacklisted = setOf("com.bank"), onOpenAppSuccess = { launched += it })
            .execute(Action.OpenApp("Bank"), step = 1)

        assertFalse(result.success)
        assertEquals("Cannot open protected app: Bank", result.detail)
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `list apps with a keyword lists the matches`() = runTest {
        every { anyConstructed<AppLauncher>().listApps("map") } returns listOf("Maps", "高德地图")

        val result = executor().execute(Action.ListApps("map"), step = 1)

        assertTrue(result.success)
        assertEquals("Installed apps matching 'map' (2): Maps, 高德地图", result.detail)
    }

    @Test
    fun `list apps without a match suggests another keyword`() = runTest {
        every { anyConstructed<AppLauncher>().listApps("zzz") } returns emptyList()

        val result = executor().execute(Action.ListApps("zzz"), step = 1)

        assertTrue(result.success)
        assertTrue(result.detail.startsWith("No apps found matching 'zzz'"))
    }

    @Test
    fun `list apps without a keyword lists everything`() = runTest {
        every { anyConstructed<AppLauncher>().listApps(null) } returns listOf("A", "B", "C")

        val result = executor().execute(Action.ListApps(""), step = 1)

        assertEquals("Installed apps (3): A, B, C", result.detail)
    }
}

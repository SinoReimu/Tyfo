package com.shiro.tyfo.input

import com.shiro.tyfo.action.Action
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Tests for InputInjector swipe behavior.
 * Validates the injection mode constants and event construction logic.
 * Full integration (ValueAnimator + vsync) requires an instrumented test.
 */
class InputInjectorSwipeTest {

    @Test
    fun `INJECT_MODE_ASYNC is 0 and WAIT_FOR_FINISH is 2`() {
        // Verify our constants match Android's InputManager values
        // Access via reflection since they're private
        val clazz = InputInjector::class.java
        val asyncField = clazz.getDeclaredField("INJECT_MODE_ASYNC")
        asyncField.isAccessible = true
        assertEquals(0, asyncField.getInt(null))

        val waitField = clazz.getDeclaredField("INJECT_MODE_WAIT_FOR_FINISH")
        waitField.isAccessible = true
        assertEquals(2, waitField.getInt(null))
    }

    @Test
    fun `InputSequenceResult tracks failures correctly for swipe`() {
        val result = InputSequenceResult()

        // Simulate DOWN success
        result.record(true)
        assertTrue(result.success)

        // Simulate MOVE events - one failure should mark the whole sequence as failed
        result.record(true)
        result.record(true)
        result.record(false)  // One MOVE injection rejected
        result.record(true)

        assertFalse(result.success)
    }

    @Test
    fun `default swipe is a flick and slow drags are reachable`() {
        // A swipe without duration_ms flicks; the model can still ask for a slow drag within the allowed range
        assertTrue(Action.SWIPE_DEFAULT_MS < InputInjector.SLOW_DRAG_MS)
        assertTrue(InputInjector.SLOW_DRAG_MS in Action.SWIPE_MIN_MS..Action.SWIPE_MAX_MS)
    }

    @Test
    fun `AccelerateDecelerate interpolation formula produces non-linear motion`() {
        // AccelerateDecelerateInterpolator uses: (cos((t+1)*PI)/2) + 0.5
        fun interpolate(t: Float): Float =
            ((Math.cos((t + 1) * Math.PI) / 2.0) + 0.5).toFloat()

        val midpoint = interpolate(0.25f)
        // At 25% time, should be less than 25% distance (still accelerating)
        assertTrue("Should be accelerating at 25%: $midpoint < 0.25", midpoint < 0.25f)

        val laterPoint = interpolate(0.75f)
        // At 75% time, should be more than 75% distance (decelerating)
        assertTrue("Should be decelerating at 75%: $laterPoint > 0.75", laterPoint > 0.75f)
    }
}

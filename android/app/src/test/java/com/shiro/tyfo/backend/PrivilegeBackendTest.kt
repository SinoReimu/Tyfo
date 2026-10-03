package com.shiro.tyfo.backend

import org.junit.Test
import org.junit.Assert.*

class PrivilegeBackendTest {
    @Test
    fun `capabilities reports backend name`() {
        val caps = BackendCapabilities(
            name = "test",
            canInjectInput = true,
            canCaptureScreen = true,
            canCaptureSecure = false,
            canCreateVirtualDisplay = true,
        )
        assertEquals("test", caps.name)
        assertFalse(caps.canCaptureSecure)
        assertTrue(caps.canInjectInput)
    }

    @Test
    fun `NoBackend can do nothing`() {
        assertEquals("none", NoBackend.capabilities.name)
        assertFalse(NoBackend.capabilities.canInjectInput)
        assertNull(NoBackend.captureScreen(0))
        assertEquals(-1, NoBackend.createVirtualDisplay("vd", 100, 100, 160, 0))
        assertFalse(NoBackend.startActivityOnDisplay("intent:#Intent;end", 2))
    }

    @Test
    fun `NONE capabilities has nothing`() {
        val caps = BackendCapabilities.NONE
        assertEquals("none", caps.name)
        assertFalse(caps.canCaptureSecure)
        assertFalse(caps.canInjectInput)
    }

    @Test
    fun `fromProbeMask decodes bitmask correctly`() {
        val caps = BackendCapabilities.fromProbeMask(
            BackendCapabilities.CAP_INJECT_INPUT or BackendCapabilities.CAP_CAPTURE_SCREEN or BackendCapabilities.CAP_CREATE_VD,
            "shizuku"
        )
        assertEquals("shizuku", caps.name)
        assertTrue(caps.canInjectInput)
        assertTrue(caps.canCaptureScreen)
        assertFalse(caps.canCaptureSecure)
        assertTrue(caps.canCreateVirtualDisplay)
    }

    @Test
    fun `fromProbeMask with zero mask gives nothing`() {
        val caps = BackendCapabilities.fromProbeMask(0, "empty")
        assertFalse(caps.canInjectInput)
        assertFalse(caps.canCaptureScreen)
        assertFalse(caps.canCaptureSecure)
        assertFalse(caps.canCreateVirtualDisplay)
    }

    @Test
    fun `fromProbeMask with full mask gives everything`() {
        val caps = BackendCapabilities.fromProbeMask(0xF, "full")
        assertTrue(caps.canInjectInput)
        assertTrue(caps.canCaptureScreen)
        assertTrue(caps.canCaptureSecure)
        assertTrue(caps.canCreateVirtualDisplay)
    }
}

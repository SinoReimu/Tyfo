package com.shiro.tyfo.display

import com.shiro.tyfo.display.AppOccupancyPolicy.Decision
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class AppOccupancyPolicyTest {

    private val occupied = setOf("com.tencent.mm")
    private val launcherCategories = setOf(Intent.CATEGORY_LAUNCHER)

    private fun decide(
        action: String? = Intent.ACTION_MAIN,
        categories: Set<String>? = launcherCategories,
        pkg: String? = "com.tencent.mm",
        agentLaunching: Boolean = false
    ) = AppOccupancyPolicy.decide(action, categories, pkg, occupied, agentLaunching)

    @Test
    fun `home screen icon tap on occupied app is blocked`() {
        assertEquals(Decision.BLOCK, decide())
    }

    @Test
    fun `agent's own launch of occupied app is allowed`() {
        assertEquals(Decision.ALLOW, decide(agentLaunching = true))
    }

    @Test
    fun `apps the agent is not using are untouched`() {
        assertEquals(Decision.ALLOW, decide(pkg = "com.taobao.taobao"))
        assertEquals(Decision.ALLOW, decide(pkg = null))
    }

    @Test
    fun `nothing is blocked once the app is released`() {
        assertEquals(
            Decision.ALLOW,
            AppOccupancyPolicy.decide(Intent.ACTION_MAIN, launcherCategories, "com.tencent.mm", emptySet(), false)
        )
    }

    @Test
    fun `non-launcher start of occupied app is allowed but watched`() {
        // Could be the app navigating inside the VD, or a notification opening it on Display 0.
        assertEquals(Decision.ALLOW_AND_WATCH, decide(action = Intent.ACTION_VIEW, categories = null))
        assertEquals(Decision.ALLOW_AND_WATCH, decide(action = Intent.ACTION_MAIN, categories = null))
    }
}

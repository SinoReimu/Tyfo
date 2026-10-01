package com.shiro.tyfo.overlay

import com.shiro.tyfo.AgentService
import com.shiro.tyfo.engine.AgentState

object OverlayStatePolicy {
    fun isActive(state: AgentState?, displayState: AgentService.DisplayState): Boolean {
        return state is AgentState.Running ||
                state is AgentState.WaitingForUser ||
                state is AgentState.WaitingForHandoff ||
                (state is AgentState.Paused && displayState == AgentService.DisplayState.TAKEOVER)
    }
}

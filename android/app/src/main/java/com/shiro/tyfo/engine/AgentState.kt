package com.shiro.tyfo.engine

sealed class AgentState {
    data class Idle(val resultMessage: String? = null) : AgentState()
    /**
     * [partial]：旁白还在流式生成，[thought] 只是目前为止的半句。界面可以逐字显示；
     * 通知、聊天记录这类一条一条发的，只取生成完的（partial = false）。
     */
    data class Running(val step: Int, val thought: String, val partial: Boolean = false) : AgentState()
    object Paused : AgentState()
    data class Error(val message: String) : AgentState()
    data class WaitingForUser(val question: String, val step: Int, val options: List<String> = emptyList()) : AgentState()
    data class WaitingForHandoff(val reason: String, val step: Int) : AgentState()
}

/** 任务还没结束：执行中、暂停、等用户回答、等用户接管都算占用。 */
val AgentState?.isTaskActive: Boolean
    get() = this is AgentState.Running || this is AgentState.Paused ||
        this is AgentState.WaitingForUser || this is AgentState.WaitingForHandoff

package ai.opencyvis.engine

sealed class AgentState {
    data class Idle(val resultMessage: String? = null) : AgentState()
    data class Running(val step: Int, val thought: String) : AgentState()
    object Paused : AgentState()
    data class Error(val message: String) : AgentState()
    data class WaitingForUser(val question: String, val step: Int) : AgentState()
    data class WaitingForHandoff(val reason: String, val step: Int) : AgentState()
}

/** 任务还没结束：执行中、暂停、等用户回答、等用户接管都算占用。 */
val AgentState?.isTaskActive: Boolean
    get() = this is AgentState.Running || this is AgentState.Paused ||
        this is AgentState.WaitingForUser || this is AgentState.WaitingForHandoff

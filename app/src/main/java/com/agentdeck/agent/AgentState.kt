package com.agentdeck.agent

/**
 * States of the agent loop.
 *
 * The loop advances as
 * `IDLE -> INJECTING -> WAITING_REPLY -> PARSING -> DISPATCHING -> FEEDBACK`
 * and returns to `WAITING_REPLY` after each observation, or to `FINAL` when
 * the model answered with a `<final>` element.
 */
enum class AgentState {
    /** No task is running. */
    IDLE,

    /** The system prompt / observation is being written into the page. */
    INJECTING,

    /** Waiting for the page to finish producing the assistant reply. */
    WAITING_REPLY,

    /** The reply is being pulled and parsed. */
    PARSING,

    /** The parsed tool calls are being executed. */
    DISPATCHING,

    /** Tool results are being sent back to the model. */
    FEEDBACK,

    /** The model produced a final answer. */
    FINAL,
    ;

    val isRunning: Boolean
        get() = this != IDLE && this != FINAL
}

/**
 * Snapshot of the loop, exposed to the UI and the notification.
 *
 * @param state current state.
 * @param round 1-based round counter.
 * @param lastTool name of the tool executed in the previous round.
 * @param message human readable detail of the current step.
 * @param finalText the model's final answer, when the loop has finished.
 */
data class AgentStatus(
    val state: AgentState = AgentState.IDLE,
    val round: Int = 0,
    val lastTool: String = "",
    val message: String = "",
    val finalText: String = "",
)

package com.workorder.agent;

/** 调查运行的对外状态（`docs/AGENT-PLAN.md` §3.3 定稿）。 */
public enum AgentStatus {

    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    /** 是否终态。**没有任何路径允许停在 RUNNING**（§3.3"永久调查中"的反面）。 */
    public boolean isTerminal() {
        return this != RUNNING;
    }
}

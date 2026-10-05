package com.workorder.agent;

/**
 * 终态原因码 + 人话说明。
 *
 * <p>原因码全集见 `docs/AGENT-PLAN.md` §3.3；码是机器判据，说明只用于展示与日志。
 */
public record AgentFailure(String code, String message) {

    public AgentFailure {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("失败必须带原因码");
        }
    }

    public static AgentFailure of(String code, String message) {
        return new AgentFailure(code, message);
    }
}

package com.workorder.agent;

/**
 * 模型边界的失败。
 *
 * <p>{@code code} 直接取 `docs/AGENT-PLAN.md` §3.3 的原因码（{@code MODEL_TIMEOUT} /
 * {@code RESPONSE_TOO_LARGE} / {@code MODEL_HTTP_ERROR} / {@code MODEL_UNREACHABLE} /
 * {@code MODEL_PROTOCOL_ERROR}），由调查循环翻译成终态，不做二次判断。
 */
public class AgentModelException extends RuntimeException {

    private final String code;

    public AgentModelException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

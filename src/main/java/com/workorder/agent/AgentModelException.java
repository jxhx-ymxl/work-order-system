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
    private final int attempts;

    public AgentModelException(String code, String message) {
        this(code, message, 1);
    }

    /**
     * @param attempts 这次失败**总共用了多少次物理调用**（含重试）。默认 1 = 没重试。
     *                 重试必须让调用方看得见——否则"物理调用与耗时全部计数"就成了空话。
     */
    public AgentModelException(String code, String message, int attempts) {
        super(message);
        this.code = code;
        this.attempts = attempts;
    }

    public String code() {
        return code;
    }

    public int attempts() {
        return attempts;
    }
}

package com.workorder.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;

/**
 * 模型边界。
 *
 * <p>S1 只有 HTTP 实现（{@link HttpAgentModel}），用本地桩验证；真供应商兼容性靠同一实现与真 key 复测。
 *
 * <p>读取超时是**每次调用传入**的：调用方掌握剩余运行预算，单轮的上限必须被剩余预算收敛，
 * 否则 {@code 轮次上限 × 单轮超时} 会把整条超时链拉长（复核发现 4）。
 */
public interface AgentModel {

    ModelTurn respond(List<JsonNode> transcript, Duration readTimeout) throws AgentModelException;

    /**
     * 带**取消信号**的一轮调用（§11-3）。
     *
     * <p>默认实现直接转给两参版本——**既有实现与桩不需要改**；真正关心取消的实现（{@link HttpAgentModel}）
     * 会在**读取循环里**检查它，并在取消时主动断开连接。
     */
    default ModelTurn respond(List<JsonNode> transcript, Duration readTimeout, Cancellation cancellation)
            throws AgentModelException {
        return respond(transcript, readTimeout);
    }
}

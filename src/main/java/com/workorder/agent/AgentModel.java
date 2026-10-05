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
}

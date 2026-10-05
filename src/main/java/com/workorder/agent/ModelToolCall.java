package com.workorder.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 模型请求的一次工具调用。
 *
 * @param callId    调用 ID——工具结果必须按它回传，模型靠它配对（§10 的"调用 ID 关联"）
 * @param name      工具名
 * @param arguments 已解析成 JSON 对象的参数
 */
public record ModelToolCall(String callId, String name, JsonNode arguments) {
}

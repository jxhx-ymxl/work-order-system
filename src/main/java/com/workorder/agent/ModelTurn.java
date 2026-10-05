package com.workorder.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 模型一轮的产出。
 *
 * @param content    助手文本（本协议下不承载结论，只用于排障）
 * @param toolCalls  本轮请求的工具调用
 * @param rawMessage 原始 assistant 消息——**原样回填进 transcript**，避免自己重新拼装导致协议漂移
 * @param rawBytes   响应体字节数
 */
public record ModelTurn(String content, List<ModelToolCall> toolCalls, JsonNode rawMessage, int rawBytes) {

    public ModelTurn {
        toolCalls = List.copyOf(toolCalls);
    }
}

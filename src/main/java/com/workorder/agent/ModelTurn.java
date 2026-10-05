package com.workorder.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 模型一轮的产出。
 *
 * @param content     助手文本（本协议下不承载结论，只用于排障）
 * @param toolCalls   本轮请求的工具调用
 * @param echoMessage 回填进 transcript 的 assistant 消息——**已按 D78 白名单清洗**（只有
 *                    {@code role} / {@code content} / {@code tool_calls}），不是供应商原文。
 *                    名字刻意不叫 {@code rawMessage}：清洗之后它已经不是"原文"了
 * @param rawBytes    响应体字节数
 */
public record ModelTurn(String content, List<ModelToolCall> toolCalls, JsonNode echoMessage, int rawBytes) {

    public ModelTurn {
        toolCalls = List.copyOf(toolCalls);
    }
}

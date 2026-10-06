package com.workorder.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 模型一轮的产出。
 *
 * @param content     助手文本（本协议下不承载结论，只用于排障）
 * @param toolCalls   本轮请求的工具调用
 * @param echoMessage 回填进 transcript 的 assistant 消息——**保真回填**（2026-10-06 真供应商实测后定稿）：
 *                    就是供应商返回的 message 本身，只去掉 {@code ECHO_DENYLIST} 里**实测**会引发 400 的字段
 *                    （当前为空集）。字段名保留 {@code echoMessage}（"回填用的消息"），不再表示"清洗过的消息"——
 *                    §11-1 的白名单方向已被 D78 的追加引用块推翻
 * @param rawBytes    响应体字节数
 */
public record ModelTurn(String content, List<ModelToolCall> toolCalls, JsonNode echoMessage, int rawBytes) {

    public ModelTurn {
        toolCalls = List.copyOf(toolCalls);
    }
}

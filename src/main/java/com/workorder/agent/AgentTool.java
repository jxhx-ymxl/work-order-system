package com.workorder.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 一个调查工具。
 *
 * <p>实现类只关心"参数对不对 / 查出什么事实"，不关心预算、编号、协议——那些在调查循环里统一做。
 */
public interface AgentTool {

    String name();

    String description();

    /** JSON Schema 形式的参数定义，直接进模型请求的 {@code tools}。 */
    Map<String, Object> parameterSchema();

    ToolOutcome execute(JsonNode arguments);
}

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

    /**
     * 执行一次查询。
     *
     * <p>{@code ctx} 是**唯一的身份来源**（§11-2 / D79）：实现**不得**读 Sa-Token、不得依赖
     * {@code RequestContextHolder}、不得读任何"当前请求"的隐式状态。
     */
    ToolOutcome execute(ToolContext ctx, JsonNode arguments);
}

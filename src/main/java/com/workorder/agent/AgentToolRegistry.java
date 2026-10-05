package com.workorder.agent;

import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具表：名字 → 工具，外加"未知工具"的统一处置。
 *
 * <p><b>为什么它不是一个插件平台</b>（CLAUDE.md §1 第 3 条"每引入一样东西都要能自证"）：
 * 没有 SPI、没有动态加载、没有配置驱动的注册——就是构造时传进来的几个实例。
 * 引入它的唯一理由是"未知工具必须有一条统一的错误回传路径"，那是协议的一部分。
 */
public final class AgentToolRegistry {

    private final Map<String, AgentTool> tools;

    public AgentToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> map = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            if (map.put(tool.name(), tool) != null) {
                throw new IllegalArgumentException("工具名重复：" + tool.name());
            }
        }
        this.tools = Collections.unmodifiableMap(map);
    }

    /** 传给模型的工具定义（顺序稳定，便于对照）。 */
    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (AgentTool tool : tools.values()) {
            definitions.add(Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", tool.name(),
                            "description", tool.description(),
                            "parameters", tool.parameterSchema())));
        }
        return definitions;
    }

    public java.util.Set<String> names() {
        return tools.keySet();
    }

    /** 执行一次调用；工具不存在时返回错误结果，不抛异常。 */
    public ToolOutcome execute(ModelToolCall call) {
        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            return ToolOutcome.error("UNKNOWN_TOOL",
                    "工具不存在：" + call.name() + "。可用工具：" + String.join("、", tools.keySet()));
        }
        try {
            return tool.execute(call.arguments() == null ? MissingNode.getInstance() : call.arguments());
        } catch (RuntimeException e) {
            // 工具是外部边界（S2 起要连 MySQL）：它抛异常既不能穿透成"没有终态"，
            // 也不能被当成"查到了空结果"（那会变成假结论）。转成可回传的错误结果，预算继续兜底。
            return ToolOutcome.error("TOOL_FAILED",
                    "工具 " + call.name() + " 执行异常：" + e.getClass().getSimpleName() + "："
                            + SensitiveDataRedactor.redactText(String.valueOf(e.getMessage())));
        }
    }
}

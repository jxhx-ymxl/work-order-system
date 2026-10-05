package com.workorder.agent;

import java.util.List;

/**
 * 最终报告：**只有问题类型 + 证据编号 + 建议编号**，没有模型自由文本（§3.2）。
 *
 * <p>为什么不留自由文本：只要允许模型自述结论，"哪些话有证据支撑"就无法机械校验，
 * 证据编号会退化成装饰。报告正文由后端按证据渲染。
 */
public record AgentReport(AgentProblemType problemType, List<String> evidenceIds, List<String> suggestionIds) {

    public AgentReport {
        evidenceIds = List.copyOf(evidenceIds);
        suggestionIds = List.copyOf(suggestionIds);
    }
}

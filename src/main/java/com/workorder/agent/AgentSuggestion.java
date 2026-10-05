package com.workorder.agent;

/**
 * 建议编号目录（`docs/AGENT-PLAN.md` §3.2：报告只提交建议编号，正文由后端渲染）。
 *
 * <p>S1 是虚构目录，与三类虚构问题类型配套；S2 接模板报告时替换成真实目录。
 */
public enum AgentSuggestion {

    CONTACT_ASSIGNEE("联系当前处理人确认进度"),
    ESCALATE_TO_DEPT_ADMIN("上报部门主管催办"),
    WAIT_FOR_CLAIM("等待处理人接单，必要时由主管指派");

    private final String text;

    AgentSuggestion(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    public static boolean isKnown(String name) {
        if (name == null) {
            return false;
        }
        for (AgentSuggestion suggestion : values()) {
            if (suggestion.name().equals(name.trim())) {
                return true;
            }
        }
        return false;
    }
}

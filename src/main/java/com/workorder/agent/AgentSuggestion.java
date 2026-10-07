package com.workorder.agent;

/**
 * 建议编号目录（`docs/AGENT-PLAN.md` §3.2：报告只提交建议编号，正文由后端渲染）。
 *
 * <p>S1 是虚构目录，与三类虚构问题类型配套；S2 接模板报告时替换成真实目录。
 */
public enum AgentSuggestion {

    CONTACT_ASSIGNEE("联系当前处理人确认进度"),
    ESCALATE_TO_DEPT_ADMIN("上报部门主管催办"),
    WAIT_FOR_CLAIM("等待处理人接单，必要时由主管指派"),

    /**
     * 下一步在**提交人**侧：等待（必要时提醒）提交人完成验收。
     *
     * <p>**规则来源是状态机，不是任何一条评测期望**：工单处于 {@code AWAIT_APPROVAL}（待验收）时，
     * 「验收通过 / 驳回」**只允许提交人**做（状态转移表 `frontend/CLAUDE.md` §3.2；代码侧
     * {@code StateMachineValidator} 把 `AWAIT_APPROVAL` 的下一步限定为 `APPROVE`/`REJECT`，
     * 服务层再按 `submitterId` 校验身份）——所以此时"下一步找谁"的答案是**提交人**，
     * 而 `CONTACT_ASSIGNEE`（找处理人）恰好是错的：处理人已经交完了。
     *
     * <p>与 §11-4 的裁决一致：这里只**扩目录**，不实现"必须建议 X"的强制项；把这条建议用在
     * 没有依据的状态上，由 {@link AgentReportValidator} 的**禁止项**拦下（失败码复用 `REPORT_INVALID`）。
     */
    WAIT_FOR_SUBMITTER_ACCEPTANCE("等待提交人验收，必要时提醒其处理");

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

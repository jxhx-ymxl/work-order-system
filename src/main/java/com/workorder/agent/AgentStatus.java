package com.workorder.agent;

/**
 * 调查运行的对外状态（`docs/AGENT-PLAN.md` §3.3 定稿）。
 *
 * <p><b>执行状态是三值</b>（`docs/agent-design/AGENT-DESIGN.md` L182：COMPLETED / INCOMPLETE / FAILED）：
 * <ul>
 *   <li>{@link #COMPLETED}：契约正常完成，产出报告；</li>
 *   <li>{@link #INCOMPLETE}：**有经复核的部分事实、没有完整报告、必须显式标注未完成**——
 *       由超时 / 预算 / 工具失败 / 状态变化等阻止完成的情形进入（`AGENT-LEARNING-EVAL.md` L278：
 *       "只能给经复核的部分事实并标 INCOMPLETE，不能洗成正常结果"）；</li>
 *   <li>{@link #FAILED}：无可用报告，或不可恢复的协议 / 配置问题。</li>
 * </ul>
 * {@link #TIMED_OUT} / {@link #CANCELLED} 是运行层的中止态，与上面三者同属终态。
 */
public enum AgentStatus {

    RUNNING,
    COMPLETED,
    INCOMPLETE,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    /** 是否终态。**没有任何路径允许停在 RUNNING**（§3.3"永久调查中"的反面）。 */
    public boolean isTerminal() {
        return this != RUNNING;
    }
}

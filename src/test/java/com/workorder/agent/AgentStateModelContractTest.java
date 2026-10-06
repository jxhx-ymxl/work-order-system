package com.workorder.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态模型契约（D86）：`INCOMPLETE` 终态 + §3.3 与 {@link AgentStatus} 对齐。
 *
 * <p>依据 `docs/agent-design/AGENT-DESIGN.md` L182（executionStatus 三值
 * COMPLETED / INCOMPLETE / FAILED）与 `AGENT-LEARNING-EVAL.md` L278
 * （"只能给经复核的部分事实并标 INCOMPLETE，不能洗成正常结果"）。
 */
@DisplayName("状态模型契约：INCOMPLETE + §3.3 对齐")
class AgentStateModelContractTest {

    private static final List<AgentEvidence> PARTIAL = List.of(
            new AgentEvidence("E1", "order.exists", "true", false, false),
            new AgentEvidence("E2", "order.status", "IN_PROGRESS", false, false));

    @Test
    @DisplayName("INCOMPLETE 可构造：report 必须为 null、必须带原因码（构造期不变量，不放宽）")
    void incompleteRequiresNullReportAndReasonCode() {
        AgentRunResult result = AgentRunResult.incomplete("STATE_CHANGED", "运行中业务状态变化",
                PARTIAL, 1, 1, 0);

        assertEquals(AgentStatus.INCOMPLETE, result.status());
        assertTrue(result.status().isTerminal(), "INCOMPLETE 是终态（isTerminal 自然覆盖）");
        assertFalse(result.hasReport(), "INCOMPLETE 不产出报告");
        assertNull(result.report());
        assertEquals("STATE_CHANGED", result.failure().code());
        assertEquals(PARTIAL, result.evidence(), "已核实的部分事实走 evidence，不新增'部分报告'形态");

        assertThrows(IllegalArgumentException.class,
                () -> new AgentRunResult(AgentStatus.INCOMPLETE, null, null, PARTIAL, 1, 1, 0),
                "缺原因码必须构造期抛");
        assertThrows(IllegalArgumentException.class,
                () -> new AgentRunResult(AgentStatus.INCOMPLETE, AgentFailure.of("STATE_CHANGED", "x"),
                        new AgentReport(AgentProblemType.ORDER_STATUS, List.of("E1"), List.of()),
                        PARTIAL, 1, 1, 0),
                "INCOMPLETE 不得带报告（失败不得被伪装成正常报告）");
    }

    @Test
    @DisplayName("渲染 INCOMPLETE：顶部标未完成 + 原因码，列已核实事实与缺口，但不出现正常报告的段落结构")
    void incompleteRendersAsNotCompletedNotAsNormalReport() {
        String out = new AgentReportRenderer().renderIncomplete(
                AgentFailure.of("STATE_CHANGED", "运行中业务状态变化"), PARTIAL);

        assertTrue(out.contains("调查未完成"), "必须显式标注未完成：\n" + out);
        assertTrue(out.contains("STATE_CHANGED"), "必须带原因码：\n" + out);
        assertTrue(out.contains("【已核实事实】"), out);
        assertTrue(out.contains("- order.status：IN_PROGRESS"), out);
        assertTrue(out.contains("【证据缺口】"), out);
        assertFalse(out.contains("【下一步核实建议】"), "不得渲染成一份正常报告：\n" + out);
        assertFalse(out.contains("【结论】"), "未完成不是结论：\n" + out);
    }

    @Test
    @DisplayName("§3.3 表与状态模型对齐：INCOMPLETE / FORBIDDEN / STATE_CHANGED 各有且仅有一行；旧名条目带标注")
    void section33TableMatchesStateModel() throws Exception {
        String doc = Files.readString(Path.of("docs/AGENT-PLAN.md"), StandardCharsets.UTF_8);

        for (String token : List.of("INCOMPLETE", "FORBIDDEN", "STATE_CHANGED")) {
            assertEquals(1, countRow(doc, token), "§3.3 里 `" + token + "` 应有且仅有一行");
        }

        int staleRow = doc.indexOf("| `STALE_EVIDENCE` |");
        assertTrue(staleRow >= 0, "STALE_EVIDENCE 保留一行旧名条目");
        String staleLine = doc.substring(staleRow, doc.indexOf('\n', staleRow));
        assertTrue(staleLine.contains("旧名"), "旧名条目必须标注：\n" + staleLine);
    }

    /** 数表格行（`| `TOKEN` |` 形态）——比子串计数更准，不会把正文里的引用算进去。 */
    private static int countRow(String doc, String token) {
        return doc.split("\\| `" + token + "` \\|", -1).length - 1;
    }
}

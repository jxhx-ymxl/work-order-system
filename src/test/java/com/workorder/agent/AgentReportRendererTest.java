package com.workorder.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板报告渲染（§3.2："报告只提交 problemType + 证据编号 + 建议编号，**正文由后端按证据渲染**"）。
 *
 * <p>硬约束：确定性（同输入同输出）、无模型自由文本、三段固定顺序、
 * "已知的空"与"未核实"**分开呈现**（D83 落到呈现层）、`exists=false` 渲染成"工单不存在"（D82）、
 * `TIMEOUT_SITUATION` 不出现原因性措辞（§1.1 C1）。
 */
@DisplayName("模板报告渲染")
class AgentReportRendererTest {

    private final AgentReportRenderer renderer = new AgentReportRenderer();

    private static AgentEvidence evidence(String id, String fact, String value, boolean unknown, boolean empty) {
        return new AgentEvidence(id, fact, value, unknown, empty);
    }

    private static final List<AgentEvidence> FULL_EVIDENCE = List.of(
            evidence("E1", "order.exists", "true", false, false),
            evidence("E2", "order.status", "IN_PROGRESS", false, false),
            evidence("E3", "order.assignee", "未分配", false, true),          // 已知的空（D83）
            evidence("E4", "order.sla_deadline", "无 SLA 截止（NULL 未登记）", false, true),
            evidence("E5", "order.accept_events", "（无：从未接单或指派）", false, true),
            evidence("E6", "order.alert_count", "未知（无告警计数记录源）", true, false));  // 真正的未知

    private static AgentReport report(String type, List<String> evidenceIds, List<String> suggestionIds) {
        return new AgentReport(AgentProblemType.valueOf(type), evidenceIds, suggestionIds);
    }

    @Test
    @DisplayName("完整报告：三段齐全且顺序固定（已核实事实 → 证据缺口 → 下一步核实建议）")
    void rendersThreeSectionsInFixedOrder() {
        String out = renderer.render(report("ORDER_STATUS", List.of("E1", "E2", "E3", "E4"),
                List.of("CONTACT_ASSIGNEE", "ESCALATE_TO_DEPT_ADMIN")), FULL_EVIDENCE);

        int facts = out.indexOf("【已核实事实】");
        int gaps = out.indexOf("【证据缺口】");
        int next = out.indexOf("【下一步核实建议】");
        assertTrue(facts >= 0 && gaps > facts && next > gaps, "三段顺序固定：\n" + out);
        assertTrue(out.contains("- order.status：IN_PROGRESS"), out);
        assertTrue(out.contains("联系当前处理人确认进度"), "建议只渲染目录里的固定文案：" + out);
        assertTrue(out.contains("上报部门主管催办"), out);
    }

    @Test
    @DisplayName("未分配的 assignee：呈现为已知的「未分配」，不是「未核实」")
    void knownEmptyIsNotPresentedAsUnverified() {
        String out = renderer.render(report("ORDER_STATUS", List.of("E1", "E2", "E3"), List.of()), FULL_EVIDENCE);

        assertTrue(out.contains("- order.assignee：未分配"), "仍要作为已知事实呈现：" + out);
        assertTrue(out.contains("- 已知为空：order.assignee"), "空要单独归到「已知为空」：" + out);
        assertFalse(unverifiedSectionOf(out).contains("order.assignee"),
                "「未核实」段不得出现未分配（D83：空值不是未知）：\n" + out);
    }

    @Test
    @DisplayName("真正的未知（alert_count / 有 id 查不到用户）：呈现为「未核实」并带上原因")
    void realUnknownIsPresentedAsUnverifiedWithReason() {
        List<AgentEvidence> evidence = List.of(
                evidence("E1", "order.exists", "true", false, false),
                evidence("E2", "order.assignee", "已分配（显示名查不到：t_user 无该行）", true, false),
                evidence("E3", "order.alert_count", "未知（无告警计数记录源）", true, false));

        String out = renderer.render(report("TIMEOUT_SITUATION", List.of("E1", "E2", "E3"), List.of()), evidence);

        String unverified = unverifiedSectionOf(out);
        assertTrue(unverified.contains("order.assignee"), out);
        assertTrue(unverified.contains("查不到"), "要带原因：" + out);
        assertTrue(unverified.contains("order.alert_count"), out);
        assertTrue(out.contains("- 已知为空：（无）"), "本用例没有空值，不能硬凑：" + out);
    }

    @Test
    @DisplayName("order.exists=false：渲染成「工单不存在」，不是空壳（D82 条件必需事实）")
    void missingOrderRendersAsExplicitConclusion() {
        List<AgentEvidence> evidence = List.of(evidence("E1", "order.exists", "false", false, false));

        String out = renderer.render(report("ORDER_STATUS", List.of("E1"), List.of()), evidence);

        assertTrue(out.contains("工单不存在"), "必须给出明确结论：" + out);
        assertTrue(out.contains("order.exists：false"), out);
        assertTrue(out.contains("【已核实事实】"), "结论之外结构仍完整：" + out);
    }

    @Test
    @DisplayName("确定性：同一输入渲染两次，输出逐字节相同")
    void renderingIsDeterministic() {
        AgentReport report = report("REASSIGN_HISTORY", List.of("E1", "E2", "E3", "E5", "E6"),
                List.of("WAIT_FOR_CLAIM", "ESCALATE_TO_DEPT_ADMIN"));
        String first = renderer.render(report, FULL_EVIDENCE);
        String second = renderer.render(report, FULL_EVIDENCE);

        assertEquals(first, second, "同输入必须同输出（无时间戳、无随机、无模型文本）");
    }

    @Test
    @DisplayName("TIMEOUT_SITUATION：渲染只陈述事实与缺口，不出现任何原因性措辞（§1.1 C1）")
    void timeoutSituationHasNoCausalWording() {
        String out = renderer.render(report("TIMEOUT_SITUATION", List.of("E5", "E6"),
                List.of("ESCALATE_TO_DEPT_ADMIN")), FULL_EVIDENCE);

        for (String causal : List.of("因为", "原因是", "由于", "导致", "之所以")) {
            assertFalse(out.contains(causal), "不得出现原因性措辞「" + causal + "」：\n" + out);
        }
    }

    /** 取「未核实」那一段（到「已知为空」为止）——用来断言"空"没有被混进"未核实"。 */
    private static String unverifiedSectionOf(String out) {
        int start = out.indexOf("- 未核实：");
        if (start < 0) {
            return "";
        }
        int end = out.indexOf("- 已知为空：", start);
        return end < 0 ? out.substring(start) : out.substring(start, end);
    }

}

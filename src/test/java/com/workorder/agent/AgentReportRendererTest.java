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
        // 2026-10-08 起正文用**显示名**（工单状态：处理中）——这是文案改动，不是判据改动：
        // 完成判据/评测看的是 fact 键与编号，不看 renderedText（holdout 与 baseline harness 均不读它）。
        assertTrue(out.contains("- 工单状态：处理中"), out);
        assertTrue(out.contains("联系当前处理人确认进度"), "建议只渲染目录里的固定文案：" + out);
        assertTrue(out.contains("上报部门主管催办"), out);
    }

    /**
     * 本轮新增的目录项（下一步在提交人侧）也要能被渲染出来——**渲染器只管渲染，不管前提**：
     * "这条建议在该状态下合不合法"由 {@link AgentReportValidator} 的禁止项判（见 `AgentMinimalLoopTest`），
     * 这里钉的是"文案逐字来自目录、不是模型写的"。
     */
    @Test
    @DisplayName("建议目录新增项（等待提交人验收）：渲染成目录里的固定文案")
    void rendersSubmitterSideSuggestion() {
        String out = renderer.render(report("ORDER_STATUS", List.of("E1", "E2", "E3"),
                List.of("WAIT_FOR_SUBMITTER_ACCEPTANCE")), FULL_EVIDENCE);

        assertTrue(out.contains("【下一步核实建议】"), out);
        assertTrue(out.contains("- 等待提交人验收，必要时提醒其处理"),
                "建议段只渲染目录里的固定文案（无模型自由文本）：" + out);
    }

    @Test
    @DisplayName("未分配的 assignee：呈现为已知的「未分配」，不是「未核实」")
    void knownEmptyIsNotPresentedAsUnverified() {
        String out = renderer.render(report("ORDER_STATUS", List.of("E1", "E2", "E3"), List.of()), FULL_EVIDENCE);

        // D83 落到呈现层：空是**已知事实**，所以要留在第一段（事实行后缀标注），不能塞进"证据缺口"
        assertTrue(out.contains("- 处理人：未分配（已知为空）"), "空值仍是已知事实，用后缀标注：" + out);
        assertFalse(unverifiedSectionOf(out).contains("处理人"),
                "「未核实」段不得出现未分配（D83：空值不是未知）：\n" + out);
        assertFalse(gapSectionOf(out).contains("已知为空"),
                "第二段只放\"未核实\"——\"已知为空\"属于事实段（与 §1 第四题三段一一对应）：\n" + out);
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
        assertTrue(unverified.contains("处理人"), out);
        assertTrue(unverified.contains("查不到"), "要带原因：" + out);
        // **表外键原样显示**：order.alert_count 不在事实标签表里 → 不吞掉、不变空白（将来加事实时的安全网）。
        assertTrue(unverified.contains("order.alert_count"), out);
        assertFalse(gapSectionOf(out).contains("已知为空"),
                "第二段只放\"未核实\"，不得出现\"已知为空\"：" + out);
    }

    /**
     * 2026-10-08 新增：正文的**显示名**（事实键 + 状态值 + 布尔值 + 流转动作码）。
     *
     * <p>钉三件事：① 键与值都换中文；② 状态文案与前端 `STATUS_MAP` **逐字一致**（避免同一状态两个名字）；
     * ③ **表外键原样显示**——同一条报告里混一个未登记的事实键，它必须原样出现（不吞、不变空白）。
     */
    @Test
    @DisplayName("显示名：事实键与值换中文（状态/布尔/动作码），表外键原样显示")
    void rendersDisplayNamesAndKeepsUnknownFactKeysRaw() {
        List<AgentEvidence> evidence = List.of(
                evidence("E1", "order.exists", "true", false, false),
                evidence("E2", "order.status", "AWAIT_APPROVAL", false, false),
                evidence("E3", "order.accept_events",
                        "ACCEPT@2026-10-07 22:44 by h*；RELEASE@2026-10-08 09:00 by 系统操作", false, false),
                evidence("E4", "order.alert_count", "2", false, false));

        String out = renderer.render(report("ORDER_STATUS", List.of("E1", "E2", "E3", "E4"), List.of()), evidence);

        assertTrue(out.contains("- 工单是否存在：是"), out);
        assertTrue(out.contains("- 工单状态：待验收"), "状态值必须与前端 STATUS_MAP 同文案：" + out);
        assertTrue(out.contains("- 接单与流转记录：接单@2026-10-07 22:44 by h*；超时释放@2026-10-08 09:00 by 系统操作"),
                "动作码整词翻译，时间/脱敏名/系统操作原样保留：" + out);
        assertTrue(out.contains("- order.alert_count：2"),
                "**表外键原样显示**（不吞掉、不变空白）：" + out);
        assertFalse(out.contains("order.status"), "登记过的键不得再出现在正文里：" + out);
    }

    @Test
    @DisplayName("order.exists=false：渲染成「工单不存在」，不是空壳（D82 条件必需事实）")
    void missingOrderRendersAsExplicitConclusion() {
        List<AgentEvidence> evidence = List.of(evidence("E1", "order.exists", "false", false, false));

        String out = renderer.render(report("ORDER_STATUS", List.of("E1"), List.of()), evidence);

        assertTrue(out.contains("工单不存在"), "必须给出明确结论：" + out);
        assertTrue(out.contains("工单是否存在：否"), out);
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

    /** 取「未核实」那一段（到「下一步核实建议」为止）——用来断言"空"没有被混进"未核实"。 */
    private static String unverifiedSectionOf(String out) {
        int start = out.indexOf("- 未核实：");
        if (start < 0) {
            return "";
        }
        int end = out.indexOf("\n【下一步核实建议】", start);
        return end < 0 ? out.substring(start) : out.substring(start, end);
    }

    /** 取整个「证据缺口」段。 */
    private static String gapSectionOf(String out) {
        int start = out.indexOf("【证据缺口】");
        int end = out.indexOf("【下一步核实建议】", start);
        return start < 0 || end < 0 ? "" : out.substring(start, end);
    }

}

package com.workorder.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板报告渲染（`docs/AGENT-PLAN.md` §3.2："报告只提交 problemType + 证据编号 + 建议编号，
 * **正文由后端按证据渲染**"）。
 *
 * <p><b>确定性</b>：输入只有 {@link AgentReport} + 本轮证据 + {@link AgentSuggestion} 目录，
 * 没有任何模型自由文本、时间戳或随机源——同一输入必得逐字节相同的输出（用例钉住）。
 *
 * <p><b>结构固定为三段</b>（对应 §1 第四题"事实 / 缺口 / 下一步核实建议"）：
 * <ol>
 *   <li>【已核实事实】逐条 fact + 值；</li>
 *   <li>【证据缺口】**未核实**（`unknown`：查不到 / 无来源，附原因）与**已知为空**（`empty`：
 *       "未分配"/"无 SLA"/"从未接单"这类**已知的空**）**分开呈现**——D83 的口径落在呈现层；</li>
 *   <li>【下一步核实建议】只渲染 {@link AgentSuggestion#text()} 的固定文案，不生成新句子。</li>
 * </ol>
 *
 * <p>`order.exists=false` 时在最前面给一行【结论】"工单不存在"（D82 的条件必需事实），报告的其余结构照常渲染。
 * 本类**只陈述事实与缺口**，不写任何原因性措辞（§1.1 C1）——措辞检查见渲染器用例。
 */
public final class AgentReportRenderer {

    public String render(AgentReport report, List<AgentEvidence> evidence) {
        Map<String, AgentEvidence> cited = new LinkedHashMap<>();
        for (String id : report.evidenceIds()) {
            for (AgentEvidence candidate : evidence) {
                if (candidate.id().equals(id)) {
                    cited.put(id, candidate);
                    break;
                }
            }
        }

        StringBuilder out = new StringBuilder();
        if (isMissingOrder(cited)) {
            out.append("【结论】工单不存在（order.exists=false）\n\n");
        }

        out.append("【已核实事实】\n");
        if (cited.isEmpty()) {
            out.append("- （无）\n");
        } else {
            cited.values().forEach(item ->
                    out.append("- ").append(item.fact()).append("：").append(item.value()).append('\n'));
        }

        out.append("\n【证据缺口】\n");
        appendGaps(out, "未核实", cited.values().stream().filter(AgentEvidence::unknown).toList(),
                "（查不到 / 无来源，附原因）");
        appendGaps(out, "已知为空", cited.values().stream().filter(AgentEvidence::empty).toList(),
                "（已知的空，不是未知）");

        out.append("\n【下一步核实建议】\n");
        if (report.suggestionIds().isEmpty()) {
            out.append("- （无）\n");
        } else {
            report.suggestionIds().forEach(id ->
                    out.append("- ").append(AgentSuggestion.valueOf(id).text()).append('\n'));
        }
        return out.toString();
    }

    private static void appendGaps(StringBuilder out, String label, List<AgentEvidence> items, String note) {
        if (items.isEmpty()) {
            out.append("- ").append(label).append("：（无）\n");
            return;
        }
        for (AgentEvidence item : items) {
            out.append("- ").append(label).append("：").append(item.fact())
                    .append("（").append(item.value()).append("）").append(note).append('\n');
        }
    }

    /** `order.exists=false` 判定：按证据的 fact + value（与 `validateReport` 的条件必需事实同一判据）。 */
    private static boolean isMissingOrder(Map<String, AgentEvidence> cited) {
        return cited.values().stream()
                .anyMatch(item -> "order.exists".equals(item.fact()) && "false".equals(item.value()));
    }
}

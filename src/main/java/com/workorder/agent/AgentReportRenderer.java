package com.workorder.agent;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * 模板报告渲染（`docs/AGENT-PLAN.md` §3.2："报告只提交 problemType + 证据编号 + 建议编号，
 * **正文由后端按证据渲染**"）。
 *
 * <p><b>确定性</b>：输入只有 {@link AgentReport} + 本轮证据 + {@link AgentSuggestion} 目录，
 * 没有任何模型自由文本、时间戳或随机源——同一输入必得逐字节相同的输出（用例钉住）。
 *
 * <p><b>结构固定为三段</b>（对应 §1 第四题"事实 / 缺口 / 下一步核实建议"）：
 * <ol>
 *   <li>【已核实事实】逐条 fact + 值；**"已知为空"（{@code empty}）作为后缀标注留在这一段**——
 *       按 D83 它是**已知事实**、不是缺口（"未分配"/"无 SLA"/"从未接单"）；</li>
 *   <li>【证据缺口】只放**未核实**（{@code unknown}：查不到 / 无来源，附原因）；</li>
 *   <li>【下一步核实建议】只渲染 {@link AgentSuggestion#text()} 的固定文案，不生成新句子。</li>
 * </ol>
 *
 * <p>`order.exists=false` 时在最前面给一行【结论】"工单不存在"（D82 的条件必需事实），报告的其余结构照常渲染。
 * 本类**只陈述事实与缺口**，不写任何原因性措辞（§1.1 C1）——措辞检查见渲染器用例。
 *
 * <p><b>未完成（INCOMPLETE）是另一条出口</b>（{@link #renderIncomplete}，D86）：顶部先标"调查未完成 + 原因码"，
 * 再照常列已核实事实与证据缺口；**不渲染【结论】与【下一步核实建议】**——那两段属于"正常报告"，
 * 未完成不得被洗成正常结果（`AGENT-LEARNING-EVAL.md` L278）。
 *
 * <p><b>显示名（2026-10-08 起）</b>：事实键与部分值在**渲染时**换成中文（读者看到的是"工单状态：处理中"，
 * 不是"order.status：IN_PROGRESS"）。三条边界：
 * ① 三段标题【已核实事实】/【证据缺口】/【下一步核实建议】**一个字都不改**（`AgentRealProviderE2EHarness` 断言它们）；
 * ② 映射是**集中常量**，**表外的键原样显示**（不吞、不变空白）——将来加事实不会渲染成空白；
 * ③ 只改**显示名**：`AgentEvidence.fact()` / 报告里的编号 / 校验判据仍用**原键**（协议不动），
 * 所以这层替换对"完成判据 / 评测"不可见（holdout 与 baseline harness 都不看 `renderedText`）。
 */
public final class AgentReportRenderer {

    /**
     * 事实键 → 中文标签。**表外键原样显示**（`getOrDefault`），绝不吞掉。
     *
     * <p>⚠ **`dept.*` 刻意没有加进来**（2026-10-08 登记）：`AgentTimeoutCalibrationHarness` 用
     * `text.contains("dept.")` / `text.contains("order.logs_page")` 当**探针**判断"这次跑有没有用到对照工具"——
     * 给 `dept.*` 加中文名会让那个探针**静默失灵**（它找的是字面量）。要补就得同一笔把探针改成按事实键判断，
     * 本轮不做，只登记（见 D108）。
     */
    private static final Map<String, String> FACT_LABELS = Map.of(
            "order.exists", "工单是否存在",
            "order.status", "工单状态",
            "order.assignee", "处理人",
            "order.sla_deadline", "SLA 截止时间",
            "order.accept_events", "接单与流转记录",
            // 2026-10-08 补：超时类会带出 SLA 上下文，"时间跨度"问法的答案主要落在这三条上。
            "sla.stored_deadline", "存储的 SLA 截止",
            "sla.observed_at", "当前观测时间",
            "sla.overdue", "是否已过期",
            "order.alert_count", "告警条数");

    /**
     * 工单状态 → 中文。**与 `frontend/src/types/order.ts` 的 `STATUS_MAP` 逐字一致**——
     * 两处口径打架时，用户会在页面与报告里读到同一个状态的两个名字。
     */
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "PENDING", "待分配",
            "ACCEPTED", "已接单",
            "IN_PROGRESS", "处理中",
            "AWAIT_APPROVAL", "待验收",
            "CLOSED", "已关闭",
            "RELEASED", "已释放",
            "ESCALATED_ADMIN", "已升级");

    /** 布尔值 → 是 / 否（`order.exists` 与 `sla.overdue` 共用一套）。 */
    private static final Map<String, String> BOOLEAN_LABELS = Map.of(
            "true", "是",
            "false", "否");

    /**
     * 流转动作码 → 中文（用于 `order.accept_events` 的值）。
     *
     * <p>前 9 条与 `frontend/src/types/order.ts` 的 `ACTION_MAP` 一致；后 2 条（`MANAGE` / `CLOSE`）
     * 是 `OrderAction` 里有、而前端 `ACTION_MAP` 里**暂时没有**的动作——这里先给中文，
     * **登记为已知差异**：日志时间线那侧遇到它们仍会显示原始码（要不要补前端属另一轮）。
     */
    private static final Map<String, String> ACTION_LABELS = Map.ofEntries(
            Map.entry("SUBMIT", "提交工单"),
            Map.entry("ACCEPT", "接单"),
            Map.entry("START", "开始处理"),
            Map.entry("COMPLETE", "提交验收"),
            Map.entry("APPROVE", "验收通过"),
            Map.entry("REJECT", "驳回"),
            Map.entry("ASSIGN", "分配工单"),
            Map.entry("RELEASE", "超时释放"),
            Map.entry("TRIAGE", "AI 分诊修正"),
            Map.entry("MANAGE", "管理员接管"),
            Map.entry("CLOSE", "管理员关闭"));

    /** 事实键的显示名：表外键原样返回（不吞掉，也不变成空白）。 */
    static String displayFact(String fact) {
        return FACT_LABELS.getOrDefault(fact, fact);
    }

    /**
     * 事实值的显示名：**只翻有映射表的那三类**（`order.status` / `order.exists` / `order.accept_events`），
     * 其余原样返回——不猜、不改写工具已经渲染好的文案（D83：渲染层不靠嗅字符串下判断）。
     */
    static String displayValue(String fact, String value) {
        if (value == null) {
            return null;
        }
        if ("order.status".equals(fact)) {
            return STATUS_LABELS.getOrDefault(value, value);
        }
        if ("order.exists".equals(fact) || "sla.overdue".equals(fact)) {
            return BOOLEAN_LABELS.getOrDefault(value, value);
        }
        if ("order.accept_events".equals(fact)) {
            return translateActionCodes(value);
        }
        return value;
    }

    /**
     * 把 `order.accept_events` 值里的**动作码整词**换成中文（`ACCEPT@时间 by 人` → `接单@时间 by 人`）。
     *
     * <p>只替换**独立的**动作码（`\b` 词边界），所以时间、脱敏显示名、"从未接单或指派（无 … 记录）"这类
     * 工具原文都原样保留；替换之间互不产生新的可替换串，因此结果与遍历顺序无关（确定性）。
     */
    private static String translateActionCodes(String value) {
        String out = value;
        for (Map.Entry<String, String> entry : ACTION_LABELS.entrySet()) {
            out = out.replaceAll("\\b" + entry.getKey() + "\\b", Matcher.quoteReplacement(entry.getValue()));
        }
        return out;
    }

    public String render(AgentReport report, List<AgentEvidence> evidence) {
        List<AgentEvidence> cited = citedEvidence(report, evidence);

        StringBuilder out = new StringBuilder();
        if (isMissingOrder(cited)) {
            out.append("【结论】工单不存在（order.exists=false）\n\n");
        }

        appendFacts(out, cited);
        appendGaps(out, cited);

        out.append("\n【下一步核实建议】\n");
        if (report.suggestionIds().isEmpty()) {
            out.append("- （无）\n");
        } else {
            report.suggestionIds().forEach(id ->
                    out.append("- ").append(AgentSuggestion.valueOf(id).text()).append('\n'));
        }
        return out.toString();
    }

    /**
     * **未完成**的对外呈现（D86）：顶部明确"调查未完成 + 原因码"，然后照常列已核实事实与证据缺口。
     *
     * <p>与 {@link #render} 的区别就是"少了正常报告的结构"：**不渲染【结论】**（未完成不是结论）、
     * **不渲染【下一步核实建议】**（没有完整报告就没有建议）。已核实的部分事实仍照常列出、未知仍进"未核实"。
     */
    public String renderIncomplete(AgentFailure failure, List<AgentEvidence> evidence) {
        return renderTerminal("【调查未完成】", failure, evidence);
    }

    /**
     * **已取消**的对外呈现（§3.3 / L116）：`CANCELLED(PERMISSION_REVOKED)` 走这里。
     *
     * <p>与未完成**同一形态**（顶部标状态 + 原因码，再列已核实事实与缺口，不给【结论】与建议），
     * 差别只在抬头写"已取消"——因为"权限被撤销"与"证据过期"对用户是两件事，措辞不能混。
     */
    public String renderCancelled(AgentFailure failure, List<AgentEvidence> evidence) {
        return renderTerminal("【调查已取消】", failure, evidence);
    }

    private String renderTerminal(String header, AgentFailure failure, List<AgentEvidence> evidence) {
        StringBuilder out = new StringBuilder();
        out.append(header).append("原因码：").append(failure.code());
        if (failure.message() != null && !failure.message().isBlank()) {
            out.append(" — ").append(failure.message());
        }
        out.append("\n\n");

        appendFacts(out, evidence);
        appendGaps(out, evidence);
        return out.toString();
    }

    /** 按报告引用的编号取证据（按编号去重、保序）——`render` 与断言共用的取数口径。 */
    private static List<AgentEvidence> citedEvidence(AgentReport report, List<AgentEvidence> evidence) {
        Map<String, AgentEvidence> cited = new LinkedHashMap<>();
        for (String id : report.evidenceIds()) {
            for (AgentEvidence candidate : evidence) {
                if (candidate.id().equals(id)) {
                    cited.put(id, candidate);
                    break;
                }
            }
        }
        return new ArrayList<>(cited.values());
    }

    /** 第一段：已核实事实；"已知为空"（D83）作为事实行后缀留在这里。 */
    private static void appendFacts(StringBuilder out, List<AgentEvidence> cited) {
        out.append("【已核实事实】\n");
        if (cited.isEmpty()) {
            out.append("- （无）\n");
            return;
        }
        cited.forEach(item -> out.append("- ").append(displayFact(item.fact())).append("：")
                .append(displayValue(item.fact(), item.value()))
                .append(item.empty() ? "（已知为空）" : "")   // D83：空是已知事实，不归"缺口"
                .append('\n'));
    }

    /** 第二段：证据缺口，只放"未核实"（unknown）。 */
    private static void appendGaps(StringBuilder out, List<AgentEvidence> items) {
        out.append("\n【证据缺口】\n");
        appendUnverified(out, items.stream().filter(AgentEvidence::unknown).toList());
    }

    /**
     * 只渲染"未核实"一类：`- 未核实：<fact> — <值>`。
     *
     * <p>用破折号而不是给值再套一层括号——值本身常含括号（如"未知（无告警计数记录源）"），
     * 再套一层就是双层括号（旧的排版瑕疵）。
     */
    private static void appendUnverified(StringBuilder out, List<AgentEvidence> items) {
        if (items.isEmpty()) {
            out.append("- 未核实：（无）\n");
            return;
        }
        for (AgentEvidence item : items) {
            out.append("- 未核实：").append(displayFact(item.fact())).append(" — ")
                    .append(displayValue(item.fact(), item.value())).append('\n');
        }
    }

    /** `order.exists=false` 判定：按证据的 fact + value（与 `validateReport` 的条件必需事实同一判据）。 */
    private static boolean isMissingOrder(List<AgentEvidence> cited) {
        return cited.stream()
                .anyMatch(item -> "order.exists".equals(item.fact()) && "false".equals(item.value()));
    }
}

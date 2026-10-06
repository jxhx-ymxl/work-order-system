package com.workorder.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * **证据登记机制**（`docs/AGENT-PLAN.md` §3.2 的"事实编号"在此分配）。
 *
 * <p>为什么单独一个类：agent 循环与**强固定流程基线**（§3.1 / `AGENT-LEARNING-EVAL.md` §3.1）
 * 必须复用**同一套**登记机制——否则 S6 的成对比较比的就成了两套编号规则，基线也失去意义。
 *
 * <p>编号规则：按工具结果里事实的插入顺序发 `E1`、`E2`…；值先过 {@link SensitiveDataRedactor}（§4.4）；
 * `unknown` / `empty` 两个标记按工具声明原样带上（D83：空 ≠ 未知）。
 */
final class EvidenceLedger {

    private EvidenceLedger() {
    }

    /**
     * 把一次工具结果登记进 {@code byId}，返回"事实键 → 证据编号"。
     *
     * <p>保序是硬要求：工具结果用 `LinkedHashMap` 承载事实，`E1`… 的分配必须与它一致
     * （历史上 `Map.copyOf` 的哈希序曾让编号漂移）。
     */
    static Map<String, String> recordInto(Map<String, AgentEvidence> byId, ToolOutcome outcome) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : outcome.facts().entrySet()) {
            String fact = entry.getKey();
            String id = "E" + (byId.size() + 1);
            String value = SensitiveDataRedactor.redactText(entry.getValue());
            byId.put(id, new AgentEvidence(id, fact, value,
                    outcome.unknownFacts().contains(fact), outcome.emptyFacts().contains(fact)));
            ids.put(fact, id);
        }
        return ids;
    }
}

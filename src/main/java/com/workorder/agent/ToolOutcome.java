package com.workorder.agent;

import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 一次工具调用的结果。
 *
 * <p><b>失败不是异常</b>：未知工具、坏参数都以 {@link #error} 返回给模型，让它自己改正——
 * 把"模型调错了"直接抛成异常中止调查，是把协议的容错成本转嫁给用户（§10 要求覆盖非法工具与坏参数）。
 *
 * @param facts        事实键 → 事实值（值须已脱敏）
 * @param unknownFacts 其中"显式未知"的事实键
 * @param emptyFacts   其中"**空 / 无记录**"的事实键（例：`order.accept_events` = 从未接单）——
 *                     与"未知"分开：空是**完整事实**（§3.1），不是"查不到"；§11-4 的两个禁止项要区分这两者
 */
public record ToolOutcome(boolean ok, Map<String, String> facts, Set<String> unknownFacts,
                          Set<String> emptyFacts, String errorCode, String errorMessage) {

    public ToolOutcome {
        // 必须保序：证据编号按事实的登记顺序分配（E1、E2…），Map.copyOf 是哈希序，
        // 会让同一份工具结果在不同 JVM/不同轮次里拿到不同的编号（实测踩到：order.status 抢到了 E1）。
        facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts));
        unknownFacts = Collections.unmodifiableSet(new LinkedHashSet<>(unknownFacts));
        emptyFacts = Collections.unmodifiableSet(new LinkedHashSet<>(emptyFacts));
    }

    public static ToolOutcome ok(Map<String, String> facts) {
        return new ToolOutcome(true, facts, new LinkedHashSet<>(), new LinkedHashSet<>(), null, null);
    }

    public static ToolOutcome ok(Map<String, String> facts, Set<String> unknownFacts) {
        return new ToolOutcome(true, facts, unknownFacts, new LinkedHashSet<>(), null, null);
    }

    public static ToolOutcome ok(Map<String, String> facts, Set<String> unknownFacts, Set<String> emptyFacts) {
        return new ToolOutcome(true, facts, unknownFacts, emptyFacts, null, null);
    }

    public static ToolOutcome error(String errorCode, String errorMessage) {
        return new ToolOutcome(false, Map.of(), Set.of(), Set.of(), errorCode, errorMessage);
    }
}

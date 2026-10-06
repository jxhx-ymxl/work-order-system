package com.workorder.agent;

import java.time.Duration;

/**
 * 三个"有上限的口径" + 模型读取边界（`docs/AGENT-PLAN.md` §3.4 / §4.1 / §4.2 定稿）。
 *
 * <p>token 刻意不在这里：首版拿不到跨供应商一致的 usage 字段，拿不到的字段当上限会变成
 * "看起来有配额、实际恒不触发"的假保护（D76）。
 *
 * @param maxToolCalls          工具调用次数上限（含非法工具与坏参数——否则"报错重试"成了免费通道）
 * @param maxModelRounds        模型往返轮次上限（防"只说话不调工具"空转）
 * @param maxReportSubmissions  {@code finish_report} 提交次数上限（首次 + 一次重试）
 * @param runBudget             运行墙钟上限（§4.1：60s，2026-10-06 实测端到端 max 29.6s ≈ 2× 余量，D89）
 * @param modelConnectTimeout   模型连接超时
 * @param modelReadTimeout      模型读取超时（单轮的上限）；2026-10-06 按 D89 的 17 次调用分布 30s → 45s
 * @param maxModelResponseBytes 模型响应体上限；**在读取过程中**生效（§4.2）
 */
public record AgentLimits(
        int maxToolCalls,
        int maxModelRounds,
        int maxReportSubmissions,
        Duration runBudget,
        Duration modelConnectTimeout,
        Duration modelReadTimeout,
        int maxModelResponseBytes) {

    public AgentLimits {
        if (maxToolCalls < 1 || maxModelRounds < 1 || maxReportSubmissions < 1) {
            throw new IllegalArgumentException("预算上限必须是正数");
        }
        requirePositive(runBudget, "运行预算");
        // 0 在 HttpURLConnection 里不是"不等待"，而是**无限等待**——那会把"有上限的口径"变成没有上限
        // （复核发现 3：表现为偶发卡死，最难排查）。
        requirePositive(modelConnectTimeout, "模型连接超时");
        requirePositive(modelReadTimeout, "模型读取超时");
        if (maxModelResponseBytes < 1024) {
            throw new IllegalArgumentException("响应体上限过小，至少 1KB");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "必须是正数（0 在 HttpURLConnection 里等于无限等待）");
        }
    }

    /** S1 定稿值：工具 12 / 轮次 8 / 报告 2 / 60s / 响应体 256KB。 */
    public static AgentLimits s1Defaults() {
        return new AgentLimits(12, 8, 2, Duration.ofSeconds(60),
                // 单轮读超时 30s → 45s（D89：真供应商 17 次调用 max 26.1s，距 30s 只剩 3.9s 余量）。
                // 它**仍被"本轮剩余预算"收敛**（InvestigationAgent 每轮取 min(剩余预算, 本值)），
                // 所以放大它不会放大整次调查的墙钟，只是让单次调用不容易被误杀。
                Duration.ofSeconds(5), Duration.ofSeconds(45), 256 * 1024);
    }

    public AgentLimits withMaxToolCalls(int value) {
        return new AgentLimits(value, maxModelRounds, maxReportSubmissions, runBudget,
                modelConnectTimeout, modelReadTimeout, maxModelResponseBytes);
    }

    public AgentLimits withMaxModelRounds(int value) {
        return new AgentLimits(maxToolCalls, value, maxReportSubmissions, runBudget,
                modelConnectTimeout, modelReadTimeout, maxModelResponseBytes);
    }

    public AgentLimits withModelReadTimeout(Duration value) {
        return new AgentLimits(maxToolCalls, maxModelRounds, maxReportSubmissions, runBudget,
                modelConnectTimeout, value, maxModelResponseBytes);
    }

    public AgentLimits withModelConnectTimeout(Duration value) {
        return new AgentLimits(maxToolCalls, maxModelRounds, maxReportSubmissions, runBudget,
                value, modelReadTimeout, maxModelResponseBytes);
    }

    public AgentLimits withRunBudget(Duration value) {
        return new AgentLimits(maxToolCalls, maxModelRounds, maxReportSubmissions, value,
                modelConnectTimeout, modelReadTimeout, maxModelResponseBytes);
    }

    public AgentLimits withMaxModelResponseBytes(int value) {
        return new AgentLimits(maxToolCalls, maxModelRounds, maxReportSubmissions, runBudget,
                modelConnectTimeout, modelReadTimeout, value);
    }
}

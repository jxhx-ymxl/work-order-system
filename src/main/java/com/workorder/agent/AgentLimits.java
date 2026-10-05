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
 * @param runBudget             运行墙钟上限（§4.1 初值 60s，待 S4 实测校准）
 * @param modelConnectTimeout   模型连接超时
 * @param modelReadTimeout      模型读取超时（单轮的上限）
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
                Duration.ofSeconds(5), Duration.ofSeconds(30), 256 * 1024);
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

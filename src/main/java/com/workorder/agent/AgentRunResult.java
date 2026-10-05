package com.workorder.agent;

import java.util.List;

/**
 * 一次调查的终态。
 *
 * <p><b>不变量（构造期强制）</b>：非 {@code COMPLETED} 的终态 {@code report} 必须为 {@code null}，
 * 且必须带原因码——这就是"失败不得被伪装成正常报告"的机器判据（`docs/AGENT-PLAN.md` §3.2）。
 */
public record AgentRunResult(
        AgentStatus status,
        AgentFailure failure,
        AgentReport report,
        List<AgentEvidence> evidence,
        int toolCalls,
        int modelRounds,
        int reportSubmissions) {

    public AgentRunResult {
        evidence = List.copyOf(evidence);
        if (!status.isTerminal()) {
            throw new IllegalArgumentException("investigate() 必须返回终态，不允许返回 RUNNING");
        }
        if (status == AgentStatus.COMPLETED) {
            if (report == null) {
                throw new IllegalArgumentException("COMPLETED 必须有报告");
            }
            if (failure != null) {
                throw new IllegalArgumentException("COMPLETED 不得带失败原因");
            }
        } else {
            if (report != null) {
                throw new IllegalArgumentException("终态 " + status + " 不得产出报告（失败不得被伪装成正常报告）");
            }
            if (failure == null) {
                throw new IllegalArgumentException("终态 " + status + " 必须给出原因码");
            }
        }
    }

    public boolean hasReport() {
        return report != null;
    }

    public static AgentRunResult completed(AgentReport report, List<AgentEvidence> evidence,
                                           int toolCalls, int modelRounds, int reportSubmissions) {
        return new AgentRunResult(AgentStatus.COMPLETED, null, report, evidence, toolCalls, modelRounds, reportSubmissions);
    }

    public static AgentRunResult failed(String code, String message, List<AgentEvidence> evidence,
                                        int toolCalls, int modelRounds, int reportSubmissions) {
        return new AgentRunResult(AgentStatus.FAILED, AgentFailure.of(code, message), null,
                evidence, toolCalls, modelRounds, reportSubmissions);
    }

    public static AgentRunResult timedOut(String code, String message, List<AgentEvidence> evidence,
                                          int toolCalls, int modelRounds, int reportSubmissions) {
        return new AgentRunResult(AgentStatus.TIMED_OUT, AgentFailure.of(code, message), null,
                evidence, toolCalls, modelRounds, reportSubmissions);
    }
}

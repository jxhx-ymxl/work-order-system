package com.workorder.service;

import com.workorder.common.dto.TriageResult;

public interface OrderTriageService {

    /**
     * 请 LLM 判断类型与优先级。
     *
     * <p><b>契约（P5 步骤 3 起变更）</b>：**拿不到结论就抛异常，绝不返回兜底值冒充结果**。
     * 原因是异步链路里调用方（消费端）必须区分"AI 判成 OTHER/普通"与"这次根本没调通"——
     * 前者要写回并收口 {@code triage_status='DONE'}，后者要走重试账本、最终收口 {@code FAILED}。
     * 详见 {@link com.workorder.common.TriageUnavailableException}。
     *
     * @throws com.workorder.common.TriageUnavailableException 未配置 LLM / 网络不可达 / 超时 /
     *         HTTP 错误 / 响应体无法解析
     */
    TriageResult triage(String title, String content);

    /**
     * 启动期自检用：打一次**最小请求**探测 LLM 是否可用。
     *
     * @return {@code null} = 探测通过；非 null = 人话描述的失败原因
     *         （区分"未配置 / HTTP 400 模型名不对 / HTTP 401 key 无效 / 不可达超时"，便于一眼定位）
     */
    String probeFailure();
}

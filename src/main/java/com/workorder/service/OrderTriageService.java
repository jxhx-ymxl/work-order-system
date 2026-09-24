package com.workorder.service;

import com.workorder.common.dto.TriageResult;

public interface OrderTriageService {

    TriageResult triage(String title, String content);

    /**
     * 启动期自检用：打一次**最小请求**探测 LLM 是否可用。
     *
     * @return {@code null} = 探测通过；非 null = 人话描述的失败原因
     *         （区分"未配置 / HTTP 400 模型名不对 / HTTP 401 key 无效 / 不可达超时"，便于一眼定位）
     */
    String probeFailure();
}

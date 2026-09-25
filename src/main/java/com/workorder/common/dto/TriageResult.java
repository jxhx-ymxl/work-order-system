package com.workorder.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TriageResult {

    private String suggestedType;
    private Integer suggestedPriority;

    /**
     * 判定依据（P5 收口新增）：模型在**信息不足**时必须写明缺什么（例如"依据不足：未说明设备、现象与位置"）。
     *
     * <p>为什么保留它：prompt 里要求模型给依据，如果代码不接、日志不打，这条要求就是"写了不生效"——
     * 没人能看出某张单被判成 `OTHER` 到底是"模型认为它是非故障类"还是"它没看懂"。
     * 目前只写进应用日志（不动 `t_work_order_log.remark`，避免列长与截断引入新风险）。
     */
    private String reason;

    /** 兼容构造器：既有的两参调用点（含单测）不必改 */
    public TriageResult(String suggestedType, Integer suggestedPriority) {
        this(suggestedType, suggestedPriority, null);
    }

    public static TriageResult fallback() {
        return new TriageResult("OTHER", 0);
    }
}

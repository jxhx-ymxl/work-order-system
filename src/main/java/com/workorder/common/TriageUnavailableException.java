package com.workorder.common;

/**
 * 分诊**没有拿到结论**（P5 步骤 3 补）。
 *
 * <h3>为什么需要它：过去"失败"被洗成了"成功"</h3>
 * 异步化之前，{@code OrderTriageServiceImpl.triage()} 把所有失败（超时 / 网络不可达 / 401 / 500 /
 * 响应体解析不了）都**吞掉并返回兜底值 {@code OTHER/普通}**。同步时代那样做是对的——
 * 用户正等着提交，不能因为外部服务抖动让他失败，兜底值落库即可。
 *
 * <p><b>异步化之后这条契约就错了</b>：消费端拿到的 {@code OTHER/0} 与"AI 真的判成其他/普通"**完全无法区分**，
 * 于是：
 * <ul>
 *   <li>LLM 明明一次都没调通，工单却被写成 {@code triage_status='DONE'}（界面显示"已分类：其他"，是假状态）；</li>
 *   <li>"失败 → 重试账本 → 阶梯重投 → 停车 → FAILED"这条链**一次都走不到**
 *       （消费端只在返回值"非法"时才判失败，而兜底值恰好是合法的）——即 F1-4 验收里
 *       "triage 不可用 / 超时 / 返回非法值 → 保持 PENDING + 重试账本有记录"实际上没实现。</li>
 * </ul>
 * 因此本异常的作用是**把失败如实暴露给调用方**：调用方（消费端）据此走重试账本，
 * 阶梯走完仍未成功 → 账本 PARKED → 工单 {@code triage_status='FAILED'}（见 D62）。
 *
 * <p>注意：抛异常**不会**影响用户提交——提交早在落库时就返回了，异步链路失败只影响这张工单的分类结果。
 */
public class TriageUnavailableException extends RuntimeException {

    public TriageUnavailableException(String message) {
        super(message);
    }

    public TriageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

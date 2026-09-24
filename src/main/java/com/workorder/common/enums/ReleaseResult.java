package com.workorder.common.enums;

/**
 * 超时释放的三态结果（P1 步骤 4 引入）。
 *
 * <p><b>为什么必须显式化</b>：改造前 {@code releaseOrder} 对"工单不存在"抛异常、对"状态守卫未命中"
 * 静默 {@code return}——消费者拿到的信息只有"没抛异常"，无法区分下面三种完全不同的情况，
 * 于是 ACK/NACK 契约也没法写：
 *
 * <table border="1">
 *   <caption>三态语义</caption>
 *   <tr><th>取值</th><th>含义</th><th>判据</th><th>消费者动作</th></tr>
 *   <tr><td>{@link #RELEASED}</td><td>真的释放成功</td><td>状态守卫 UPDATE 影响 1 行</td><td>ACK</td></tr>
 *   <tr><td>{@link #SKIPPED}</td><td>状态已变（已被 START/COMPLETE 等改过），正常跳过</td><td>影响 0 行</td><td>ACK（不算失败）</td></tr>
 *   <tr><td>{@link #ERROR}</td><td>内部出错（工单不存在等），消息本身无法完成</td><td>业务前置条件不成立</td><td>P1 阶段也 ACK + ERROR 日志（无 DLX，NACK 会静默丢消息）；P4 起 NACK 进死信</td></tr>
 * </table>
 *
 * <p>注意：{@code SKIPPED} **不是**失败。它是"这条事件来晚了/不该生效了"的正常结论——
 * 状态守卫（{@code WHERE status='ACCEPTED'}）本来就是靠"影响 0 行"表达这个语义的（plan §3.4 第 5 条）。
 */
public enum ReleaseResult {

    /** 真的释放成功（SQL 影响 1 行） */
    RELEASED,

    /** 状态守卫未命中（工单已被 START 等改过），正常跳过，不算失败 */
    SKIPPED,

    /** 内部出错（如工单不存在、消息无法解析），需要留痕 */
    ERROR
}

package com.workorder.service;

/** 通知渠道接口 —— 策略模式扩展点 */
public interface NotifyChannel {

    void send(Long userId, String title, String content);

    /**
     * **带事件幂等键**的发送（P5 步骤 2 新增）：同一条事件（{@code eventId}）对同一个接收人只应落一条。
     *
     * <p><b>为什么幂等键放在这里而不是消费端做"先查再发"</b>：查再发在并发下必然漏
     * （两个线程都查到"没有"）；唯一索引是数据库给的原子判据。实现方把 {@code eventId} 落库并依赖唯一约束，
     * 命中冲突时返回 {@code false}（= 这条已经发过），**不得抛异常**——调用方靠返回值统计真实新增数。
     *
     * <p>这是抽象方法而不是带默认实现的"退化发送"：不支持幂等键的渠道若静默退化成普通发送，
     * 重复投递就会变成重复打扰，且**没有任何痕迹**。新增渠道时必须显式回答"你的幂等键是什么"。
     *
     * @return {@code true} = 本次真的新增了一条；{@code false} = 该事件已给该接收人发过，本次跳过
     */
    boolean sendOnce(Long userId, String title, String content, String eventId, String refType, Long refId);

    default String channelName() {
        return this.getClass().getSimpleName();
    }
}

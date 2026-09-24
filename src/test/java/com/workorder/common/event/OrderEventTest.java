package com.workorder.common.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件键的语义测试（P1 步骤 1）。
 *
 * <p>本类是**纯单测**（不起 Spring、不连任何中间件）——事件键是纯函数，隔离验证最可靠。
 * 注意：步骤 1 的验收里还有一条"同一 eventId 重复 publish 只写一条 outbox 记录"，
 * 那属于**步骤 2 的写路径**，需要 outbox 表与业务事务，本类不覆盖（见交付报告的范围说明）。
 */
class OrderEventTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 24, 10, 0);

    @Test
    @DisplayName("事件键格式：{aggregate}:{id}:v{version}:{eventType}")
    void eventId_hasExpectedFormat() {
        OrderEvent e = OrderEvent.orderReleaseCheck(123L, 7, T0, T0.plusMinutes(30));
        assertEquals("order:123:v7:ORDER_RELEASE_CHECK", e.eventId());
        assertEquals("ORDER_RELEASE_CHECK", e.eventType());
        assertEquals(123L, e.aggregateId());
        assertEquals(7, e.aggregateVersion());
        assertEquals(T0, e.occurredAt());
    }

    @Test
    @DisplayName("不同 version 必须产生不同 eventId（同一工单的两次合法接单不是同一条消息）")
    void differentVersions_produceDifferentEventIds() {
        // 场景：PENDING→ACCEPTED→RELEASED→ACCEPTED，两次接单是两个合法事件
        OrderEvent first = OrderEvent.orderReleaseCheck(123L, 2, T0, T0.plusMinutes(30));
        OrderEvent second = OrderEvent.orderReleaseCheck(123L, 5, T0.plusMinutes(40), T0.plusMinutes(70));

        assertNotEquals(first.eventId(), second.eventId(),
                "两次合法事件的 eventId 相同，说明去重键退化成了实体维度——第二次会被当重复吞掉");
        assertEquals("order:123:v2:ORDER_RELEASE_CHECK", first.eventId());
        assertEquals("order:123:v5:ORDER_RELEASE_CHECK", second.eventId());
    }

    @Test
    @DisplayName("同一 (orderId, version) 重复构造得到同一 eventId（幂等键必须稳定）")
    void sameInputs_produceSameEventId() {
        assertEquals(OrderEvent.orderReleaseCheck(9L, 3, T0, T0.plusMinutes(30)).eventId(),
                OrderEvent.orderReleaseCheck(9L, 3, T0.plusSeconds(5), T0.plusMinutes(30)).eventId(),
                "同一事件重复构造必须得到相同的 eventId，否则 outbox 的 UNIQUE 约束与消费端去重都会失效");
    }

    @Test
    @DisplayName("瘦消息：payload 只带 orderId")
    void payload_carriesOnlyOrderId() {
        OrderEvent e = OrderEvent.orderReleaseCheck(42L, 1, T0, T0.plusMinutes(30));
        assertEquals(1, e.payload().size(), "payload 只应带 orderId（瘦消息）");
        assertEquals(42L, e.payload().get("orderId"));
        assertFalse(e.payload().containsKey("status"), "不应携带工单快照字段（避免与库中状态不一致）");
    }

    @Test
    @DisplayName("不同工单的 eventId 不同")
    void differentOrders_produceDifferentEventIds() {
        assertNotEquals(OrderEvent.orderReleaseCheck(1L, 1, T0, T0.plusMinutes(30)).eventId(),
                OrderEvent.orderReleaseCheck(2L, 1, T0, T0.plusMinutes(30)).eventId());
    }
}

package com.workorder.scheduler;

import com.workorder.entity.WorkOrder;
import com.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class SlaEscalationSchedulerTest {

    @Autowired
    private WorkOrderMapper workOrderMapper;

    @Test
    @DisplayName("findSlaExpired —— 只返回已过期且状态未完结的工单")
    void testFindSlaExpired_onlyExpiredAndUnfinished() {
        // 3条已过期且未完结
        WorkOrder expired1 = buildOrder("PENDING", LocalDateTime.now().minusHours(2));
        WorkOrder expired2 = buildOrder("ACCEPTED", LocalDateTime.now().minusDays(1));
        WorkOrder expired3 = buildOrder("IN_PROGRESS", LocalDateTime.now().minusMinutes(30));
        // 2条未过期
        WorkOrder future1 = buildOrder("PENDING", LocalDateTime.now().plusHours(3));
        WorkOrder future2 = buildOrder("ACCEPTED", LocalDateTime.now().plusDays(1));
        // 1条已过期但已完结(CLOSED)
        WorkOrder closed = buildOrder("CLOSED", LocalDateTime.now().minusHours(5));

        workOrderMapper.insert(expired1);
        workOrderMapper.insert(expired2);
        workOrderMapper.insert(expired3);
        workOrderMapper.insert(future1);
        workOrderMapper.insert(future2);
        workOrderMapper.insert(closed);

        List<WorkOrder> result = workOrderMapper.findSlaExpired(200);

        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(o ->
                List.of("PENDING", "ACCEPTED", "IN_PROGRESS").contains(o.getStatus())));
        assertTrue(result.stream().allMatch(o ->
                o.getSlaDeadline().isBefore(LocalDateTime.now())));
    }

    @Test
    @DisplayName("findSlaExpired —— 无超时工单时返回空列表")
    void testFindSlaExpired_noExpired() {
        WorkOrder w1 = buildOrder("PENDING", LocalDateTime.now().plusHours(2));
        WorkOrder w2 = buildOrder("ACCEPTED", LocalDateTime.now().plusDays(1));
        workOrderMapper.insert(w1);
        workOrderMapper.insert(w2);

        List<WorkOrder> result = workOrderMapper.findSlaExpired(200);
        assertEquals(0, result.size());
    }

    @Test
    @DisplayName("findSlaExpired —— batchSize 限制生效")
    void testFindSlaExpired_batchSizeLimit() {
        for (int i = 0; i < 5; i++) {
            WorkOrder order = buildOrder("PENDING", LocalDateTime.now().minusHours(1));
            workOrderMapper.insert(order);
        }

        List<WorkOrder> result = workOrderMapper.findSlaExpired(3);
        assertEquals(3, result.size());
    }

    private WorkOrder buildOrder(String status, LocalDateTime slaDeadline) {
        WorkOrder order = new WorkOrder();
        order.setOrderNo("WO-" + System.nanoTime());
        order.setTitle("测试工单");
        order.setContent("测试");
        order.setType("REPAIR");
        order.setPriority(0);
        order.setStatus(status);
        order.setSubmitterId(1L);
        order.setRejectCount(0);
        order.setMaxReject(3);
        order.setSlaDeadline(slaDeadline);
        order.setVersion(0);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        return order;
    }
}

package com.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * P1 步骤 5 的行为变更覆盖：`startOrder` **不再调用 Redis**（原来有一句
 * `redisTemplate.delete("order:accept_timeout:" + orderId)`，删的是一个从不存在、也无人读取的键）。
 *
 * <p>怎么证明"少了一次 Redis 调用但不改行为"：
 * ① `StringRedisTemplate` 换成 mock，断言 `delete(...)` **一次都没被调用**（旧实现必然调用一次）；
 * ② 同时断言业务结果不变——状态 `ACCEPTED → IN_PROGRESS`、`version+1`、`assignee` 不动。
 *
 * <p>为什么把这个测试单独放一个类：`@MockBean` 会替换整个应用上下文里的 Redis 模板，
 * 放在别的测试类里会波及 OrderNoGenerator 等真正需要 Redis 的用例；单独一个类 = 独立上下文，
 * 其他测试类继续用真实 Redis（测试库 DB 1）。
 */
@SpringBootTest
@ActiveProfiles("test")
class StartOrderNoRedisTest {

    @MockBean
    private StringRedisTemplate redisTemplate;

    @Autowired private WorkOrderService workOrderService;
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long orderId;
    private Long watermark;

    @BeforeEach
    void setUp() {
        WorkOrder last = workOrderMapper.selectOne(new LambdaQueryWrapper<WorkOrder>()
                .select(WorkOrder::getId).orderByDesc(WorkOrder::getId).last("LIMIT 1"));
        watermark = last == null || last.getId() == null ? 0L : last.getId();

        WorkOrder[] holder = new WorkOrder[1];
        transactionTemplate.execute(tx -> {
            WorkOrder o = new WorkOrder();
            o.setOrderNo("TST-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18));
            o.setTitle("start 不再碰 Redis");
            o.setContent("x");
            o.setType("NETWORK");
            o.setPriority(0);
            o.setStatus("ACCEPTED");
            o.setSubmitterId(1L);
            o.setAssigneeId(127L);
            o.setRejectCount(0);
            o.setMaxReject(3);
            o.setVersion(0);
            o.setCreatedAt(LocalDateTime.now());
            o.setUpdatedAt(LocalDateTime.now());
            workOrderMapper.insert(o);
            holder[0] = o;
            return null;
        });
        orderId = holder[0].getId();
    }

    @AfterEach
    void cleanUp() {
        workOrderMapper.delete(new LambdaQueryWrapper<WorkOrder>().gt(WorkOrder::getId, watermark));
    }

    @Test
    @DisplayName("startOrder：状态流转正常（ACCEPTED→IN_PROGRESS, version+1），且**不再调用 Redis**")
    void startOrder_doesNotTouchRedis() {
        workOrderService.startOrder(orderId, 127L);

        WorkOrder after = workOrderMapper.selectById(orderId);
        assertEquals("IN_PROGRESS", after.getStatus(), "状态流转必须不变");
        assertEquals(1, after.getVersion().intValue(), "version+1 必须不变");
        assertEquals(127L, after.getAssigneeId().longValue(), "assignee 不动");

        verify(redisTemplate, never()).delete(anyString());
    }
}

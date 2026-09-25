package com.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.entity.ConsumeRecord;
import com.workorder.entity.MessageRetry;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.ConsumeRecordMapper;
import com.workorder.mapper.MessageRetryMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.impl.ConsumeRecordService;
import com.workorder.service.impl.OrderTriageConsumeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用**真实**的 {@code OrderTriageServiceImpl}（不经 mock）验证"LLM 不可用 → 进重试账本"。
 *
 * <p>为什么单独一个类：既有的 {@code OrderTriageConsumeTest} 用 {@code @MockBean} 把 triage 服务换成了 mock，
 * 并让它在失败时 {@code thenThrow(...)}——**测试通过，而生产实现却在失败时返回兜底值**，
 * 于是"失败 → 重试账本 → 停车 → FAILED"这条链在真实环境里一次都没走通。
 * 这正是"用 mock 钉契约会掩盖实现分歧"的典型，本类用真实现把它钉住。
 *
 * <p>本类把 {@code llm.api.url} 置空（等价于"LLM 不可用"），断言：
 * ① 消费返回 {@code RETRY_SCHEDULED}；② 去重记录回滚（0 行）；③ 重试账本 1 行、原因含"未配置 LLM_API_URL"；
 * ④ 工单保持 {@code PENDING}（还没到停车，界面应显示"分类中"）。
 */
@SpringBootTest(properties = "llm.api.url=")
@ActiveProfiles("test")
class OrderTriageRealFailureTest {

    @Autowired private OrderTriageConsumeService orderTriageConsumeService;
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private ConsumeRecordMapper consumeRecordMapper;
    @Autowired private MessageRetryMapper messageRetryMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long orderWatermark;
    private Long consumeWatermark;
    private Long retryWatermark;

    @BeforeEach
    void setUp() {
        orderWatermark = maxOrderId();
        consumeWatermark = maxConsumeId();
        retryWatermark = maxRetryId();
    }

    @AfterEach
    void cleanUp() {
        messageRetryMapper.delete(new LambdaQueryWrapper<MessageRetry>().gt(MessageRetry::getId, retryWatermark));
        consumeRecordMapper.delete(new LambdaQueryWrapper<ConsumeRecord>().gt(ConsumeRecord::getId, consumeWatermark));
        workOrderMapper.delete(new LambdaQueryWrapper<WorkOrder>().gt(WorkOrder::getId, orderWatermark));
    }

    @Test
    @DisplayName("真实 triage 服务：LLM 不可用 → 落重试账本（而不是把失败写成 DONE/其他）")
    void llmUnavailable_writesRetryLedger_notDone() {
        Long orderId = insertPendingOrder();
        String eventId = "order:" + orderId + ":v0:ORDER_TRIAGE";

        OrderTriageConsumeService.Outcome outcome = orderTriageConsumeService.consume(
                eventId, orderId, "{\"orderId\":" + orderId + ",\"missingFields\":[\"type\",\"priority\"]}");

        assertEquals(OrderTriageConsumeService.Outcome.RETRY_SCHEDULED, outcome,
                "LLM 不可用必须按失败处理（过去这里会被当成一次成功、写成 DONE）");
        assertEquals(0, consumeRecordMapper.selectCount(new LambdaQueryWrapper<ConsumeRecord>()
                .eq(ConsumeRecord::getEventId, eventId)), "失败时去重记录必须回滚，否则重投会被判已消费");
        MessageRetry retry = messageRetryMapper.selectOne(new LambdaQueryWrapper<MessageRetry>()
                .eq(MessageRetry::getEventId, eventId));
        assertNotNull(retry, "必须落重试账本（F1-4 验收：triage 不可用时重试账本里有记录）");
        assertEquals(ConsumeRecordService.CONSUMER_ORDER_TRIAGE, retry.getConsumer());
        assertTrue(retry.getLastError().contains("LLM_API_URL"),
                "失败原因要能定位（含'未配置 LLM_API_URL'）：" + retry.getLastError());
        assertEquals("PENDING", workOrderMapper.selectById(orderId).getTriageStatus(),
                "还没到停车上限，工单应保持 PENDING（界面显示「分类中」）");
    }

    private Long insertPendingOrder() {
        WorkOrder[] holder = new WorkOrder[1];
        transactionTemplate.execute(tx -> {
            WorkOrder o = new WorkOrder();
            o.setOrderNo("TST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18));
            o.setTitle("真实失败测试");
            o.setContent("x");
            o.setType("OTHER");
            o.setPriority(0);
            o.setStatus("PENDING");
            o.setSubmitterId(1L);
            o.setRejectCount(0);
            o.setMaxReject(3);
            o.setTriageStatus("PENDING");
            o.setSlaDeadline(LocalDateTime.now().plusMinutes(480));
            o.setVersion(0);
            o.setCreatedAt(LocalDateTime.now());
            o.setUpdatedAt(LocalDateTime.now());
            workOrderMapper.insert(o);
            holder[0] = o;
            return null;
        });
        return holder[0].getId();
    }

    private Long maxOrderId() {
        WorkOrder o = workOrderMapper.selectOne(new LambdaQueryWrapper<WorkOrder>()
                .select(WorkOrder::getId).orderByDesc(WorkOrder::getId).last("LIMIT 1"));
        return o == null || o.getId() == null ? 0L : o.getId();
    }

    private Long maxConsumeId() {
        ConsumeRecord r = consumeRecordMapper.selectOne(new LambdaQueryWrapper<ConsumeRecord>()
                .select(ConsumeRecord::getId).orderByDesc(ConsumeRecord::getId).last("LIMIT 1"));
        return r == null || r.getId() == null ? 0L : r.getId();
    }

    private Long maxRetryId() {
        MessageRetry r = messageRetryMapper.selectOne(new LambdaQueryWrapper<MessageRetry>()
                .select(MessageRetry::getId).orderByDesc(MessageRetry::getId).last("LIMIT 1"));
        return r == null || r.getId() == null ? 0L : r.getId();
    }
}

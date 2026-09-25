package com.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.entity.MessageRetry;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.MessageRetryMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.impl.ConsumeRecordService;
import com.workorder.service.impl.MessageRetryService;
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
 * P5 步骤 3 补的缺口：**分诊彻底失败时，工单必须离开 {@code PENDING}**（否则"分类中"是假终态）。
 *
 * <p>本类验证三件事：
 * <ol>
 *   <li>账本第 6 次失败转 {@code PARKED} 时，同一事务里把工单置 {@code triage_status='FAILED'}；</li>
 *   <li><b>状态守卫</b>：已经 {@code DONE} 的工单不得被迟到的"失败"改回 {@code FAILED}；</li>
 *   <li><b>只影响分诊链路</b>：释放检查/提交通知这两个消费者停车时**不得**动工单的分诊状态。</li>
 * </ol>
 * 事务边界：置 FAILED 与账本变更都在 {@link MessageRetryService#recordFailure} 的
 * {@code REQUIRES_NEW} 事务里（同一方法内两次写），因此本类断言的是"两者一起到达终态"。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderTriageParkedMarksFailedTest {

    @Autowired private MessageRetryService messageRetryService;
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private MessageRetryMapper messageRetryMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long orderWatermark;
    private Long retryWatermark;

    @BeforeEach
    void setUp() {
        orderWatermark = maxOrderId();
        retryWatermark = maxRetryId();
    }

    @AfterEach
    void cleanUp() {
        messageRetryMapper.delete(new LambdaQueryWrapper<MessageRetry>().gt(MessageRetry::getId, retryWatermark));
        workOrderMapper.delete(new LambdaQueryWrapper<WorkOrder>().gt(WorkOrder::getId, orderWatermark));
    }

    @Test
    @DisplayName("【核心】第 6 次失败 → 账本 PARKED 且工单 triage_status=FAILED（不再永远停在 PENDING）")
    void parked_marksOrderAsFailed() {
        Long orderId = insertOrder("PENDING");
        String eventId = "order:" + orderId + ":v0:ORDER_TRIAGE";
        String payload = "{\"orderId\":" + orderId + ",\"missingFields\":[\"type\",\"priority\"]}";

        for (int i = 1; i <= MessageRetryService.MAX_ATTEMPTS; i++) {
            messageRetryService.recordFailure(eventId, ConsumeRecordService.CONSUMER_ORDER_TRIAGE, payload, "第 " + i + " 次：LLM 超时");
            assertEquals("PENDING", triageStatus(orderId), "第 " + i + " 次失败后仍在自动重投窗口内，应保持 PENDING");
        }
        // 第 6 次（> MAX_ATTEMPTS）→ PARKED
        messageRetryService.recordFailure(eventId, ConsumeRecordService.CONSUMER_ORDER_TRIAGE, payload, "第 6 次：LLM 仍不可用");

        MessageRetry retry = findRetry(eventId);
        assertNotNull(retry);
        assertEquals("PARKED", retry.getStatus(), "超过上限必须停车（不再自动重投）");
        assertEquals(6, retry.getAttempt().intValue());
        assertEquals("FAILED", triageStatus(orderId),
                "账本停车时工单必须同时收口为 FAILED——否则界面永远显示「分类中」（PENDING 成了假终态）");
    }

    @Test
    @DisplayName("状态守卫：已 DONE 的工单不被迟到的失败改回 FAILED")
    void parked_doesNotOverrideDone() {
        Long orderId = insertOrder("DONE");
        String eventId = "order:" + orderId + ":v0:ORDER_TRIAGE";
        String payload = "{\"orderId\":" + orderId + "}";

        for (int i = 1; i <= MessageRetryService.MAX_ATTEMPTS + 1; i++) {
            messageRetryService.recordFailure(eventId, ConsumeRecordService.CONSUMER_ORDER_TRIAGE, payload, "err");
        }

        assertEquals("PARKED", findRetry(eventId).getStatus());
        assertEquals("DONE", triageStatus(orderId), "守卫未命中时不得覆盖已定稿状态");
    }

    @Test
    @DisplayName("只影响分诊链路：释放检查的账本停车不动工单的分诊状态")
    void parked_otherConsumer_leavesOrderUntouched() {
        Long orderId = insertOrder("PENDING");
        String eventId = "order:" + orderId + ":v1:ORDER_RELEASE_CHECK";
        String payload = "{\"orderId\":" + orderId + "}";

        for (int i = 1; i <= MessageRetryService.MAX_ATTEMPTS + 1; i++) {
            messageRetryService.recordFailure(eventId, ConsumeRecordService.CONSUMER_ORDER_RELEASE, payload, "err");
        }

        assertEquals("PARKED", findRetry(eventId).getStatus());
        assertEquals("PENDING", triageStatus(orderId),
                "释放检查的权威通道是兜底扫描，它的账本停车与分诊状态无关");
    }

    @Test
    @DisplayName("payload 不可解析时退回事件键解析，仍能定位工单")
    void parked_fallsBackToEventIdParsing() {
        Long orderId = insertOrder("PENDING");
        String eventId = "order:" + orderId + ":v0:ORDER_TRIAGE";

        for (int i = 1; i <= MessageRetryService.MAX_ATTEMPTS + 1; i++) {
            messageRetryService.recordFailure(eventId, ConsumeRecordService.CONSUMER_ORDER_TRIAGE, "{}", "err");
        }

        assertEquals("FAILED", triageStatus(orderId), "payload 里没有 orderId 时应从 order:{id}:v… 解析");
    }

    // ────────────── helpers ──────────────

    private Long insertOrder(String triageStatus) {
        WorkOrder[] holder = new WorkOrder[1];
        transactionTemplate.execute(tx -> {
            WorkOrder o = new WorkOrder();
            o.setOrderNo("TST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18));
            o.setTitle("分诊停车测试");
            o.setContent("x");
            o.setType("OTHER");
            o.setPriority(0);
            o.setStatus("PENDING");
            o.setSubmitterId(1L);
            o.setRejectCount(0);
            o.setMaxReject(3);
            o.setTriageStatus(triageStatus);
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

    private String triageStatus(Long orderId) {
        return workOrderMapper.selectById(orderId).getTriageStatus();
    }

    private MessageRetry findRetry(String eventId) {
        return messageRetryMapper.selectOne(new LambdaQueryWrapper<MessageRetry>()
                .eq(MessageRetry::getEventId, eventId));
    }

    private Long maxOrderId() {
        WorkOrder o = workOrderMapper.selectOne(new LambdaQueryWrapper<WorkOrder>()
                .select(WorkOrder::getId).orderByDesc(WorkOrder::getId).last("LIMIT 1"));
        return o == null || o.getId() == null ? 0L : o.getId();
    }

    private Long maxRetryId() {
        MessageRetry r = messageRetryMapper.selectOne(new LambdaQueryWrapper<MessageRetry>()
                .select(MessageRetry::getId).orderByDesc(MessageRetry::getId).last("LIMIT 1"));
        return r == null || r.getId() == null ? 0L : r.getId();
    }
}

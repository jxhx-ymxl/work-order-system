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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 【故障注入】PARKED 与 FAILED **同事务**——用注入的失败证明，而不是读代码断言"它们在一个方法里"。
 *
 * <p>为什么必须有这个测试（而不是只写注释）：当前保证是"两次写都在
 * {@code MessageRetryService.recordFailure} 的同一个 {@code @Transactional(REQUIRES_NEW)} 方法里"——
 * 这是**读代码得出的结论**。而这个项目一路的规则是"用测试证明"。风险场景很具体：
 * 将来有人为了让"失败更可见"把其中一句挪到方法外/换传播级别，注释不会报警，**这个测试会**。
 *
 * <p>做法（与 P4 步骤 1 的 {@code ConsumeRecordIdempotencyTest} 同一个套路）：{@code @SpyBean}
 * 真实 {@code WorkOrderMapper}，让 {@code markTriageFailed} 抛异常（**这是事务里的第二次写**），
 * 然后断言：
 * <ol>
 *   <li><b>账本没有变成 PARKED</b>（仍 `PENDING`、`attempt` 仍 5）→ 说明第一次写跟着回滚了；</li>
 *   <li><b>工单仍是 PENDING</b> → 两个写一起回滚，没有半截状态。</li>
 * </ol>
 * 另有对照用例（不注入）证明同一套前置下两者会一起到达终态——排除"一直是 PENDING 是因为前置造错了"。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderTriageParkedAtomicityTest {

    /** 真实的 mapper 之上加一层 spy；未打桩的方法照常委托给真实实现 */
    @SpyBean private WorkOrderMapper workOrderMapper;

    @Autowired private MessageRetryService messageRetryService;
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
    @DisplayName("【故障注入】置 FAILED 的 UPDATE 抛异常 → 账本没有变成 PARKED（第一次写也跟着回滚了）")
    void injectedFailure_rollsBackBothWrites() {
        Long orderId = insertOrder("PENDING");
        String eventId = "order:" + orderId + ":v0:ORDER_TRIAGE";
        String payload = "{\"orderId\":" + orderId + "}";
        insertLedgerAtLastAttempt(eventId, payload);   // attempt=5：下一次失败就该停车

        // 注入：事务里的**第二次写**失败
        doThrow(new IllegalStateException("注入的失败：置 FAILED 的 UPDATE 失败"))
                .when(workOrderMapper).markTriageFailed(anyLong());

        Exception thrown = assertThrows(Exception.class,
                () -> messageRetryService.recordFailure(eventId, ConsumeRecordService.CONSUMER_ORDER_TRIAGE,
                        payload, "第 6 次：LLM 仍不可用"),
                "注入的异常必须抛出（否则说明打桩没生效，断言就没有意义）");
        assertTrue(String.valueOf(thrown.getMessage()).contains("注入的失败"),
                "抛出来的必须是注入的那个异常：" + thrown);

        MessageRetry after = findRetry(eventId);
        assertEquals("PENDING", after.getStatus(),
                "账本不得变成 PARKED——第二次写失败必须把第一次写一起回滚（否则就是'账本已停车但工单还在分类中'）");
        assertEquals(5, after.getAttempt().intValue(), "attempt 也必须回滚到失败前的值");
        assertEquals("PENDING", workOrderMapper.selectById(orderId).getTriageStatus(),
                "工单也应保持 PENDING（两个写一起回滚）");
    }

    @Test
    @DisplayName("对照：同样前置但不注入失败 → 账本 PARKED 且工单 FAILED（证明上一条的'仍是 PENDING'来自注入）")
    void control_withoutInjection_bothReachTerminalState() {
        Long orderId = insertOrder("PENDING");
        String eventId = "order:" + orderId + ":v0:ORDER_TRIAGE";
        String payload = "{\"orderId\":" + orderId + "}";
        insertLedgerAtLastAttempt(eventId, payload);

        messageRetryService.recordFailure(eventId, ConsumeRecordService.CONSUMER_ORDER_TRIAGE, payload, "第 6 次");

        assertEquals("PARKED", findRetry(eventId).getStatus());
        assertEquals("FAILED", workOrderMapper.selectById(orderId).getTriageStatus());
    }

    // ────────────── helpers ──────────────

    /** 直接落一张"已失败 5 次、等待第 6 次"的账本行（比调 5 次 recordFailure 更确定） */
    private void insertLedgerAtLastAttempt(String eventId, String payload) {
        MessageRetry row = new MessageRetry();
        row.setEventId(eventId);
        row.setConsumer(ConsumeRecordService.CONSUMER_ORDER_TRIAGE);
        row.setPayload(payload);
        row.setAttempt(MessageRetryService.MAX_ATTEMPTS);   // = 5
        row.setStatus("PENDING");
        row.setNextRetryAt(LocalDateTime.now().minusMinutes(1));
        row.setLastError("前 5 次：LLM 不可用");
        messageRetryMapper.insert(row);
    }

    private Long insertOrder(String triageStatus) {
        WorkOrder[] holder = new WorkOrder[1];
        transactionTemplate.execute(tx -> {
            WorkOrder o = new WorkOrder();
            o.setOrderNo("TST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18));
            o.setTitle("同事务故障注入");
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

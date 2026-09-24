package com.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.enums.ReleaseResult;
import com.workorder.entity.ConsumeRecord;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.ConsumeRecordMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.impl.ConsumeRecordService;
import com.workorder.service.impl.ConsumeRecordService.ConsumeResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * P4 步骤 1 的核心验证：**消费端幂等表的事务边界**（真库，不是 Mockito 演习）。
 *
 * <p>三条必须证明的事：
 * <ol>
 *   <li>同一 {@code eventId} + 同一 consumer 消费两次 → 第二次直接跳过，**业务只执行一次**（version 只 +1）；</li>
 *   <li><b>业务失败时去重记录也回滚</b>（本步的灵魂）：用 {@code @SpyBean} 让真实 {@code releaseOrder} 抛错，
 *       断言 {@code t_consume_record} 与工单表**都没留痕**。若顺序写反（先提交去重记录、再执行业务），这条必然失败。</li>
 *   <li>不同 {@code eventId}（同一工单两次合法接单，version 不同）→ 两条都被消费，
 *       第二次由**状态守卫**判 SKIPPED（两道防线各司其职）。</li>
 * </ol>
 *
 * <p>隔离：独立测试库 + 按 id 水位线清理（需要真实提交，不能用 {@code @Transactional} 回滚整场测试）。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConsumeRecordIdempotencyTest {

    /** 只为了让"业务失败"可控：默认走真实实现，只有显式 stub 的用例才抛错 */
    @SpyBean
    private WorkOrderService workOrderService;

    @Autowired private ConsumeRecordService consumeRecordService;
    @Autowired private ConsumeRecordMapper consumeRecordMapper;
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long orderWatermark;
    private Long consumeWatermark;

    @BeforeEach
    void setUp() {
        orderWatermark = maxOrderId();
        consumeWatermark = maxConsumeId();
    }

    @AfterEach
    void cleanUp() {
        consumeRecordMapper.delete(new LambdaQueryWrapper<ConsumeRecord>().gt(ConsumeRecord::getId, consumeWatermark));
        workOrderMapper.delete(new LambdaQueryWrapper<WorkOrder>().gt(WorkOrder::getId, orderWatermark));
    }

    @Test
    @DisplayName("同一 eventId 消费两次：第二次 duplicate=true，业务只执行一次（version 只 +1）")
    void sameEventId_consumedOnce() {
        Long orderId = insertAcceptedOrder();
        String eventId = "order:" + orderId + ":v1:ORDER_RELEASE_CHECK";

        ConsumeResult first = consumeRecordService.consumeReleaseCheck(eventId, orderId);
        ConsumeResult second = consumeRecordService.consumeReleaseCheck(eventId, orderId);

        assertFalse(first.duplicate());
        assertEquals(ConsumeRecordService.BusinessOutcome.SUCCESS, first.outcome());
        assertTrue(second.duplicate(), "第二次必须命中去重表并跳过");
        assertNull(second.outcome(), "重复投递不得再执行业务");

        WorkOrder after = workOrderMapper.selectById(orderId);
        assertEquals("RELEASED", after.getStatus());
        assertEquals(2, after.getVersion().intValue(), "业务只执行一次：version 只能 +1（1 → 2）");
        assertEquals(1, countConsume(eventId), "去重记录恰好 1 行");
    }

    @Test
    @DisplayName("【灵魂】业务失败 → 去重记录一起回滚：两张表都不留痕")
    void businessFailure_rollsBackConsumeRecord() {
        Long orderId = insertAcceptedOrder();
        String eventId = "order:" + orderId + ":v1:ORDER_RELEASE_CHECK";
        doThrow(new RuntimeException("模拟业务失败")).when(workOrderService).releaseOrder(anyLong());

        assertThrows(RuntimeException.class,
                () -> consumeRecordService.consumeReleaseCheck(eventId, orderId));

        assertEquals(0, countConsume(eventId),
                "业务失败时去重记录必须一起回滚（否则重试被永久跳过＝消息被静默吃掉）");
        WorkOrder after = workOrderMapper.selectById(orderId);
        assertEquals("ACCEPTED", after.getStatus(), "业务也必须回滚");
        assertEquals(1, after.getVersion().intValue(), "version 不变");
    }

    @Test
    @DisplayName("回滚后可重试：同一条事件第二次消费能正常执行（没被去重表永久拒之门外）")
    void afterRollback_retryWorks() {
        Long orderId = insertAcceptedOrder();
        String eventId = "order:" + orderId + ":v1:ORDER_RELEASE_CHECK";
        doThrow(new RuntimeException("第一次失败")).when(workOrderService).releaseOrder(anyLong());
        assertThrows(RuntimeException.class,
                () -> consumeRecordService.consumeReleaseCheck(eventId, orderId));

        Mockito.reset(workOrderService);   // 模拟故障恢复
        ConsumeResult retry = consumeRecordService.consumeReleaseCheck(eventId, orderId);

        assertFalse(retry.duplicate(), "回滚后不该把这条事件当成'已消费'");
        assertEquals(ConsumeRecordService.BusinessOutcome.SUCCESS, retry.outcome());
        assertEquals("RELEASED", workOrderMapper.selectById(orderId).getStatus());
    }

    @Test
    @DisplayName("不同 eventId（同一工单两次合法接单）→ 两条都被消费；第二次靠状态守卫判 SKIPPED")
    void differentEventIds_bothConsumed() {
        Long orderId = insertAcceptedOrder();
        String v1 = "order:" + orderId + ":v1:ORDER_RELEASE_CHECK";
        String v2 = "order:" + orderId + ":v2:ORDER_RELEASE_CHECK";

        ConsumeResult r1 = consumeRecordService.consumeReleaseCheck(v1, orderId);
        ConsumeResult r2 = consumeRecordService.consumeReleaseCheck(v2, orderId);

        assertEquals(ConsumeRecordService.BusinessOutcome.SUCCESS, r1.outcome());
        assertFalse(r2.duplicate(), "不同 version 是两次合法事件，不能被去重表当成重复");
        assertEquals(ConsumeRecordService.BusinessOutcome.SKIPPED, r2.outcome(),
                "第二次由状态守卫判定：单子已经 RELEASED，不再释放");
        assertEquals(1, countConsume(v1));
        assertEquals(1, countConsume(v2), "两条事件各留一条去重记录");
    }

    @Test
    @DisplayName("缺 eventId：不写去重记录，但业务照常执行（只剩状态守卫这道防线）")
    void missingEventId_skipsDedupeButStillReleases() {
        Long orderId = insertAcceptedOrder();

        ConsumeResult result = consumeRecordService.consumeReleaseCheck(null, orderId);

        assertFalse(result.duplicate());
        assertEquals(ConsumeRecordService.BusinessOutcome.SUCCESS, result.outcome());
        assertEquals(0, consumeRecordMapper.selectCount(new LambdaQueryWrapper<ConsumeRecord>()
                        .gt(ConsumeRecord::getId, consumeWatermark)),
                "没有 eventId 就无法去重，不该写记录");
    }

    // ────────────── helpers ──────────────

    private Long insertAcceptedOrder() {
        WorkOrder[] holder = new WorkOrder[1];
        transactionTemplate.execute(tx -> {
            WorkOrder o = new WorkOrder();
            o.setOrderNo("TST-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18));
            o.setTitle("消费幂等测试");
            o.setContent("x");
            o.setType("NETWORK");
            o.setPriority(0);
            o.setStatus("ACCEPTED");
            o.setSubmitterId(1L);
            o.setAssigneeId(127L);
            o.setRejectCount(0);
            o.setMaxReject(3);
            o.setVersion(1);
            o.setCreatedAt(LocalDateTime.now());
            o.setUpdatedAt(LocalDateTime.now());
            workOrderMapper.insert(o);
            holder[0] = o;
            return null;
        });
        return holder[0].getId();
    }

    private long countConsume(String eventId) {
        return consumeRecordMapper.selectCount(new LambdaQueryWrapper<ConsumeRecord>()
                .eq(ConsumeRecord::getEventId, eventId));
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
}

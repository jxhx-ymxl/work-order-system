package com.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.entity.EventOutbox;
import com.workorder.entity.SlaConfig;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.EventOutboxMapper;
import com.workorder.mapper.SlaConfigMapper;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 步骤 3 收口修正的回归测试：**兜底配置也缺失时，接单成功但 outbox 零记录**。
 *
 * <p>为什么必须有这条测试：修正前的实现把"无法解析的接单时限"取 0 → `deliver_at = now`
 * → 记录立即被投递 → 刚接的单在下一轮比对里被**立刻释放**（处理人视角是"抢到的单莫名消失"）。
 * 修正后的正确行为是：**不写 outbox**，让 `ReleaseTimeoutScheduler` 走老路径兜底释放，
 * 即退化成 P1 之前的样子——既不阻断接单，也不进入没人验证过的新分支。
 *
 * <p>构造方式：删掉兜底组合 `OTHER/0` 的配置行，再用一张 `type=OTHER, priority=0` 的工单接单——
 * 主查询与兜底查询会命中同一张缺失的配置，正好走"连兜底都缺"的分支。
 * 测试库的配置行用完即还原（`@AfterEach`），避免污染其他用例。
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxMissingSlaConfigTest {

    @Autowired private WorkOrderService workOrderService;
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private EventOutboxMapper eventOutboxMapper;
    @Autowired private SlaConfigMapper slaConfigMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final String FALLBACK_TYPE = "OTHER";
    private static final int FALLBACK_PRIORITY = 0;

    private Long orderId;
    private Long handlerId;
    private Long orderWatermark;
    private Long outboxWatermark;
    private Long userWatermark;
    private SlaConfig removedFallback;

    @BeforeEach
    void setUp() {
        orderWatermark = maxOrderId();
        outboxWatermark = maxOutboxId();
        userWatermark = maxUserId();

        // 前提：兜底组合必须存在，否则本用例测不到"连兜底都缺"的分支
        removedFallback = slaConfigMapper.selectOne(new LambdaQueryWrapper<SlaConfig>()
                .eq(SlaConfig::getType, FALLBACK_TYPE)
                .eq(SlaConfig::getPriority, FALLBACK_PRIORITY));
        assertNotNull(removedFallback, "测试前提不成立：兜底组合 OTHER/0 在配置表里不存在");
        slaConfigMapper.deleteById(removedFallback.getId());

        User handler = new User();
        handler.setUsername("missing-sla-test-handler-" + System.nanoTime());
        handler.setPassword("x");
        handler.setStatus(1);
        userMapper.insert(handler);
        handlerId = handler.getId();

        transactionTemplate.execute(status -> {
            WorkOrder o = new WorkOrder();
            o.setOrderNo("TST-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18));
            o.setTitle("缺 SLA 配置时的接单行为");
            o.setContent("x");
            o.setType(FALLBACK_TYPE);
            o.setPriority(FALLBACK_PRIORITY);
            o.setStatus("PENDING");
            o.setSubmitterId(1L);
            o.setRejectCount(0);
            o.setMaxReject(3);
            o.setVersion(0);
            o.setCreatedAt(LocalDateTime.now());
            o.setUpdatedAt(LocalDateTime.now());
            workOrderMapper.insert(o);
            orderId = o.getId();
            return null;
        });
    }

    @AfterEach
    void cleanUp() {
        if (removedFallback != null) {
            slaConfigMapper.insert(removedFallback); // 还原配置行，避免污染其他用例
            removedFallback = null;
        }
        eventOutboxMapper.delete(new LambdaQueryWrapper<EventOutbox>().gt(EventOutbox::getId, outboxWatermark));
        workOrderMapper.delete(new LambdaQueryWrapper<WorkOrder>().gt(WorkOrder::getId, orderWatermark));
        userMapper.delete(new LambdaQueryWrapper<User>().gt(User::getId, userWatermark));
    }

    @Test
    @DisplayName("兜底配置也缺失：接单成功（不阻断业务），且 outbox 零记录（不写 deliver_at=now 的记录）")
    void missingFallbackConfig_acceptSucceedsButNoOutboxRow() {
        workOrderService.acceptOrder(orderId, handlerId);

        WorkOrder after = workOrderMapper.selectById(orderId);
        assertEquals("ACCEPTED", after.getStatus(),
                "缺配置不能阻断接单——接单本身必须成功");
        assertEquals(handlerId, after.getAssigneeId(), "接单人应被写入");

        assertEquals(0, eventOutboxMapper.selectCount(new LambdaQueryWrapper<EventOutbox>()
                        .eq(EventOutbox::getAggregateId, orderId)),
                "缺配置时不得写 outbox：写一条 deliver_at=now 的记录会让刚接的单立刻被释放");
    }

    @Test
    @DisplayName("对照：还原兜底配置后，同一条路径会正常写 outbox（证明上一条的'零记录'来自缺配置，而不是别的故障）")
    void restoringFallbackConfig_writesOutboxAgain() {
        slaConfigMapper.insert(removedFallback);
        removedFallback = null; // 已插回，@AfterEach 不必再插

        workOrderService.acceptOrder(orderId, handlerId);

        assertEquals(1, eventOutboxMapper.selectCount(new LambdaQueryWrapper<EventOutbox>()
                        .eq(EventOutbox::getAggregateId, orderId)),
                "兜底配置存在时应恰好写一条 outbox 记录");
    }

    // ────────────── helpers ──────────────

    private Long maxOrderId() {
        WorkOrder o = workOrderMapper.selectOne(new LambdaQueryWrapper<WorkOrder>()
                .select(WorkOrder::getId).orderByDesc(WorkOrder::getId).last("LIMIT 1"));
        return o == null || o.getId() == null ? 0L : o.getId();
    }

    private Long maxOutboxId() {
        EventOutbox o = eventOutboxMapper.selectOne(new LambdaQueryWrapper<EventOutbox>()
                .select(EventOutbox::getId).orderByDesc(EventOutbox::getId).last("LIMIT 1"));
        return o == null || o.getId() == null ? 0L : o.getId();
    }

    private Long maxUserId() {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .select(User::getId).orderByDesc(User::getId).last("LIMIT 1"));
        return u == null || u.getId() == null ? 0L : u.getId();
    }
}

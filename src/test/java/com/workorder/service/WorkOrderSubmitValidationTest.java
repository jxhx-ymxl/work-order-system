package com.workorder.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.workorder.common.BizException;
import com.workorder.common.dto.SubmitOrderReq;
import com.workorder.common.dto.TriageResult;
import com.workorder.entity.SlaConfig;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.*;
import com.workorder.service.impl.WorkOrderServiceImpl;
import com.workorder.utils.OrderNoGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@code submitOrder} 的入口校验与 SLA 兜底（P0a 的 B1/B2）。
 *
 * <p>为什么用纯 Mockito 而不是 @SpringBootTest：这里要验证的都是**分支**——非法类型被拒绝、
 * 配置缺失走兜底、兜底也缺失时不静默。这些分支在真实库上很难稳定触发（尤其 P0b 完成类型替换后，
 * 合法类型都有配置），用 mock 让 mapper 返回 null 才能长期稳定地覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderSubmitValidationTest {

    @Mock private WorkOrderMapper workOrderMapper;
    @Mock private SlaConfigMapper slaConfigMapper;
    @Mock private WorkOrderLogService workOrderLogService;
    @Mock private OrderNoGenerator orderNoGenerator;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private RoleMapper roleMapper;
    @Mock private UserMapper userMapper;
    @Mock private StateMachineValidator stateMachineValidator;
    @Mock private MessagePublishService messagePublishService;
    @Mock private NotificationService notificationService;
    @Mock private OrderTriageService orderTriageService;
    @Mock private StringRedisTemplate redisTemplate;

    @InjectMocks
    private WorkOrderServiceImpl workOrderService;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        // 用 lenient：并非每个用例都会走到这两处（例如非法类型在插入前就被拒绝）
        lenient().when(orderNoGenerator.next()).thenReturn("WO-20260923-00001");
        // insert 时回填主键，模拟 MyBatis-Plus 的自增回填
        lenient().when(workOrderMapper.insert(any(WorkOrder.class))).thenAnswer(inv -> {
            inv.getArgument(0, WorkOrder.class).setId(999L);
            return 1;
        });

        serviceLogger = (Logger) LoggerFactory.getLogger(WorkOrderServiceImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
    }

    private SubmitOrderReq req(String type, Integer priority) {
        SubmitOrderReq r = new SubmitOrderReq();
        r.setTitle("测试标题");
        r.setContent("测试内容");
        r.setType(type);
        r.setPriority(priority);
        return r;
    }

    private SlaConfig config(String type, int priority, int finishMinutes) {
        SlaConfig c = new SlaConfig();
        c.setType(type);
        c.setPriority(priority);
        c.setAcceptMinutes(30);
        c.setFinishMinutes(finishMinutes);
        return c;
    }

    // ─────────────── B1：入口校验 ───────────────

    @Test
    @DisplayName("B1：非空但非法的 type 被拒绝，且不查询 SLA 配置")
    void submitOrder_illegalType_rejected() {
        BizException ex = assertThrows(BizException.class,
                () -> workOrderService.submitOrder(req("REPAIR", 0), 1L));
        assertTrue(ex.getMessage().contains("非法的工单类型"), "异常信息应指出非法类型：" + ex.getMessage());
        verify(slaConfigMapper, never()).selectOne(any(Wrapper.class));
        verify(workOrderMapper, never()).insert(any(WorkOrder.class));
    }

    @Test
    @DisplayName("B1：非法的 priority 被拒绝")
    void submitOrder_illegalPriority_rejected() {
        BizException ex = assertThrows(BizException.class,
                () -> workOrderService.submitOrder(req("NETWORK", 2), 1L));
        assertTrue(ex.getMessage().contains("非法的优先级"), "异常信息应指出非法优先级：" + ex.getMessage());
        verify(workOrderMapper, never()).insert(any(WorkOrder.class));
    }

    @Test
    @DisplayName("B1：type 为空是合法输入，仍走 triage")
    void submitOrder_blankType_usesTriage() {
        when(orderTriageService.triage(any(), any())).thenReturn(new TriageResult("DORM", 1));
        when(slaConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config("DORM", 1, 60));

        // type 传空串、priority 传 null —— 两者都缺失时才应完全采用 triage 的建议值
        WorkOrder order = workOrderService.submitOrder(req("", null), 1L);

        verify(orderTriageService, times(1)).triage(any(), any());
        assertEquals("DORM", order.getType(), "type 为空时应采用 triage 的建议类型");
        assertEquals(1, order.getPriority(), "priority 为空时应采用 triage 的建议优先级");
        assertNotNull(order.getSlaDeadline());
    }

    @Test
    @DisplayName("B1/F1-4：triage 返回非法类型时回落 OTHER，而不是把非法值写库")
    void submitOrder_triageReturnsIllegalType_fallsBackToOther() {
        when(orderTriageService.triage(any(), any())).thenReturn(new TriageResult("REPAIR", 0));
        when(slaConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config("OTHER", 0, 480));

        WorkOrder order = workOrderService.submitOrder(req(null, null), 1L);

        assertEquals("OTHER", order.getType());
        assertTrue(logAppender.list.stream()
                        .anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("回落")),
                "triage 返回非法值时应记 WARN 日志");
    }

    // ─────────────── B2：兜底配置 ───────────────

    @Test
    @DisplayName("B2：配置缺失时使用 OTHER/0 的 finish_minutes 兜底（不硬编码）")
    void submitOrder_configMissing_usesFallbackMinutesFromConfigTable() {
        when(slaConfigMapper.selectOne(any(Wrapper.class)))
                .thenReturn(null)                      // 第一次：查 NETWORK/0 → 缺失
                .thenReturn(config("OTHER", 0, 480));  // 第二次：兜底 OTHER/0

        LocalDateTime before = LocalDateTime.now();
        WorkOrder order = workOrderService.submitOrder(req("NETWORK", 0), 1L);
        LocalDateTime after = LocalDateTime.now();

        assertNotNull(order.getSlaDeadline(), "配置缺失时必须走兜底，不得为 null");
        assertFalse(order.getSlaDeadline().isBefore(before.plusMinutes(480)));
        assertFalse(order.getSlaDeadline().isAfter(after.plusMinutes(480)));

        ILoggingEvent warn = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .findFirst()
                .orElse(null);
        assertNotNull(warn, "触发兜底时必须记 WARN 日志");
        assertTrue(warn.getFormattedMessage().contains("WO-20260923-00001"), "WARN 日志应含 orderNo");
        assertTrue(warn.getFormattedMessage().contains("NETWORK"), "WARN 日志应含 type");
    }

    @Test
    @DisplayName("B2：连兜底配置都不存在时记 ERROR 且不静默（deadline 保持 null）")
    void submitOrder_fallbackAlsoMissing_logsErrorNotSilent() {
        when(slaConfigMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        WorkOrder order = workOrderService.submitOrder(req("NETWORK", 0), 1L);

        assertNull(order.getSlaDeadline(), "兜底也缺失时保持当前行为（null），但必须可见");
        assertTrue(logAppender.list.stream()
                        .anyMatch(e -> e.getLevel() == Level.ERROR
                                && e.getFormattedMessage().contains("sla_deadline 将为 NULL")),
                "必须记 ERROR 日志，不得静默");
    }

    @Test
    @DisplayName("B2：正常路径（配置存在）不触发兜底、不产生 WARN")
    void submitOrder_configPresent_noFallbackWarning() {
        when(slaConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config("UTILITY", 1, 120));

        WorkOrder order = workOrderService.submitOrder(req("UTILITY", 1), 1L);

        assertNotNull(order.getSlaDeadline());
        assertTrue(logAppender.list.stream().noneMatch(e -> e.getLevel() == Level.WARN),
                "配置命中时不应产生兜底 WARN");
        verify(slaConfigMapper, times(1)).selectOne(any(Wrapper.class));
    }
}

package com.workorder.scheduler;

import com.workorder.common.enums.ReleaseResult;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.WorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 兜底扫描适配三态后的**行为不变性**测试（P1 步骤 4 的重点危险区）。
 *
 * <p>改造前它靠"catch Exception 后继续"保证"一张单失败不影响整批"；改造后 releaseOrder 不再抛异常
 * 而是返回三态，于是"继续扫下一张"这件事必须重新证明一次——不能因为返回值不再抛异常，
 * 就悄悄变成"遇到 SKIPPED/ERROR 就 break"。
 */
class ReleaseTimeoutSchedulerTest {

    private WorkOrderMapper workOrderMapper;
    private WorkOrderService workOrderService;
    private ReleaseTimeoutScheduler scheduler;

    @BeforeEach
    void setUp() {
        workOrderMapper = mock(WorkOrderMapper.class);
        workOrderService = mock(WorkOrderService.class);
        scheduler = new ReleaseTimeoutScheduler(workOrderMapper, workOrderService);
    }

    @Test
    @DisplayName("三态与异常混合时：每一张都被尝试过，且异常不逃出扫描方法")
    void processesEveryOrderRegardlessOfResult() {
        when(workOrderMapper.findAcceptTimeoutOrders(anyInt())).thenReturn(List.of(
                order(1L), order(2L), order(3L), order(4L)));
        when(workOrderMapper.findAcceptedOrdersWithoutSlaConfig(anyInt())).thenReturn(List.of());
        when(workOrderService.releaseOrder(1L)).thenReturn(ReleaseResult.RELEASED);
        when(workOrderService.releaseOrder(2L)).thenReturn(ReleaseResult.SKIPPED);
        when(workOrderService.releaseOrder(3L)).thenReturn(ReleaseResult.ERROR);
        when(workOrderService.releaseOrder(4L)).thenThrow(new RuntimeException("DB 故障"));

        assertDoesNotThrow(scheduler::scanAndReleaseTimeout);

        verify(workOrderService).releaseOrder(1L);
        verify(workOrderService).releaseOrder(2L);
        verify(workOrderService).releaseOrder(3L);
        verify(workOrderService).releaseOrder(4L);
        verify(workOrderService, times(4)).releaseOrder(anyLong());
    }

    @Test
    @DisplayName("时限来自配置：扫描用的是 findAcceptTimeoutOrders（带 JOIN 配置表），不再用硬编码阈值查库")
    void TimeoutComesFromConfigQuery() {
        when(workOrderMapper.findAcceptTimeoutOrders(anyInt())).thenReturn(List.of());
        when(workOrderMapper.findAcceptedOrdersWithoutSlaConfig(anyInt())).thenReturn(List.of());

        scheduler.scanAndReleaseTimeout();

        verify(workOrderMapper).findAcceptTimeoutOrders(anyInt());
        // 旧的实现是 workOrderMapper.selectList(带 updated_at<=now-30min 的 wrapper)；它必须不再被调用
        verify(workOrderMapper, never()).selectList(any());
        verify(workOrderService, never()).releaseOrder(anyLong());
    }

    @Test
    @DisplayName("配置缺失：不释放（不动作）但必须查出来留痕，且不影响扫描方法返回")
    void missingConfigIsDetectedNotReleased() {
        when(workOrderMapper.findAcceptTimeoutOrders(anyInt())).thenReturn(List.of());
        when(workOrderMapper.findAcceptedOrdersWithoutSlaConfig(anyInt())).thenReturn(List.of(77L, 78L));

        assertDoesNotThrow(scheduler::scanAndReleaseTimeout);

        verify(workOrderService, never()).releaseOrder(anyLong());
        verify(workOrderMapper).findAcceptedOrdersWithoutSlaConfig(anyInt());
    }

    private WorkOrder order(Long id) {
        WorkOrder o = new WorkOrder();
        o.setId(id);
        o.setOrderNo("WO-TEST-" + id);
        o.setStatus("ACCEPTED");
        o.setUpdatedAt(LocalDateTime.now().minusHours(1));
        return o;
    }
}

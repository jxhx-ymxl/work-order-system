package com.workorder.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.entity.WorkOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 步骤 5 的核心验证：**兜底释放扫描的时限确实来自 {@code t_sla_config.accept_minutes}**
 * （按每张工单自己的 type+priority），而不是某个全局硬编码值。
 *
 * <p>为什么必须用真库验：这条 SQL 的语义是"每行用自己那一行的配置算时限"，
 * Mockito 版本只能证明"调用了这个方法"（那由 {@code ReleaseTimeoutSchedulerTest} 覆盖），
 * 证明不了"10 分钟档的工单在 11 分钟时就被选中，而 30 分钟档的工单在 29 分钟时不被选中"。
 *
 * <p>测试库的配置基准（`sql/init.sql`）：NETWORK/0 = 30 分钟、NETWORK/1 = 10 分钟。
 */
@SpringBootTest
@ActiveProfiles("test")
class AcceptTimeoutFromConfigTest {

    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long watermark;

    @BeforeEach
    void setUp() {
        WorkOrder last = workOrderMapper.selectOne(new LambdaQueryWrapper<WorkOrder>()
                .select(WorkOrder::getId).orderByDesc(WorkOrder::getId).last("LIMIT 1"));
        watermark = last == null || last.getId() == null ? 0L : last.getId();
    }

    @AfterEach
    void cleanUp() {
        workOrderMapper.delete(new LambdaQueryWrapper<WorkOrder>().gt(WorkOrder::getId, watermark));
    }

    @Test
    @DisplayName("时限按 type+priority 各自取值：10 分钟档 11 分钟 → 选中；30 分钟档 29 分钟 → 不选中")
    void timeoutUsesPerConfigAcceptMinutes() {
        Long net0Overdue = insertAccepted("NETWORK", 0, 31);  // 配置 30 → 已超时
        Long net0Fresh = insertAccepted("NETWORK", 0, 29);    // 配置 30 → 未超时
        Long net1Overdue = insertAccepted("NETWORK", 1, 11);  // 配置 10 → 已超时（若按硬编码 30 就不会被选中）

        Set<Long> candidates = workOrderMapper.findAcceptTimeoutOrders(500).stream()
                .map(WorkOrder::getId).collect(Collectors.toSet());

        assertTrue(candidates.contains(net0Overdue), "配置 30 分钟、已过 31 分钟：应被兜底扫描选中");
        assertFalse(candidates.contains(net0Fresh), "配置 30 分钟、只过 29 分钟：不该被选中（否则就是没用配置）");
        assertTrue(candidates.contains(net1Overdue),
                "配置 10 分钟、已过 11 分钟：应被选中——这条把'硬编码 30 分钟'钉死了");
    }

    @Test
    @DisplayName("查不到配置的 ACCEPTED 工单：不进释放候选，但会被缺失检测查出来")
    void missingConfigOrderIsDetectedButNotReleased() {
        Long orphan = insertAccepted("NO_SUCH_TYPE", 0, 999);

        Set<Long> candidates = workOrderMapper.findAcceptTimeoutOrders(500).stream()
                .map(WorkOrder::getId).collect(Collectors.toSet());
        List<Long> missing = workOrderMapper.findAcceptedOrdersWithoutSlaConfig(500);

        assertFalse(candidates.contains(orphan), "没有配置的工单不得被释放（不发明默认时限）");
        assertTrue(missing.contains(orphan), "没有配置的工单必须被检测出来记 ERROR，否则表现为'永远挂着'");
    }

    // ────────────── helpers ──────────────

    private Long insertAccepted(String type, int priority, int minutesAgo) {
        WorkOrder[] holder = new WorkOrder[1];
        transactionTemplate.execute(tx -> {
            WorkOrder o = new WorkOrder();
            o.setOrderNo("TST-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18));
            o.setTitle("accept_minutes 归一验证");
            o.setContent("x");
            o.setType(type);
            o.setPriority(priority);
            o.setStatus("ACCEPTED");
            o.setSubmitterId(1L);
            o.setAssigneeId(127L);
            o.setRejectCount(0);
            o.setMaxReject(3);
            o.setVersion(1);
            o.setCreatedAt(LocalDateTime.now().minusMinutes(minutesAgo));
            o.setUpdatedAt(LocalDateTime.now().minusMinutes(minutesAgo));
            workOrderMapper.insert(o);
            holder[0] = o;
            return null;
        });
        return holder[0].getId();
    }
}

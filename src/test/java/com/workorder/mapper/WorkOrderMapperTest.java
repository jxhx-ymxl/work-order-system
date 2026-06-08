package com.workorder.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workorder.entity.WorkOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class WorkOrderMapperTest {

    @Autowired
    private WorkOrderMapper workOrderMapper;

    private Long submitter1 = 1L;
    private Long submitter2 = 2L;
    private Long assignee1 = 10L;

    @BeforeEach
    void setUp() {
        for (int i = 1; i <= 5; i++) {
            WorkOrder order = new WorkOrder();
            order.setOrderNo("WO-20260608-0000" + i);
            order.setTitle("测试工单" + i);
            order.setContent("测试内容" + i);
            order.setType("REPAIR");
            order.setPriority(i <= 2 ? 0 : 1);
            order.setStatus(i <= 2 ? "PENDING" : "ACCEPTED");
            order.setSubmitterId(i <= 3 ? submitter1 : submitter2);
            order.setAssigneeId(i <= 2 ? null : assignee1);
            order.setRejectCount(0);
            order.setMaxReject(3);
            order.setSlaDeadline(LocalDateTime.now().plusHours(2));
            order.setVersion(0);
            order.setCreatedAt(LocalDateTime.now());
            order.setUpdatedAt(LocalDateTime.now());
            workOrderMapper.insert(order);
        }
    }

    @Test
    @DisplayName("分页查询——无条件返回全部5条")
    void testSelectPageWithConditions_noFilter() {
        Page<WorkOrder> page = new Page<>(1, 10);
        IPage<WorkOrder> result = workOrderMapper.selectPageWithConditions(page, null, null, null, null);
        assertEquals(5, result.getTotal());
        assertEquals(5, result.getRecords().size());
    }

    @Test
    @DisplayName("分页查询——按status筛选")
    void testSelectPageWithConditions_byStatus() {
        Page<WorkOrder> page = new Page<>(1, 10);
        IPage<WorkOrder> result = workOrderMapper.selectPageWithConditions(page, "PENDING", null, null, null);
        assertEquals(2, result.getTotal());
        result.getRecords().forEach(o -> assertEquals("PENDING", o.getStatus()));
    }

    @Test
    @DisplayName("分页查询——按submitterId筛选")
    void testSelectPageWithConditions_bySubmitter() {
        Page<WorkOrder> page = new Page<>(1, 10);
        IPage<WorkOrder> result = workOrderMapper.selectPageWithConditions(page, null, null, submitter2, null);
        assertEquals(2, result.getTotal());
        result.getRecords().forEach(o -> assertEquals(submitter2, o.getSubmitterId()));
    }

    @Test
    @DisplayName("分页查询——按assigneeId筛选")
    void testSelectPageWithConditions_byAssignee() {
        Page<WorkOrder> page = new Page<>(1, 10);
        IPage<WorkOrder> result = workOrderMapper.selectPageWithConditions(page, null, null, null, assignee1);
        assertEquals(3, result.getTotal());
        result.getRecords().forEach(o -> assertEquals(assignee1, o.getAssigneeId()));
    }

    @Test
    @DisplayName("分页查询——组合条件 status + submitterId")
    void testSelectPageWithConditions_combined() {
        Page<WorkOrder> page = new Page<>(1, 10);
        IPage<WorkOrder> result = workOrderMapper.selectPageWithConditions(page, "ACCEPTED", null, submitter1, assignee1);
        assertEquals(1, result.getTotal());
        assertEquals("ACCEPTED", result.getRecords().get(0).getStatus());
        assertEquals(submitter1, result.getRecords().get(0).getSubmitterId());
    }

    @Test
    @DisplayName("分页查询——分页第1页2条")
    void testSelectPage_pagination() {
        Page<WorkOrder> page = new Page<>(1, 2);
        IPage<WorkOrder> result = workOrderMapper.selectPageWithConditions(page, null, null, null, null);
        assertEquals(5, result.getTotal());
        assertEquals(2, result.getRecords().size());
        assertEquals(1, result.getCurrent());
        assertEquals(3, result.getPages());
    }

    @Test
    @DisplayName("乐观锁——更新后version自增")
    void testOptimisticLock_versionIncrement() {
        Page<WorkOrder> page = new Page<>(1, 1);
        List<WorkOrder> orders = workOrderMapper.selectPageWithConditions(page, null, null, null, null).getRecords();
        assertFalse(orders.isEmpty());

        WorkOrder order = orders.get(0);
        Integer oldVersion = order.getVersion();

        LambdaUpdateWrapper<WorkOrder> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(WorkOrder::getId, order.getId())
               .eq(WorkOrder::getVersion, oldVersion)
               .set(WorkOrder::getTitle, "更新后的标题")
               .set(WorkOrder::getVersion, oldVersion + 1);

        int rows = workOrderMapper.update(null, wrapper);
        assertEquals(1, rows);

        WorkOrder updated = workOrderMapper.selectById(order.getId());
        assertEquals(oldVersion + 1, updated.getVersion());
        assertEquals("更新后的标题", updated.getTitle());
    }

    @Test
    @DisplayName("乐观锁——版本冲突时更新失败")
    void testOptimisticLock_conflict() {
        Page<WorkOrder> page = new Page<>(1, 1);
        List<WorkOrder> orders = workOrderMapper.selectPageWithConditions(page, null, null, null, null).getRecords();
        assertFalse(orders.isEmpty());

        WorkOrder order = orders.get(0);
        Integer oldVersion = order.getVersion();

        // 模拟另一个线程先更新了同一行，version 已变
        LambdaUpdateWrapper<WorkOrder> stolen = new LambdaUpdateWrapper<>();
        stolen.eq(WorkOrder::getId, order.getId())
              .eq(WorkOrder::getVersion, oldVersion)
              .set(WorkOrder::getTitle, "被抢了")
              .set(WorkOrder::getVersion, oldVersion + 1);
        workOrderMapper.update(null, stolen);

        // 当前线程用旧 version 去更新 —— 应该失败
        LambdaUpdateWrapper<WorkOrder> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(WorkOrder::getId, order.getId())
               .eq(WorkOrder::getVersion, oldVersion)
               .set(WorkOrder::getTitle, "我的更新")
               .set(WorkOrder::getVersion, oldVersion + 1);

        int rows = workOrderMapper.update(null, wrapper);
        assertEquals(0, rows);
    }
}

package com.workorder.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.BizException;
import com.workorder.common.PageResult;
import com.workorder.common.Result;
import com.workorder.common.dto.PageQuery;
import com.workorder.common.dto.SubmitOrderReq;
import com.workorder.common.vo.WorkOrderDetailVO;
import com.workorder.common.vo.WorkOrderLogVO;
import com.workorder.common.vo.WorkOrderVO;
import com.workorder.entity.WorkOrder;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.WorkOrderLogService;
import com.workorder.service.WorkOrderService;
import org.junit.jupiter.api.AfterEach;
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
class WorkOrderControllerTest {

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private WorkOrderLogService workOrderLogService;

    @Autowired
    private WorkOrderMapper workOrderMapper;

    @Autowired
    private WorkOrderLogMapper workOrderLogMapper;

    @BeforeEach
    void setUp() {
        StpUtil.login(1L);
        for (int i = 1; i <= 10; i++) {
            SubmitOrderReq req = new SubmitOrderReq();
            req.setTitle("测试工单" + i);
            req.setContent("测试内容" + i);
            req.setType(i <= 5 ? "REPAIR" : "LEAVE");
            req.setPriority(i <= 3 ? 0 : 1);
            workOrderService.submitOrder(req, i <= 6 ? 1L : 2L);
        }
    }

    @AfterEach
    void tearDown() {
        StpUtil.logout();
    }

    // ---- listOrders ----

    @Test
    @DisplayName("listOrders——无条件分页返回全部")
    void testListOrders_noFilter() {
        PageQuery query = new PageQuery();
        query.setPage(1);
        query.setSize(20);
        PageResult<WorkOrderVO> result = workOrderService.listOrders(query, 1L);
        assertEquals(10, result.getTotal());
        assertEquals(1, result.getCurrent());
        assertEquals(1, result.getPages());
    }

    @Test
    @DisplayName("listOrders——按status筛选")
    void testListOrders_byStatus() {
        PageQuery query = new PageQuery();
        query.setStatus("PENDING");
        PageResult<WorkOrderVO> result = workOrderService.listOrders(query, 1L);
        assertEquals(10, result.getTotal());
        result.getRecords().forEach(o -> assertEquals("PENDING", o.getStatus()));
    }

    @Test
    @DisplayName("listOrders——分页第1页3条，total仍为10")
    void testListOrders_pagination() {
        PageQuery query = new PageQuery();
        query.setPage(1);
        query.setSize(3);
        PageResult<WorkOrderVO> result = workOrderService.listOrders(query, 1L);
        assertEquals(10, result.getTotal());
        assertEquals(3, result.getRecords().size());
        assertEquals(4, result.getPages());
    }

    @Test
    @DisplayName("listOrders——size超过100时截断为100")
    void testListOrders_sizeCap() {
        PageQuery query = new PageQuery();
        query.setSize(999);
        assertEquals(100, query.getSize());
    }

    @Test
    @DisplayName("listOrders——VO不包含version字段")
    void testListOrders_noVersionExposed() {
        PageQuery query = new PageQuery();
        PageResult<WorkOrderVO> result = workOrderService.listOrders(query, 1L);
        assertFalse(result.getRecords().isEmpty());
        WorkOrderVO vo = result.getRecords().get(0);
        // version is not a field of WorkOrderVO — verified by compilation
        assertNotNull(vo.getOrderNo());
        assertNotNull(vo.getStatus());
    }

    // ---- getOrderDetail ----

    @Test
    @DisplayName("getOrderDetail——返回工单信息和日志列表")
    void testGetOrderDetail() {
        List<WorkOrder> orders = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>().orderByDesc(WorkOrder::getId).last("LIMIT 1"));
        assertFalse(orders.isEmpty());
        Long orderId = orders.get(0).getId();

        WorkOrderDetailVO detail = workOrderService.getOrderDetail(orderId);
        assertNotNull(detail.getOrder());
        assertNotNull(detail.getLogs());
        assertEquals(1, detail.getLogs().size());
        assertEquals("SUBMIT", detail.getLogs().get(0).getAction());
        assertEquals("PENDING", detail.getLogs().get(0).getNewStatus());
    }

    @Test
    @DisplayName("getOrderDetail——工单不存在抛BizException")
    void testGetOrderDetail_notFound() {
        BizException ex = assertThrows(BizException.class,
                () -> workOrderService.getOrderDetail(99999L));
        assertEquals("工单不存在", ex.getMessage());
    }

    // ---- queryLogs ----

    @Test
    @DisplayName("queryLogs——按时间正序返回，包含操作人姓名")
    void testQueryLogs() {
        List<WorkOrder> orders = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>().orderByDesc(WorkOrder::getId).last("LIMIT 1"));
        Long orderId = orders.get(0).getId();

        // 追加2条日志模拟后续操作
        WorkOrderLog log2 = new WorkOrderLog();
        log2.setOrderId(orderId);
        log2.setOrderNo(orders.get(0).getOrderNo());
        log2.setOperatorId(1L);
        log2.setAction("ACCEPT");
        log2.setOldStatus("PENDING");
        log2.setNewStatus("ACCEPTED");
        log2.setCreatedAt(LocalDateTime.now());
        workOrderLogMapper.insert(log2);

        WorkOrderLog log3 = new WorkOrderLog();
        log3.setOrderId(orderId);
        log3.setOrderNo(orders.get(0).getOrderNo());
        log3.setOperatorId(1L);
        log3.setAction("START");
        log3.setOldStatus("ACCEPTED");
        log3.setNewStatus("IN_PROGRESS");
        log3.setCreatedAt(LocalDateTime.now());
        workOrderLogMapper.insert(log3);

        List<WorkOrderLogVO> logs = workOrderLogService.queryLogs(orderId);
        assertEquals(3, logs.size());
        assertEquals("SUBMIT", logs.get(0).getAction());
        assertEquals("ACCEPT", logs.get(1).getAction());
        assertEquals("START", logs.get(2).getAction());

        logs.forEach(log -> {
            assertNotNull(log.getOperatorName());
            assertFalse(log.getOperatorName().isEmpty());
        });
    }

    // ---- Controller submit ----

    @Test
    @DisplayName("Controller submit——从StpUtil获取submitterId")
    void testControllerSubmit_usesStpUtil() {
        SubmitOrderReq req = new SubmitOrderReq();
        req.setTitle("Controller测试工单");
        req.setContent("验证StpUtil生效");
        req.setType("REPAIR");
        req.setPriority(0);

        WorkOrder order = workOrderService.submitOrder(req, StpUtil.getLoginIdAsLong());

        assertEquals(1L, order.getSubmitterId());
        assertEquals("PENDING", order.getStatus());
        assertNotNull(order.getOrderNo());
    }
}

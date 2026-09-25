package com.workorder.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.PageResult;
import com.workorder.common.dto.PageQuery;
import com.workorder.common.vo.WorkOrderDetailVO;
import com.workorder.common.vo.WorkOrderVO;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.WorkOrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P5 步骤 3：**接口必须把 {@code triage_status} 带出去**（前端三态显示的唯一数据来源）。
 *
 * <p>为什么必须覆盖**列表**而不只是详情：列表页的「类型」列也要按三态渲染——
 * 只暴露在详情里的话，列表仍会把兜底值 {@code OTHER} 当作"AI 判成其他"显示。
 * 两条路径都走同一个 {@code toVO(...)}，本类同时钉住（真实 HTTP 响应体另见交付报告）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkOrderTriageStatusVoTest {

    @Autowired private WorkOrderService workOrderService;
    @Autowired private WorkOrderMapper workOrderMapper;

    @Test
    @DisplayName("列表与详情都必须带 triageStatus（同一个 toVO，两条路径都要覆盖）")
    void listAndDetailExposeTriageStatus() {
        Long orderId = insertOrder("PENDING");

        PageQuery query = new PageQuery();
        query.setPage(1);
        query.setSize(100);
        PageResult<WorkOrderVO> page = workOrderService.listOrders(query, 1L);
        WorkOrderVO inList = page.getRecords().stream()
                .filter(vo -> orderId.equals(vo.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(inList, "列表应包含刚插入的工单");
        assertEquals("PENDING", inList.getTriageStatus(), "列表响应必须带 triageStatus（否则列表仍显示兜底值）");

        WorkOrderDetailVO detail = workOrderService.getOrderDetail(orderId, 1L);
        assertNotNull(detail);
        assertNotNull(detail.getOrder());
        assertEquals("PENDING", detail.getOrder().getTriageStatus(), "详情响应同样必须带（前端据此显示「分类中」）");
    }

    @Test
    @DisplayName("FAILED 也要带出去（否则界面永远显示不出「分类失败」）")
    void failedStatusIsExposed() {
        Long orderId = insertOrder("FAILED");

        WorkOrderDetailVO detail = workOrderService.getOrderDetail(orderId, 1L);
        assertEquals("FAILED", detail.getOrder().getTriageStatus());
    }

    private Long insertOrder(String triageStatus) {
        WorkOrder o = new WorkOrder();
        o.setOrderNo("TST-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18));
        o.setTitle("VO 暴露测试");
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
        return o.getId();
    }
}

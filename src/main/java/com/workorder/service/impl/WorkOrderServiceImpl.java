package com.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workorder.common.BizException;
import com.workorder.common.ErrorCode;
import com.workorder.common.PageResult;
import com.workorder.common.dto.PageQuery;
import com.workorder.common.dto.SubmitOrderReq;
import com.workorder.common.vo.StatsVO;
import com.workorder.common.vo.WorkOrderDetailVO;
import com.workorder.common.vo.WorkOrderLogVO;
import com.workorder.common.vo.WorkOrderVO;
import com.workorder.entity.Role;
import com.workorder.entity.SlaConfig;
import com.workorder.entity.User;
import com.workorder.entity.UserRole;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.RoleMapper;
import com.workorder.mapper.SlaConfigMapper;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.UserRoleMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.WorkOrderLogService;
import com.workorder.service.WorkOrderService;
import com.workorder.utils.OrderNoGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkOrderServiceImpl implements WorkOrderService {

    private final WorkOrderMapper workOrderMapper;
    private final SlaConfigMapper slaConfigMapper;
    private final WorkOrderLogService workOrderLogService;
    private final OrderNoGenerator orderNoGenerator;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final UserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder submitOrder(SubmitOrderReq req, Long submitterId) {
        String orderNo = orderNoGenerator.next();

        Integer priority = req.getPriority() != null ? req.getPriority() : 0;
        SlaConfig slaConfig = slaConfigMapper.selectOne(new LambdaQueryWrapper<SlaConfig>()
                .eq(SlaConfig::getType, req.getType())
                .eq(SlaConfig::getPriority, priority));

        LocalDateTime slaDeadline = null;
        if (slaConfig != null && slaConfig.getFinishMinutes() != null) {
            slaDeadline = LocalDateTime.now().plusMinutes(slaConfig.getFinishMinutes());
        }

        WorkOrder order = new WorkOrder();
        order.setOrderNo(orderNo);
        order.setTitle(req.getTitle());
        order.setContent(req.getContent());
        order.setType(req.getType());
        order.setPriority(priority);
        order.setStatus("PENDING");
        order.setSubmitterId(submitterId);
        order.setRejectCount(0);
        order.setMaxReject(3);
        order.setSlaDeadline(slaDeadline);
        order.setVersion(0);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.insert(order);

        workOrderLogService.saveLog(order.getId(), orderNo, submitterId,
                "SUBMIT", null, "PENDING", null);

        return order;
    }

    @Override
    public PageResult<WorkOrderVO> listOrders(PageQuery query, Long currentUserId) {
        Page<WorkOrder> page = new Page<>(query.getPage(), query.getSize());
        Set<String> roles = getRoleCodes(currentUserId);

        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();

        // 用户可选筛选条件
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(WorkOrder::getStatus, query.getStatus());
        }
        if (query.getOrderNo() != null && !query.getOrderNo().isBlank()) {
            wrapper.like(WorkOrder::getOrderNo, query.getOrderNo());
        }
        if (query.getSubmitterId() != null) {
            wrapper.eq(WorkOrder::getSubmitterId, query.getSubmitterId());
        }
        if (query.getAssigneeId() != null) {
            wrapper.eq(WorkOrder::getAssigneeId, query.getAssigneeId());
        }

        // RBAC 角色数据过滤（多角色取最宽松=OR）
        if (!roles.contains("SYS_ADMIN")) {
            wrapper.and(rbac -> applyRoleFilters(rbac, roles, currentUserId));
        }

        wrapper.orderByDesc(WorkOrder::getId);

        IPage<WorkOrder> result = workOrderMapper.selectPage(page, wrapper);

        List<WorkOrderVO> vos = result.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), result.getPages(), result.getCurrent(), vos);
    }

    private void applyRoleFilters(LambdaQueryWrapper<WorkOrder> rbac, Set<String> roles, Long currentUserId) {
        boolean hasFilter = false;

        if (roles.contains("SUBMITTER")) {
            rbac.eq(WorkOrder::getSubmitterId, currentUserId);
            hasFilter = true;
        }
        if (roles.contains("HANDLER")) {
            if (hasFilter) {
                rbac.or(h -> h.eq(WorkOrder::getAssigneeId, currentUserId)
                        .or(h2 -> h2.eq(WorkOrder::getStatus, "PENDING").isNull(WorkOrder::getAssigneeId)));
            } else {
                rbac.eq(WorkOrder::getAssigneeId, currentUserId)
                        .or(h2 -> h2.eq(WorkOrder::getStatus, "PENDING").isNull(WorkOrder::getAssigneeId));
            }
            hasFilter = true;
        }
        if (roles.contains("DEPT_ADMIN")) {
            User user = userMapper.selectById(currentUserId);
            if (user != null && user.getDeptId() != null) {
                List<Long> deptUserIds = userMapper.selectList(
                                new LambdaQueryWrapper<User>().eq(User::getDeptId, user.getDeptId()))
                        .stream().map(User::getId).toList();
                if (!deptUserIds.isEmpty()) {
                    if (hasFilter) {
                        rbac.or().in(WorkOrder::getSubmitterId, deptUserIds);
                    } else {
                        rbac.in(WorkOrder::getSubmitterId, deptUserIds);
                    }
                    hasFilter = true;
                }
            }
        }

        if (!hasFilter) {
            rbac.eq(WorkOrder::getSubmitterId, currentUserId);
        }
    }

    @Override
    public List<StatsVO> getStats(String scope, Long currentUserId) {
        QueryWrapper<WorkOrder> wrapper = new QueryWrapper<>();
        wrapper.select("status", "COUNT(*) as cnt");

        if ("DEPT".equals(scope)) {
            User user = userMapper.selectById(currentUserId);
            if (user == null || user.getDeptId() == null) {
                return Collections.emptyList();
            }
            List<Long> deptUserIds = userMapper.selectList(
                            new LambdaQueryWrapper<User>().eq(User::getDeptId, user.getDeptId()))
                    .stream().map(User::getId).toList();
            if (deptUserIds.isEmpty()) {
                return Collections.emptyList();
            }
            wrapper.in("submitter_id", deptUserIds);
        }

        wrapper.groupBy("status");

        List<Map<String, Object>> maps = workOrderMapper.selectMaps(wrapper);
        List<StatsVO> result = new ArrayList<>();
        for (Map<String, Object> map : maps) {
            StatsVO vo = new StatsVO();
            vo.setStatus((String) map.get("status"));
            Object cnt = map.get("cnt");
            vo.setCount(cnt != null ? Long.valueOf(cnt.toString()) : 0L);
            result.add(vo);
        }
        return result;
    }

    @Override
    public WorkOrderDetailVO getOrderDetail(Long orderId) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工单不存在");
        }

        List<WorkOrderLogVO> logs = workOrderLogService.queryLogs(orderId);

        WorkOrderDetailVO detail = new WorkOrderDetailVO();
        detail.setOrder(toVO(order));
        detail.setLogs(logs);
        return detail;
    }

    private Set<String> getRoleCodes(Long userId) {
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return Collections.emptySet();
        }
        List<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        return roleMapper.selectList(
                        new LambdaQueryWrapper<Role>().in(Role::getId, roleIds))
                .stream().map(Role::getRoleCode).collect(Collectors.toSet());
    }

    private WorkOrderVO toVO(WorkOrder order) {
        WorkOrderVO vo = new WorkOrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setTitle(order.getTitle());
        vo.setContent(order.getContent());
        vo.setType(order.getType());
        vo.setPriority(order.getPriority());
        vo.setStatus(order.getStatus());
        vo.setSubmitterId(order.getSubmitterId());
        vo.setAssigneeId(order.getAssigneeId());
        vo.setRejectCount(order.getRejectCount());
        vo.setMaxReject(order.getMaxReject());
        vo.setSlaDeadline(order.getSlaDeadline());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setUpdatedAt(order.getUpdatedAt());
        return vo;
    }
}

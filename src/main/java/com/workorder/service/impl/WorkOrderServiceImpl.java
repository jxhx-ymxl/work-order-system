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
import com.workorder.common.enums.OrderAction;
import com.workorder.common.enums.Status;
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
import com.workorder.service.MessagePublishService;
import com.workorder.service.NotificationService;
import com.workorder.service.StateMachineValidator;
import com.workorder.service.WorkOrderLogService;
import com.workorder.service.WorkOrderService;
import com.workorder.utils.OrderNoGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private final StateMachineValidator stateMachineValidator;
    private final MessagePublishService messagePublishService;
    private final NotificationService notificationService;
    private final StringRedisTemplate redisTemplate;

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

    // ───────────────────── Issue #29: 抢单 ─────────────────────

    @Override
    @com.workorder.common.aop.OrderAction(action = "ACCEPT")
    @Transactional(rollbackFor = Exception.class)
    public void acceptOrder(Long orderId, Long userId) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工单不存在");
        }

        stateMachineValidator.validate(Status.valueOf(order.getStatus()), OrderAction.ACCEPT);

        int rows = workOrderMapper.grabOrder(orderId, userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "工单已被抢走");
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        redisTemplate.opsForValue().set(
                                "order:accept_timeout:" + orderId,
                                userId.toString(),
                                Duration.ofMinutes(30));
                        messagePublishService.sendReleaseCheck(orderId);
                    }
                });
    }

    // ───────────────────── Issue #30: 开始处理 ─────────────────────

    @Override
    @com.workorder.common.aop.OrderAction(action = "START")
    @Transactional(rollbackFor = Exception.class)
    public void startOrder(Long orderId, Long operatorId) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工单不存在");
        }
        if (!operatorId.equals(order.getAssigneeId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅当前处理人可操作");
        }

        stateMachineValidator.validate(Status.valueOf(order.getStatus()), OrderAction.START);

        int rows = workOrderMapper.updateStatus(orderId, "ACCEPTED", "IN_PROGRESS", order.getVersion());
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "状态已变更，请刷新重试");
        }

        redisTemplate.delete("order:accept_timeout:" + orderId);
    }

    // ───────────────────── Issue #30: 提交验收 ─────────────────────

    @Override
    @com.workorder.common.aop.OrderAction(action = "COMPLETE")
    @Transactional(rollbackFor = Exception.class)
    public void completeOrder(Long orderId, Long operatorId) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工单不存在");
        }
        if (!operatorId.equals(order.getAssigneeId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅当前处理人可操作");
        }

        stateMachineValidator.validate(Status.valueOf(order.getStatus()), OrderAction.COMPLETE);

        int rows = workOrderMapper.updateStatus(orderId, "IN_PROGRESS", "AWAIT_APPROVAL", order.getVersion());
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "状态已变更，请刷新重试");
        }
    }

    // ───────────────────── Issue #31: 验收通过 ─────────────────────

    @Override
    @com.workorder.common.aop.OrderAction(action = "APPROVE")
    @Transactional(rollbackFor = Exception.class)
    public void approveOrder(Long orderId, Long operatorId) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工单不存在");
        }
        if (!operatorId.equals(order.getSubmitterId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅提交人可验收");
        }

        stateMachineValidator.validate(Status.valueOf(order.getStatus()), OrderAction.APPROVE);

        int rows = workOrderMapper.updateStatus(orderId, "AWAIT_APPROVAL", "CLOSED", order.getVersion());
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "状态已变更，请刷新重试");
        }
    }

    // ───────────────────── Issue #31: 验收驳回 ─────────────────────

    @Override
    @com.workorder.common.aop.OrderAction(action = "REJECT")
    @Transactional(rollbackFor = Exception.class)
    public void rejectOrder(Long orderId, Long operatorId, String remark) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工单不存在");
        }
        if (!operatorId.equals(order.getSubmitterId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅提交人可验收");
        }

        stateMachineValidator.validate(Status.valueOf(order.getStatus()), OrderAction.REJECT);

        String newStatus;
        if (order.getRejectCount() + 1 >= order.getMaxReject()) {
            newStatus = "ESCALATED_ADMIN";
        } else {
            newStatus = "IN_PROGRESS";
        }

        int rows = workOrderMapper.updateStatusAndIncrementReject(
                orderId, newStatus, order.getVersion(), order.getRejectCount());
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "状态已变更，请刷新重试");
        }

        if ("ESCALATED_ADMIN".equals(newStatus)) {
            notificationService.sendToRole("SYS_ADMIN",
                    "工单 " + order.getOrderNo() + " 驳回次数已达上限",
                    "类型:" + order.getType()
                            + ", 优先级:" + order.getPriority()
                            + ", 请介入处理");
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            messagePublishService.sendSlaEscalation(orderId);
                        }
                    });
        }
    }

    // ───────────────────── Issue #28: 超时释放 ─────────────────────

    @Override
    @com.workorder.common.aop.OrderAction(action = "RELEASE", remark = "系统超时自动释放")
    @Transactional(rollbackFor = Exception.class)
    public void releaseOrder(Long orderId) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工单不存在");
        }

        int rows = workOrderMapper.releaseOrder(orderId);
        if (rows == 0) {
            return;
        }
    }

    // ───────────────────── Issue #33: 管理员分配 ─────────────────────

    @Override
    @com.workorder.common.aop.OrderAction(action = "ASSIGN")
    @Transactional(rollbackFor = Exception.class)
    public void assignOrder(Long orderId, Long assigneeId, Long operatorId) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工单不存在");
        }

        User assignee = userMapper.selectById(assigneeId);
        if (assignee == null || assignee.getStatus() != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "被指派人不存在或已被禁用");
        }

        stateMachineValidator.validate(Status.valueOf(order.getStatus()), OrderAction.ASSIGN);

        int rows = workOrderMapper.assignOrder(orderId, assigneeId);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "工单已被抢走或状态异常");
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        redisTemplate.opsForValue().set(
                                "order:accept_timeout:" + orderId,
                                assigneeId.toString(),
                                Duration.ofMinutes(30));
                        messagePublishService.sendReleaseCheck(orderId);
                    }
                });
    }

    // ───────────────────── Issue #32: 驳回幂等 ─────────────────────

    private static final String REJECT_TOKEN_PREFIX = "token:reject:";
    private static final String REJECT_LUA = """
            if redis.call('get', KEYS[1]) == ARGV[1]
            then return redis.call('del', KEYS[1])
            else return 0
            end""";

    @Override
    public String generateRejectToken(Long orderId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(
                REJECT_TOKEN_PREFIX + token, orderId.toString(), Duration.ofSeconds(30));
        return token;
    }

    @Override
    public boolean validateAndConsumeRejectToken(Long orderId, String token) {
        String key = REJECT_TOKEN_PREFIX + token;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(REJECT_LUA, Long.class);
        Long result = redisTemplate.execute(script, List.of(key), orderId.toString());
        return result != null && result == 1L;
    }

    // ───────────────────── 已有的查询方法 ─────────────────────

    @Override
    public PageResult<WorkOrderVO> listOrders(PageQuery query, Long currentUserId) {
        Page<WorkOrder> page = new Page<>(query.getPage(), query.getSize());
        Set<String> roles = getRoleCodes(currentUserId);

        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();

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

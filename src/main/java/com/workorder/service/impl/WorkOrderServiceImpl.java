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
import com.workorder.common.dto.TriageResult;
import com.workorder.common.enums.OrderAction;
import com.workorder.common.enums.ReleaseResult;
import com.workorder.common.enums.Status;
import com.workorder.common.event.OrderEvent;
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
import com.workorder.service.MessagePublisher;
import com.workorder.service.NotificationService;
import com.workorder.service.OrderTriageService;
import com.workorder.service.StateMachineValidator;
import com.workorder.service.WorkOrderLogService;
import com.workorder.service.WorkOrderService;
import com.workorder.utils.OrderNoGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
    private final MessagePublisher messagePublisher;
    private final NotificationService notificationService;
    private final OrderTriageService orderTriageService;
    private final StringRedisTemplate redisTemplate;

    // ─────────────── 工单类型与优先级的合法值（对齐 BUSINESS-SCOPE.md F1-1 / R4） ───────────────

    /** 合法的工单类型。注意：type 为空是合法输入（走 AI triage），只有"非空但非法"才拒绝 */
    private static final Set<String> ALLOWED_TYPES = Set.of("NETWORK", "UTILITY", "DORM", "OTHER");

    /** 合法的优先级：0 普通 / 1 紧急 */
    private static final Set<Integer> ALLOWED_PRIORITIES = Set.of(0, 1);

    /** SLA 兜底配置的组合（F1-1 情形二 / I4 定稿 a-2）：值本身不硬编码，只硬编码"用哪个组合兜底" */
    private static final String FALLBACK_SLA_TYPE = "OTHER";
    private static final int FALLBACK_SLA_PRIORITY = 0;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder submitOrder(SubmitOrderReq req, Long submitterId) {
        String orderNo = orderNoGenerator.next();

        String type = req.getType() != null && !req.getType().isBlank() ? req.getType() : null;
        Integer priority = req.getPriority();

        // ── B1：入口校验。type 为 null 合法（走 triage），"非空但非法"才拒绝 ──
        if (type != null && !ALLOWED_TYPES.contains(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "非法的工单类型: " + type + "，允许值: " + ALLOWED_TYPES);
        }
        if (priority != null && !ALLOWED_PRIORITIES.contains(priority)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "非法的优先级: " + priority + "，允许值: " + ALLOWED_PRIORITIES);
        }

        // ── P5 步骤 1：**不再同步等 LLM**（改造前这里会同步调 triage，占着 DB 连接等最多 5 秒）──
        // 缺 type/priority 时先用兜底值落库（OTHER/普通；兜底组合来自配置表，不硬编码分钟数），
        // 置 triage_status='PENDING'，并在**同一事务内**发 ORDER_TRIAGE 事件；真正的分诊由消费端异步完成。
        java.util.List<String> missingFields = new java.util.ArrayList<>();
        if (type == null) {
            missingFields.add("type");
        }
        if (priority == null) {
            missingFields.add("priority");
        }
        boolean needsTriage = !missingFields.isEmpty();
        if (type == null) {
            type = FALLBACK_SLA_TYPE;
        }
        if (priority == null) {
            priority = FALLBACK_SLA_PRIORITY;
        }

        SlaConfig slaConfig = slaConfigMapper.selectOne(new LambdaQueryWrapper<SlaConfig>()
                .eq(SlaConfig::getType, type)
                .eq(SlaConfig::getPriority, priority));

        // ── B2：配置缺失时走兜底配置（I4 定稿 a-2），兜底值从配置表读取，不硬编码分钟数 ──
        boolean fallbackUsed = false;
        if (slaConfig == null) {
            slaConfig = slaConfigMapper.selectOne(new LambdaQueryWrapper<SlaConfig>()
                    .eq(SlaConfig::getType, FALLBACK_SLA_TYPE)
                    .eq(SlaConfig::getPriority, FALLBACK_SLA_PRIORITY));
            fallbackUsed = slaConfig != null;
        }

        LocalDateTime slaDeadline = null;
        if (slaConfig != null && slaConfig.getFinishMinutes() != null) {
            slaDeadline = LocalDateTime.now().plusMinutes(slaConfig.getFinishMinutes());
            if (fallbackUsed) {
                log.warn("[SLA兜底] 未找到对应 SLA 配置，使用兜底组合 {}//{} 的 finish_minutes={}: orderNo={}, type={}, priority={}",
                        FALLBACK_SLA_TYPE, FALLBACK_SLA_PRIORITY, slaConfig.getFinishMinutes(),
                        orderNo, type, priority);
            }
        } else {
            // 连兜底组合都查不到：正常不可能（ensureSlaConfigComplete 会在启动时报 error），
            // 但该自检不阻止启动，所以此路径可达——必须显式报错，不得静默落 NULL。
            log.error("[SLA兜底] 工单 {} 的 sla_deadline 将为 NULL：type={}, priority={}，且兜底组合 {}//{} 也不存在或 finish_minutes 为空。"
                            + "该工单不会进入 SLA 扫描（NULL 与任何值比较均为 unknown），请立即补齐 t_sla_config",
                    orderNo, type, priority, FALLBACK_SLA_TYPE, FALLBACK_SLA_PRIORITY);
        }

        WorkOrder order = new WorkOrder();
        order.setOrderNo(orderNo);
        order.setTitle(req.getTitle());
        order.setContent(req.getContent());
        order.setType(type);
        order.setPriority(priority);
        order.setStatus("PENDING");
        order.setSubmitterId(submitterId);
        order.setRejectCount(0);
        order.setMaxReject(3);
        // PENDING = 待分诊（提交时缺字段）；DONE = 不需要分诊（用户已填全）
        order.setTriageStatus(needsTriage ? "PENDING" : "DONE");
        order.setSlaDeadline(slaDeadline);
        order.setVersion(0);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.insert(order);

        workOrderLogService.saveLog(order.getId(), orderNo, submitterId,
                "SUBMIT", null, "PENDING", null);

        if (needsTriage) {
            // 与落库同事务：事务回滚则事件一并消失（outbox 的意义）；
            // payload 带上"哪些字段是空的"，消费端只写回这些字段（不覆盖用户手工填过的值）
            messagePublisher.publish(OrderEvent.orderTriage(
                    order.getId(), order.getVersion(), LocalDateTime.now(), missingFields));
        }

        // ── P5 步骤 2：提交通知（异步）──
        // **接收人解析不在这个事务里**：这里只往 outbox 写一行，消费端才去查"HANDLER 角色下有哪些人"，
        // 然后逐个写站内信。理由（plan §2.1）：30–50 名处理人就是 30–50 次单行插入，
        // 串在提交线程里会让 RT 随人数线性上涨；进了提交事务更糟——通知插失败会把用户的提交整体回滚。
        // 这一步**无条件执行**（不只在需要分诊时才发），与 needsTriage 无关。
        messagePublisher.publish(OrderEvent.orderSubmitted(order.getId(), order.getVersion(), LocalDateTime.now()));

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

        // P1 步骤 2：改为在**业务事务内**写 outbox。
        // 替代原先的 afterCommit 直发——那存在双写窗口：commit 成功后、send 之前进程崩溃，
        // 消息永久丢失且没有任何记录（Mock 下不可见，接真实 MQ 第一天就是线上问题）。
        // 注意：本方法仍在事务内，publish() 只做一次 INSERT；事务回滚则记录一并消失。
        LocalDateTime occurredAt = LocalDateTime.now();
        // 原先这里经由 afterCommit 写 Redis 标记 `order:accept_timeout:{id}`（TTL 30 分钟）。
        // **该键已被判定为死设计并彻底移除**（P1 步骤 2 移除写入、步骤 5 移除 startOrder 里的删除调用），
        // 原因见 docs/DECISIONS.md D48（任务书原写 D46，编号冲突后顺延）：
        //   ① 从来没有任何代码读取它；② "何时该释放"的真相来源是 t_sla_config.accept_minutes + outbox.deliver_at，
        //   不是 Redis 里的一份副本；③ 重新引入会把"接单强依赖 Redis 可用"的耦合加回来。
        publishReleaseCheck(order, occurredAt);
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

        // P1 步骤 5：此处原本删 Redis 标记 `order:accept_timeout:{id}`——该键是死设计，写入端已在步骤 2 移除，
        // 这个"删除一个从不存在、也无人读取的键"的调用一并删掉（少一次 Redis 往返，见 D48）。
        // 行为不变：startOrder 的状态流转与日志完全不受影响（`OrderAction(START)` 切面只读工单状态）。
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
    public ReleaseResult releaseOrder(Long orderId) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            // P1 步骤 4：由"抛异常"改为显式 ERROR 态——消费者据此记 ERROR 并（当前）ACK，
            // 兜底扫描据此记 ERROR 后继续扫下一张。异常仍会用于 DB 层故障（见 OrderReleaseListener 的 catch）。
            log.error("[release] 工单不存在，无法释放（消息可能来自已删除的工单）: orderId={}", orderId);
            return ReleaseResult.ERROR;
        }

        int rows = workOrderMapper.releaseOrder(orderId);
        if (rows == 0) {
            // 状态守卫未命中：工单已被 START/COMPLETE 等改过。这是正常结论，不是失败——
            // 释放语义靠状态守卫实现（不靠删除消息），见 ASYNC-SCHEDULING-PLAN.md §3.4 第 5 条。
            return ReleaseResult.SKIPPED;
        }
        return ReleaseResult.RELEASED;
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

        // 同 acceptOrder：事务内写 outbox（原 afterCommit 直发已删除，不留"双保险"）
        LocalDateTime occurredAt = LocalDateTime.now();
        // 同 acceptOrder：Redis 标记留到 P1 步骤 5 统一处理（理由见上）
        publishReleaseCheck(order, occurredAt);
    }

    // ───────────────────── Issue#P1: 管理员接管/关闭升级工单 ─────────────────────

    /** 具备处理/管理升级单资格的角色集合 */
    private static final Set<String> ESCALATION_HANDLER_ROLES =
            Set.of("HANDLER", "DEPT_ADMIN", "SYS_ADMIN");

    @Override
    @com.workorder.common.aop.OrderAction(action = "MANAGE")
    @Transactional(rollbackFor = Exception.class)
    public void manageEscalatedOrder(Long orderId, Long operatorId) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工单不存在");
        }

        // 仅升级单可被接管
        stateMachineValidator.validate(Status.valueOf(order.getStatus()), OrderAction.MANAGE);

        // 操作人须具备处理/管理资格（处理人 或 主管 或 超管）
        Set<String> roles = getRoleCodes(operatorId);
        boolean qualified = roles.stream().anyMatch(ESCALATION_HANDLER_ROLES::contains);
        if (!qualified) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅处理人/部门主管/系统管理员可接管升级工单");
        }

        // 原子接管：ESCALATED_ADMIN -> IN_PROGRESS，接管人 = 操作人；乐观锁防并发双接管
        int rows = workOrderMapper.takeOverEscalated(orderId, operatorId, order.getVersion());
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "工单状态已变更，可能已被接管，请刷新");
        }
    }

    @Override
    @com.workorder.common.aop.OrderAction(action = "CLOSE")
    @Transactional(rollbackFor = Exception.class)
    public void closeEscalatedOrder(Long orderId, Long operatorId) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工单不存在");
        }

        // 仅超管可强制关闭升级单
        if (!getRoleCodes(operatorId).contains("SYS_ADMIN")) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅系统管理员可强制关闭升级工单");
        }

        stateMachineValidator.validate(Status.valueOf(order.getStatus()), OrderAction.CLOSE);

        int rows = workOrderMapper.closeEscalated(orderId, order.getVersion());
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "工单状态已变更，请刷新");
        }
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

    /**
     * 工单详情——带数据级越权校验。
     * 可见规则（与 listOrders 的 RBAC 过滤一致）：
     *  - SYS_ADMIN：全部
     *  - 提交人：仅自己提交的
     *  - 处理人：自己接单的 + 待分配池(PENDING, 抢单前需查看)
     *  - 部门主管：本部门提交的（无部门则退化为仅自己）
     *  - ESCALATED_ADMIN 升级单：仅 SYS_ADMIN / DEPT_ADMIN 可见（须能接手处理，防止信息泄露）
     * 越权访问直接抛 FORBIDDEN，不泄露工单是否存在。
     */
    @Override
    public WorkOrderDetailVO getOrderDetail(Long orderId, Long currentUserId) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工单不存在");
        }

        Set<String> roles = getRoleCodes(currentUserId);
        if (!canViewDetail(order, roles, currentUserId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权查看该工单");
        }

        List<WorkOrderLogVO> logs = workOrderLogService.queryLogs(orderId);

        WorkOrderDetailVO detail = new WorkOrderDetailVO();
        detail.setOrder(toVO(order));
        detail.setLogs(logs);
        return detail;
    }

    /** 详情可见性判定 */
    private boolean canViewDetail(WorkOrder order, Set<String> roles, Long currentUserId) {
        // 超管看全部
        if (roles.contains("SYS_ADMIN")) {
            return true;
        }

        String status = order.getStatus();
        Long submitterId = order.getSubmitterId();
        Long assigneeId = order.getAssigneeId();

        // 升级单仅管理员（主管/超管）可见
        if ("ESCALATED_ADMIN".equals(status)) {
            return roles.contains("DEPT_ADMIN");
        }

        // 提交人看自己提交的
        if (roles.contains("SUBMITTER") && submitterId != null && submitterId.equals(currentUserId)) {
            return true;
        }

        // 处理人看自己接的 + 待分配池
        if (roles.contains("HANDLER")) {
            if (assigneeId != null && assigneeId.equals(currentUserId)) {
                return true;
            }
            if ("PENDING".equals(status) && assigneeId == null) {
                return true;
            }
        }

        // 部门主管看本部门提交的
        if (roles.contains("DEPT_ADMIN")) {
            User user = userMapper.selectById(currentUserId);
            if (user != null && user.getDeptId() != null && submitterId != null) {
                List<Long> deptUserIds = userMapper.selectList(
                                new LambdaQueryWrapper<User>().eq(User::getDeptId, user.getDeptId()))
                        .stream().map(User::getId).toList();
                if (deptUserIds.contains(submitterId)) {
                    return true;
                }
            }
        }

        return false;
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
        // P5 步骤 3：把分诊状态带出去（列表 / 详情 / 提交三处响应都走本方法，见三个调用点：
        // listOrders → :500、getOrderDetail → :603、Controller.submit → toVO）。
        // 前端据此渲染「分类中/已分类/分类失败」，否则用户看到的是兜底值 OTHER/普通，会以为系统判错了。
        vo.setTriageStatus(order.getTriageStatus());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setUpdatedAt(order.getUpdatedAt());
        return vo;
    }

    // ────────────── Issue #42/#43: LLM triage ──────────────

    /**
     * 解析该工单的**接单时限**（分钟），用于计算 outbox 的 {@code deliver_at}。
     *
     * <p>按工单的 {@code type+priority} 读 {@code t_sla_config.accept_minutes}——这一步让该字段
     * 从"写了不生效"变成真正被消费（G5/I8 的一半；另一半在 P1 步骤 5：Redis TTL 与兜底扫描 SQL）。
     *
     * <p>查不到该组合时回落到兜底组合 {@code OTHER + 普通}（记 WARN）；**连兜底也查不到时返回 {@code null}**
     * ——调用方 {@link #publishReleaseCheck} 据此**不写 outbox**（见该方法注释，这是 P1 步骤 3 收口的修正）。
     * 返回 {@code null} 而不是某个默认分钟数，是为了**不发明新的魔法数字**：缺配置时既不猜时限，也不投递。
     */
    private Integer resolveAcceptMinutes(String type, Integer priority) {
        SlaConfig cfg = slaConfigMapper.selectOne(new LambdaQueryWrapper<SlaConfig>()
                .eq(SlaConfig::getType, type)
                .eq(SlaConfig::getPriority, priority));
        if (cfg == null || cfg.getAcceptMinutes() == null) {
            cfg = slaConfigMapper.selectOne(new LambdaQueryWrapper<SlaConfig>()
                    .eq(SlaConfig::getType, FALLBACK_SLA_TYPE)
                    .eq(SlaConfig::getPriority, FALLBACK_SLA_PRIORITY));
            if (cfg == null || cfg.getAcceptMinutes() == null) {
                return null;
            }
            log.warn("[outbox] type={}, priority={} 无 SLA 配置，接单时限沿用兜底 {}//{} 的 accept_minutes={}",
                    type, priority, FALLBACK_SLA_TYPE, FALLBACK_SLA_PRIORITY, cfg.getAcceptMinutes());
        }
        return cfg.getAcceptMinutes();
    }

    /**
     * 接单/指派成功后写"释放检查"事件到 outbox（事务内）。
     *
     * <p><b>配置解析不出来时不写 outbox，而不是写一条"立即投递"的记录</b>（P1 步骤 3 收口修正）：
     * 早期实现把无法解析的时限取 0 → {@code deliver_at = now} → 记录到点即被投递 → 消费端/兜底一比对
     * 就把**刚接的单立刻释放**，处理人视角是"抢到的单莫名消失"。既然 {@code ReleaseTimeoutScheduler}
     * 本就能兜底完成释放，缺配置时的正确行为是**退化成 P1 之前的样子**（没有 MQ 这条通道），
     * 而不是进入一个没人验证过的新分支——所以这里只记 ERROR，接单本身照常成功。
     */
    private void publishReleaseCheck(WorkOrder order, LocalDateTime occurredAt) {
        Integer acceptMinutes = resolveAcceptMinutes(order.getType(), order.getPriority());
        if (acceptMinutes == null) {
            log.error("[outbox] 工单 {} (type={}, priority={}) 的接单时限无法解析——该组合与兜底组合 {}//{} "
                            + "都没有 accept_minutes → **本次不写 outbox、不投递延迟消息**，"
                            + "该工单的释放改由 ReleaseTimeoutScheduler 兜底（等同 P1 之前的行为）。"
                            + "修复动作：补齐 t_sla_config 缺失行（参照 sql/init.sql 第五节的 8 行）",
                    order.getId(), order.getType(), order.getPriority(), FALLBACK_SLA_TYPE, FALLBACK_SLA_PRIORITY);
            return;
        }
        messagePublisher.publish(OrderEvent.orderReleaseCheck(
                order.getId(), order.getVersion() + 1, occurredAt, occurredAt.plusMinutes(acceptMinutes)));
    }
}

package com.workorder.common.aop;

import cn.dev33.satoken.stp.StpUtil;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.WorkOrderLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Issue #40: 操作日志 AOP 切面
 * 环绕 @OrderAction 注解的方法，前后对比工单状态，自动写入操作日志。
 * 使用标量 SQL 读取 status 以绕开 MyBatis 一级缓存。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OrderLogAspect {

    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderLogService workOrderLogService;

    @Around("@annotation(orderAction)")
    public Object around(ProceedingJoinPoint joinPoint, OrderAction orderAction) throws Throwable {
        Long orderId = (Long) joinPoint.getArgs()[0];
        Object[] args = joinPoint.getArgs();

        String oldStatus = workOrderMapper.getStatusById(orderId);

        Object result = joinPoint.proceed();

        String newStatus = workOrderMapper.getStatusById(orderId);

        if (oldStatus != null && !oldStatus.equals(newStatus)) {
            Long operatorId = resolveOperatorId();
            WorkOrder order = workOrderMapper.selectById(orderId);
            String remark = resolveRemark(orderAction, args);
            workOrderLogService.saveLog(orderId, order.getOrderNo(), operatorId,
                    orderAction.action(), oldStatus, newStatus, remark);
        }

        return result;
    }

    /**
     * 解析操作人ID。正常 HTTP 请求取当前登录用户；无登录上下文时
     * （系统定时任务触发 release、测试裸线程等）降级为 0（系统操作），
     * 避免因取不到 Request 抛出 NotWebContextException 导致业务事务回滚。
     */
    private Long resolveOperatorId() {
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            return loginId != null ? Long.valueOf(loginId.toString()) : 0L;
        } catch (Exception e) {
            // Sa-Token 在无 Web 上下文时会抛 NotWebContextException，属预期降级场景
            return 0L;
        }
    }

    private String resolveRemark(OrderAction orderAction, Object[] args) {
        if (!orderAction.remark().isEmpty()) {
            return orderAction.remark();
        }
        if ("ASSIGN".equals(orderAction.action()) && args.length >= 2 && args[1] instanceof Long assigneeId) {
            return "管理员指派给用户" + assigneeId;
        }
        if (args.length >= 3 && args[2] instanceof String s) {
            return s;
        }
        return null;
    }
}

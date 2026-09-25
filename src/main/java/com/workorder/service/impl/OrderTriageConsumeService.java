package com.workorder.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workorder.common.dto.TriageResult;
import com.workorder.entity.SlaConfig;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.SlaConfigMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.scheduler.SlaEscalationScheduler;
import com.workorder.service.NotificationService;
import com.workorder.service.OrderTriageService;
import com.workorder.service.WorkOrderLogService;
import com.workorder.service.impl.ConsumeRecordService.BusinessOutcome;
import com.workorder.service.impl.ConsumeRecordService.ConsumeResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 分诊事件的消费编排（P5 步骤 1）：LLM 分诊 → 写回 → **按 H4 重算 SLA** → 修正日志 → （必要时）立即告警。
 *
 * <h3>一、两道防线（与释放链路同一套语义，但守卫字段不同）</h3>
 * <ol>
 *   <li><b>去重表</b>（{@code t_consume_record}，consumer={@code order-triage-listener}）：回答"这条事件消费过吗"；</li>
 *   <li><b>状态守卫</b>：回答"这张单现在还需要分诊吗"——{@code WHERE triage_status='PENDING'}。
 *       它挡的是**迟到的分诊结果覆盖人工修改过的 type/priority**（用户已经手工改过，AI 就不能再改）。</li>
 * </ol>
 *
 * <h3>二、字段级规则：只写回"提交时为空"的字段</h3>
 * 依据来自消息元信息 {@code missingFields}（提交时哪几个字段是空的），**不是**在消费时重新猜：
 * 请求早就结束了，而且 t_work_order 的 type/priority 是 NOT NULL（落库时已填兜底值），无法用 NULL 表示"未提供"。
 * 于是：用户手工填过的字段（不在 missingFields 里）**一律保持原值**。
 *
 * <h3>三、H4 重算规则（定稿，不要自行发挥）</h3>
 * <b>基准 = {@code created_at} + 新的 {@code finish_minutes}</b>（不是 now！否则"放了很久才分诊"的工单会被凭空续命）。
 * 重算后若**已经过期** → 立即告警，且**计为 H1 的首次告警**（写同一个去重键 {@code sla_notified:{id}}，24h 催办节奏自该时刻起算）。
 *
 * <h3>四、失败怎么办</h3>
 * LLM 不可用 / 返回非法值 / 找不到 SLA 配置 → 返回 {@link BusinessOutcome#FAILED}：
 * 去重记录随之回滚（{@code consumeOnce} 里 setRollbackOnly），并由编排层写**重试账本**（复用 P4 的阶梯与停车，不另建一套）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTriageConsumeService {

    /** 消费结果（与释放链路同构，便于 listener 用同一套日志/ACK 映射） */
    public enum Outcome { APPLIED, SKIPPED, DUPLICATE, RETRY_SCHEDULED, NOT_RETRYABLE }

    /** 合法类型集合（与 WorkOrderServiceImpl.ALLOWED_TYPES 一致；LLM 返回值必须落在集合内） */
    private static final Set<String> ALLOWED_TYPES = Set.of("NETWORK", "UTILITY", "DORM", "OTHER");
    private static final Set<Integer> ALLOWED_PRIORITIES = Set.of(0, 1);
    private static final String FALLBACK_TYPE = "OTHER";
    private static final int FALLBACK_PRIORITY = 0;

    private final ConsumeRecordService consumeRecordService;
    private final MessageRetryService messageRetryService;
    private final OrderTriageService orderTriageService;
    private final WorkOrderMapper workOrderMapper;
    private final SlaConfigMapper slaConfigMapper;
    private final WorkOrderLogService workOrderLogService;
    private final NotificationService notificationService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Outcome consume(String eventId, Long orderId, String payload) {
        List<String> missingFields = parseMissingFields(payload);
        try {
            ConsumeResult result = consumeRecordService.consumeOnce(eventId,
                    ConsumeRecordService.CONSUMER_ORDER_TRIAGE,
                    () -> applyTriage(orderId, missingFields));

            if (result.duplicate()) {
                closeLedger(eventId);
                return Outcome.DUPLICATE;
            }
            return switch (result.outcome()) {
                case SUCCESS -> {
                    closeLedger(eventId);
                    yield Outcome.APPLIED;
                }
                case SKIPPED -> {
                    closeLedger(eventId);
                    yield Outcome.SKIPPED;
                }
                case FAILED -> scheduleRetry(eventId, orderId, payload, "分诊失败（LLM 不可用/返回非法值/SLA 配置缺失）");
            };
        } catch (Exception e) {
            return scheduleRetry(eventId, orderId, payload, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 真正的业务：在调用方（{@code consumeOnce}）的事务里执行。
     */
    private BusinessOutcome applyTriage(Long orderId, List<String> missingFields) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            log.error("[triage] 工单不存在，分诊无法进行: orderId={}", orderId);
            return BusinessOutcome.FAILED;
        }

        // ── 状态守卫（第二道防线）：只处理 PENDING，防止迟到结果覆盖人工修改 ──
        if (!"PENDING".equals(order.getTriageStatus())) {
            log.debug("[triage] 跳过：triage_status={}（已定稿或已被处理），不覆盖人工修改: orderId={}",
                    order.getTriageStatus(), orderId);
            return BusinessOutcome.SKIPPED;
        }

        // 分诊本身失败（LLM 不可用/超时/返回非法值）→ FAILED：回滚去重记录并进重试账本
        TriageResult triage = orderTriageService.triage(order.getTitle(), order.getContent());
        if (triage == null || triage.getSuggestedType() == null
                || !ALLOWED_TYPES.contains(triage.getSuggestedType())
                || triage.getSuggestedPriority() == null
                || !ALLOWED_PRIORITIES.contains(triage.getSuggestedPriority())) {
            log.error("[triage] LLM 返回不可用/非法值，按失败处理（进重试账本，提交本身不受影响）: orderId={} type={} priority={}",
                    orderId, triage == null ? null : triage.getSuggestedType(),
                    triage == null ? null : triage.getSuggestedPriority());
            return BusinessOutcome.FAILED;
        }

        // ── 字段级规则：只写回"提交时为空"的字段（用户手工填的一律不覆盖）──
        String newType = missingFields.contains("type") ? triage.getSuggestedType() : order.getType();
        Integer newPriority = missingFields.contains("priority") ? triage.getSuggestedPriority() : order.getPriority();

        SlaConfig config = resolveConfig(newType, newPriority);
        if (config == null || config.getFinishMinutes() == null) {
            log.error("[triage] 找不到 SLA 配置（含兜底组合），无法重算 sla_deadline，按失败处理等配置补齐: orderId={} type={} priority={}",
                    orderId, newType, newPriority);
            return BusinessOutcome.FAILED;
        }

        // ── H4：重算基准 = created_at + 新 finish_minutes（**不是 now**）──
        LocalDateTime newDeadline = order.getCreatedAt().plusMinutes(config.getFinishMinutes());
        int updated = workOrderMapper.updateTriageResult(orderId, newType, newPriority, newDeadline);
        if (updated == 0) {
            // 守卫未命中：并发场景下别人已经改过（人工修改/另一条分诊事件）→ 正常跳过
            log.debug("[triage] 写回未命中状态守卫（已被人工修改或已处理），跳过: orderId={}", orderId);
            return BusinessOutcome.SKIPPED;
        }

        // ── 修正日志（写进操作日志时间线，operatorId=0 表示系统）──
        workOrderLogService.saveLog(orderId, order.getOrderNo(), 0L, "TRIAGE",
                order.getStatus(), order.getStatus(),
                String.format("AI 分诊修正: type %s→%s, priority %s→%s, sla_deadline %s→%s（基准 created_at=%s + finish_minutes=%d）",
                        order.getType(), newType, order.getPriority(), newPriority,
                        order.getSlaDeadline(), newDeadline, order.getCreatedAt(), config.getFinishMinutes()));
        log.info("[triage] 分诊写回完成: orderId={} type {}→{} priority {}→{} sla_deadline {}→{} 依据={}",
                orderId, order.getType(), newType, order.getPriority(), newPriority, order.getSlaDeadline(), newDeadline,
                // 模型给的判定依据（信息不足时会写"依据不足：…"）——留痕，便于事后区分"模型判成非故障类"与"模型没看懂"（D65）
                triage.getReason() == null ? "（模型未给）" : triage.getReason());

        // ── H4 b-1：重算后已过期 → 立即告警，且计为 H1 的"首次告警"（24h 催办节奏自此刻起）──
        if (!newDeadline.isAfter(LocalDateTime.now())) {
            alertImmediately(order, newDeadline);
        }
        return BusinessOutcome.SUCCESS;
    }

    /**
     * 立即告警：复用 SLA 扫描器的去重键与 TTL（{@code sla_notified:{orderId}} / 24h）。
     *
     * <p>语义：这一次告警**就是 H1 说的"首次告警"**，24 小时的催办节奏从此刻开始；
     * 因此这里必须写去重键——否则 SLA 扫描器下一轮会再发一次，变成重复告警。
     */
    private void alertImmediately(WorkOrder order, LocalDateTime deadline) {
        String key = SlaEscalationScheduler.notifiedKey(order.getId());
        Boolean acquired;
        try {
            acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", SlaEscalationScheduler.SLA_NOTIFIED_TTL);
        } catch (Exception e) {
            log.warn("[triage] 写告警去重键失败，仍发出告警（可能重复一次）: orderId={}, 原因={}", order.getId(), e.getMessage());
            acquired = Boolean.TRUE;
        }
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("[triage] 已有告警记录（24h 内），不重复立即告警: orderId={}", order.getId());
            return;
        }
        notificationService.sendToRole("SYS_ADMIN",
                "工单 " + order.getOrderNo() + " SLA 超时（分诊后立即触发）",
                "类型:" + order.getType() + ", 优先级:" + order.getPriority()
                        + ", 当前状态:" + order.getStatus() + ", 重算截止:" + deadline
                        + "（H4 b-1：以 created_at 为基准重算后已过期）");
        log.error("[triage] H4 b-1 触发：分诊重算后 SLA 已过期，立即告警并计为首次告警，24h 催办节奏自此刻起: orderId={} deadline={}",
                order.getId(), deadline);
    }

    private SlaConfig resolveConfig(String type, Integer priority) {
        SlaConfig config = slaConfigMapper.selectOne(new LambdaQueryWrapper<SlaConfig>()
                .eq(SlaConfig::getType, type).eq(SlaConfig::getPriority, priority));
        if (config == null) {
            config = slaConfigMapper.selectOne(new LambdaQueryWrapper<SlaConfig>()
                    .eq(SlaConfig::getType, FALLBACK_TYPE).eq(SlaConfig::getPriority, FALLBACK_PRIORITY));
            if (config != null) {
                log.warn("[triage] type={}, priority={} 无 SLA 配置，重算沿用兜底组合 {}//{} 的 finish_minutes",
                        type, priority, FALLBACK_TYPE, FALLBACK_PRIORITY);
            }
        }
        return config;
    }

    /** 从 payload 里读"提交时哪些字段为空"；读不到就按两个字段都缺处理（保守：宁可多写回，也不漏写回） */
    private List<String> parseMissingFields(String payload) {
        List<String> fields = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(payload).get("missingFields");
            if (node != null && node.isArray()) {
                node.forEach(f -> fields.add(f.asText()));
            }
        } catch (Exception e) {
            log.warn("[triage] payload 里读不到 missingFields（按'两个字段都缺'处理）: {}", e.getMessage());
        }
        if (fields.isEmpty()) {
            fields.add("type");
            fields.add("priority");
        }
        return fields;
    }

    private Outcome scheduleRetry(String eventId, Long orderId, String payload, String error) {
        if (eventId == null || eventId.isBlank()) {
            log.error("[triage] 分诊失败但缺少 x-event-id，无法落重试账本，本条不重投（工单保持 PENDING，由人工或后续修复触发）: orderId={} 原因={}",
                    orderId, error);
            return Outcome.NOT_RETRYABLE;
        }
        try {
            messageRetryService.recordFailure(eventId, ConsumeRecordService.CONSUMER_ORDER_TRIAGE,
                    payload == null || payload.isBlank() ? "{\"orderId\":" + orderId + "}" : payload, error);
            return Outcome.RETRY_SCHEDULED;
        } catch (Exception e) {
            log.error("[triage] 写重试账本失败（这条消息本轮 ACK 后不会自动重投）: eventId={}, orderId={}", eventId, orderId, e);
            return Outcome.NOT_RETRYABLE;
        }
    }

    private void closeLedger(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        try {
            messageRetryService.markSucceeded(eventId, ConsumeRecordService.CONSUMER_ORDER_TRIAGE);
        } catch (Exception e) {
            log.warn("[triage] 关闭重试账本失败（不影响本次结果）: eventId={}, 原因={}", eventId, e.getMessage());
        }
    }
}

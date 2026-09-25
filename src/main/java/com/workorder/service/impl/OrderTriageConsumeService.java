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
 *
 * <h3>五、三段式：LLM 调用在**事务外**（P5 收口，2026-09-26）</h3>
 * <pre>
 * ① 事务外：准入预检（读一次工单，自动提交，语句结束即归还连接）→ **调 LLM** → 校验结果可用性
 * ② 事务内：consumeOnce —— 去重 INSERT + 写回 + SLA 重算 + 修正日志（去重与业务写**同事务**，P4 规则不动）
 * ③ 事务外：失败时写重试账本（MessageRetryService 自己 REQUIRES_NEW，P4 规则不动）
 * </pre>
 * <b>为什么必须挪（改动前的病）</b>：LLM 调用要 5–15s（最坏 30s）。留在事务里 = 一条消息**占住一条数据库连接**
 * 整个调用期间，而且 {@code listener.concurrency} 一调大就等量吃连接 —— 并发上限被连接池锁死
 * （曾经 concurrency=1 时吞吐 ≈ 0.2 单/秒）。挪出后连接只在两次短事务里被持有（毫秒级），并发才能真正打开。
 *
 * <h3>六、这次改动的代价：**"重复调一次 LLM"的窗口**（必须知道，不要当成没有）</h3>
 * 去重记录是在 **LLM 调用之后**才写的，于是多出两个窗口：
 * <ol>
 *   <li><b>调用完成 → 事务提交之间崩溃/被杀</b>：这条消息没有留下去重记录，MQ 重投时会**再调一次 LLM**
 *       （多花一次 token）；</li>
 *   <li><b>同一条事件被并发重复投递</b>：两个消费者可能各自调一次 LLM，然后一个插去重记录、另一个撞唯一键被跳过。</li>
 * </ol>
 * <b>为什么可接受</b>：写回本身有状态守卫（{@code UPDATE ... WHERE triage_status='PENDING'}），
 * 重复调用**不会产生错误结果**，最坏是"多花一次 token"；相比之下，"连接被 LLM 占住导致整条链路吞吐被锁死"
 * 是更严重的结构性问题。若要连这点浪费也消掉，只能回到"先写去重记录再调 LLM"，而那要求去重记录
 * **先于**业务写独立提交 —— 那正是 P4 明令禁止的方向（业务失败后重试会被永久跳过）。
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

        // ───────── ① 事务外：准入预检 + **调用 LLM**（P5 收口：LLM 不再在事务里）─────────
        // 为什么必须挪出来：LLM 调用要 5–15s（最坏 30s）。留在 consumeOnce 的事务里 = 一条消息
        //   **占住一条数据库连接**整个调用期间，并且让 listener.concurrency 成为吞吐上限
        //   （并发调大就等量吃连接）。挪出后：连接只在两次短事务里被持有（毫秒级），
        //   并发才能真正打开（详见 D67）。
        // 顺带做一次"便宜的准入预检"：已经定稿的单不必花 token（**它不是权威守卫**，
        //   权威守卫仍是事务内 UPDATE 的 `WHERE triage_status='PENDING'`——预检与事务之间
        //   用户仍可能改动工单，所以事务内必须重新读、重新判）。
        TriageResult triage = null;
        boolean preSkip = false;
        try {
            WorkOrder snapshot = workOrderMapper.selectById(orderId);   // 自动提交：语句结束即归还连接，不会跨 LLM 调用持有
            if (snapshot == null) {
                log.error("[triage] 工单不存在，分诊无法进行: orderId={}", orderId);
                return scheduleRetry(eventId, orderId, payload, "工单不存在（事务外预检）");
            }
            if (!"PENDING".equals(snapshot.getTriageStatus())) {
                // 已定稿／已被人工处理 → 不调 LLM（省一次 token）；**去重记录仍照写入**（与改动前的行为一致）
                preSkip = true;
                log.debug("[triage] 事务外预检：triage_status={} 已定稿，跳过 LLM 调用（去重记录仍会写入）: orderId={}",
                        snapshot.getTriageStatus(), orderId);
            } else {
                triage = orderTriageService.triage(snapshot.getTitle(), snapshot.getContent());
                if (!isUsable(triage)) {
                    // 拿不到结论（LLM 不可用/超时/返回非法值）→ 不进事务、直接进重试账本。
                    // 与改动前一致：这种情况下**去重记录不会留下**（改动前是"插了又随事务回滚"）。
                    log.error("[triage] LLM 返回不可用/非法值，按失败处理（进重试账本，提交本身不受影响）: orderId={} type={} priority={}",
                            orderId, triage == null ? null : triage.getSuggestedType(),
                            triage == null ? null : triage.getSuggestedPriority());
                    return scheduleRetry(eventId, orderId, payload, "分诊失败（LLM 不可用/返回非法值）");
                }
            }
        } catch (Exception e) {
            return scheduleRetry(eventId, orderId, payload, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // ───────── ② 事务内：去重 INSERT + 写回 + SLA 重算 + 修正日志（P4 规则：去重与业务写**同事务**）─────────
        final TriageResult prepared = triage;
        final boolean skip = preSkip;
        try {
            ConsumeResult result = consumeRecordService.consumeOnce(eventId,
                    ConsumeRecordService.CONSUMER_ORDER_TRIAGE,
                    () -> skip ? BusinessOutcome.SKIPPED : applyTriage(orderId, missingFields, prepared));

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
                case FAILED -> scheduleRetry(eventId, orderId, payload, "分诊写回失败（SLA 配置缺失等）");
            };
        } catch (Exception e) {
            // ③ 事务外：失败写重试账本（P4 规则：账本必须在业务事务之外）——scheduleRetry 由 MessageRetryService 自己开 REQUIRES_NEW
            return scheduleRetry(eventId, orderId, payload, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** LLM 结果是否可用（类型/优先级都落在合法集合内，见 D65 的 prompt 契约） */
    private boolean isUsable(TriageResult triage) {
        return triage != null
                && triage.getSuggestedType() != null && ALLOWED_TYPES.contains(triage.getSuggestedType())
                && triage.getSuggestedPriority() != null && ALLOWED_PRIORITIES.contains(triage.getSuggestedPriority());
    }

    /**
     * 真正的业务：在调用方（{@code consumeOnce}）的事务里执行。**这里不再有 LLM 调用**（结果由参数传入）。
     */
    private BusinessOutcome applyTriage(Long orderId, List<String> missingFields, TriageResult triage) {
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

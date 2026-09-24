package com.workorder.service.impl;

import com.workorder.common.enums.ReleaseResult;
import com.workorder.entity.ConsumeRecord;
import com.workorder.mapper.ConsumeRecordMapper;
import com.workorder.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * 消费端幂等的**事务边界持有者**（P4 步骤 1）。
 *
 * <h3>一、为什么需要一个单独的类来持有事务</h3>
 * 要求是"去重记录 + 业务写在同一个事务里"，而 {@code @Transactional} 只在**跨 bean 调用**时生效：
 * 如果让 listener 自己开事务、再在同一个类里自调用 {@code releaseOrder}，既违反代理语义、
 * 也会绕过 {@code @OrderAction} 切面（释放操作的审计日志会静默丢失）。
 * 因此把边界放在这里：本类开事务 → 通过注入的 {@code WorkOrderService} **代理**调业务（切面与事务都生效，
 * 且 {@code @Transactional} 默认 REQUIRED → 加入本事务）。
 *
 * <h3>二、顺序不能反（plan §5.2 的原话场景）</h3>
 * 本方法的顺序是 **先 INSERT 去重记录 → 再执行释放**，两者在**同一事务**内提交：
 * <ul>
 *   <li>业务成功 → 两行一起提交；重复投递时再次 INSERT 命中 UNIQUE → 直接返回"已消费"。</li>
 *   <li>业务失败（本方法抛异常）→ 事务回滚，**去重记录也一起消失** → 下次重投仍能正常处理。</li>
 *   <li>若反过来"先提交去重记录、再执行业务"，业务一失败去重记录已落地，
 *       重试会被永久跳过——那是**消息被静默吃掉**，比重复更危险。</li>
 * </ul>
 *
 * <h3>三、与调用方（listener）的 ACK 契约</h3>
 * 本方法是 {@code @Transactional}：**返回时事务已经提交**。listener 在本方法返回之后才 ACK，
 * 因此不会出现"消息已经 ACK、事务却回滚"的窗口（那等于丢消息）。
 *
 * <h3>四、没有 eventId 时怎么办</h3>
 * 消息缺 {@code x-event-id} 时无法做事件级去重：记 WARN 后**照常执行**释放检查
 * （此时只剩状态守卫这一道防线）。投递侧（{@code OutboxDispatchTask}）总会带这个头，
 * 出现缺失说明消息不是本项目投出来的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsumeRecordService {

    /** 释放检查消费者的标识，写进 {@code t_consume_record.consumer}；同一事件可被多个消费者各消费一次 */
    public static final String CONSUMER_ORDER_RELEASE = "order-release-listener";

    /** 分诊消费者的标识（P5 步骤 1）：与释放检查是同一事件上的两个不同消费者 */
    public static final String CONSUMER_ORDER_TRIAGE = "order-triage-listener";

    private final ConsumeRecordMapper consumeRecordMapper;
    private final WorkOrderService workOrderService;

    /** 业务结果三态（与 {@link ReleaseResult} 同构，但供任意消费者使用） */
    public enum BusinessOutcome {
        /** 业务成功 */
        SUCCESS,
        /** 状态守卫未命中（迟到的消息 / 已被人工改过），正常跳过——**不是失败** */
        SKIPPED,
        /** 业务失败（需要重试） */
        FAILED
    }

    /**
     * 消费结果。
     *
     * @param duplicate {@code true} = 这条事件之前已经消费过（去重命中），本次未执行业务
     * @param outcome   业务结果；{@code duplicate=true} 时为 {@code null}
     */
    public record ConsumeResult(boolean duplicate, BusinessOutcome outcome) {
    }

    /**
     * 消费"释放检查"事件：同事务内"写去重记录 + 执行释放"。
     */
    @Transactional(rollbackFor = Exception.class)
    public ConsumeResult consumeReleaseCheck(String eventId, Long orderId) {
        return consumeOnce(eventId, CONSUMER_ORDER_RELEASE, () -> switch (workOrderService.releaseOrder(orderId)) {
            case RELEASED -> BusinessOutcome.SUCCESS;
            case SKIPPED -> BusinessOutcome.SKIPPED;
            case ERROR -> BusinessOutcome.FAILED;
        });
    }

    /**
     * **通用**的"消费一次"：同一事务内"写去重记录 + 执行业务"（P5 步骤 1 从释放路径抽出，释放与分诊共用）。
     *
     * <p>业务以 {@link java.util.function.Supplier} 传入，在本方法的事务里执行：业务里调用的 Spring 组件
     * （如 {@code workOrderService.releaseOrder}）会加入同一事务、切面照常生效。
     * 返回 {@link BusinessOutcome#FAILED} 时 `setRollbackOnly()`——**业务失败要连去重记录一起回滚**，
     * 否则重投会命 UNIQUE 被判"已消费"而空转（P4 步骤 2 踩过，见 D53）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ConsumeResult consumeOnce(String eventId, String consumer,
                                    java.util.function.Supplier<BusinessOutcome> business) {
        if (eventId == null || eventId.isBlank()) {
            log.warn("[consume] 消息缺少 x-event-id，无法做事件级去重，本次直接执行业务（只剩状态守卫这道防线）: consumer={}",
                    consumer);
        } else {
            try {
                ConsumeRecord record = new ConsumeRecord();
                record.setEventId(eventId);
                record.setConsumer(consumer);
                consumeRecordMapper.insert(record);
            } catch (DuplicateKeyException e) {
                // 唯一键命中 = 这条事件已经消费过（重复投递、手工重投、broker 重发）。
                // 关键：**这里不执行业务**，它不会被第二次执行。
                // MySQL 下唯一键冲突只让这一条 INSERT 失败，不会让整个事务不可用，所以可以在这里直接返回。
                log.debug("[consume] 重复投递：该事件已消费过，跳过一次（不执行业务）: eventId={}, consumer={}",
                        eventId, consumer);
                return new ConsumeResult(true, null);
            }
        }

        // 与上面的 INSERT 在**同一事务**：业务返回 FAILED 或抛异常 → 去重记录一起回滚（重投仍可正常处理）
        BusinessOutcome outcome = business.get();
        if (outcome == BusinessOutcome.FAILED) {
            // **业务失败（含释放三态里的 ERROR）必须回滚去重记录**：不回滚会踩一个很隐蔽的坑——
            // 去重记录留着 → 重投时 INSERT 命 UNIQUE → 被当成"已消费"跳过，
            // 重试账本被空转关掉、业务永远不会再执行（P4 步骤 2 实测发现，见 D53）。
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
        return new ConsumeResult(false, outcome);
    }
}

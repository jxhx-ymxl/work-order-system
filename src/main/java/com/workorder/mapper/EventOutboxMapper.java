package com.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workorder.entity.EventOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * outbox 表的读写入口。
 *
 * <p><b>投递侧的四类语句刻意都写成"条件 UPDATE"，而不是"先 SELECT ... FOR UPDATE 再改"</b>：
 * 投递流程必须在**不持有数据库行锁**的前提下做网络 IO（见 {@code docs/DECISIONS.md} 的对应条目）。
 * 条件 UPDATE 的语义是"谁改了谁就拿到租约"：语句自带 WHERE 守卫，天然多实例安全
 * （第二个实例的 UPDATE 匹配不到已被抢占的行），且锁在语句结束时就释放。
 *
 * <p>所有回写都带 {@code AND status='SENDING'}：万一记录已被回收任务改回 PENDING
 * （例如持有它的实例卡了太久），迟到的回写不会覆盖新状态，只会影响 0 行并被日志记录。
 */
@Mapper
public interface EventOutboxMapper extends BaseMapper<EventOutbox> {

    /**
     * 原子抢占：把最多 {@code limit} 条"可投递"记录标成 SENDING 并写上 owner。
     *
     * <p>可投递 = {@code status='PENDING'} 且（从未重试过或已过退避时间）。
     * <b>这里没有 {@code deliver_at} 条件</b>：延迟由延迟交换机的 {@code x-delay} 承担，
     * 该值由 {@code deliver_at} 现算（见 {@code OutboxDispatchTask#delayMillis}）。
     * 若在这里再卡一次 {@code deliver_at<=NOW()}，延迟消息就永远只在"到点那一刻"才发，
     * 交换机里的延迟语义会退化成 0 延迟。
     */
    @Update("UPDATE t_event_outbox SET status='SENDING', owner=#{owner}, claimed_at=NOW() "
            + "WHERE status='PENDING' AND (next_retry_at IS NULL OR next_retry_at <= NOW()) "
            + "ORDER BY id LIMIT #{limit}")
    int claimPending(@Param("owner") String owner, @Param("limit") int limit);

    /** 取回本轮抢占到的记录（按 owner 认领，避免误取别的实例的租约） */
    @Select("SELECT * FROM t_event_outbox WHERE status='SENDING' AND owner=#{owner} ORDER BY id")
    List<EventOutbox> selectClaimedBy(@Param("owner") String owner);

    /** 收到 publisher-confirm 的 ack 后才允许调用 */
    @Update("UPDATE t_event_outbox SET status='SENT', sent_at=NOW(), owner=NULL, claimed_at=NULL "
            + "WHERE id=#{id} AND status='SENDING'")
    int markSent(@Param("id") Long id);

    /**
     * 投递未确认（nack / 超时 / 发送异常）时回写。
     *
     * @param status      {@code PENDING}（还会重试）或 {@code FAILED}（达上限，待人工介入）
     * @param nextRetryAt 退避后的下次可投递时间；{@code FAILED} 时传 null
     */
    @Update("UPDATE t_event_outbox SET status=#{status}, retry_count=retry_count+1, "
            + "next_retry_at=#{nextRetryAt}, owner=NULL, claimed_at=NULL "
            + "WHERE id=#{id} AND status='SENDING'")
    int markAttemptFailed(@Param("id") Long id,
                          @Param("status") String status,
                          @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /**
     * 退回抢占但**未尝试发送**的记录（本轮批预算用尽、或遇到连接级故障提前收尾）。
     * 不增加 retry_count：它没被尝试过，不是失败。
     */
    @Update("UPDATE t_event_outbox SET status='PENDING', owner=NULL, claimed_at=NULL "
            + "WHERE id=#{id} AND status='SENDING'")
    int releaseClaim(@Param("id") Long id);

    /**
     * 回收"抢占后进程崩溃/卡死"留下的 SENDING 记录（超过阈值无进展）。
     *
     * <p>阈值必须显著大于单轮投递的最坏耗时，否则会把仍在投递中的记录抢回去、造成重复投递；
     * 取值依据见 {@code docs/DECISIONS.md}（单轮预算 vs 回收阈值的倍数关系）。
     */
    @Update("UPDATE t_event_outbox SET status='PENDING', owner=NULL, claimed_at=NULL, next_retry_at=NOW() "
            + "WHERE status='SENDING' AND claimed_at < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)")
    int reclaimStale(@Param("minutes") int minutes);
}

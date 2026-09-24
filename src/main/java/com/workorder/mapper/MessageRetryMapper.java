package com.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workorder.entity.MessageRetry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 消息重试表的读写入口。
 *
 * <p><b>取数只取 id</b>（与 {@code idx_retry_dispatch} 一一对应）：重投任务不把整行读进内存，
 * 逐行 CAS 抢占之后才加载该行——这样多实例下"谁抢到谁投"，且不会像 D49 那样退化成"全库 + LIMIT 后互相干扰"。
 */
@Mapper
public interface MessageRetryMapper extends BaseMapper<MessageRetry> {

    /** 取待重投的 id（走 idx_retry_dispatch；与取数条件一一对应） */
    @Select("SELECT id FROM t_message_retry WHERE status = 'PENDING' AND next_retry_at <= NOW() "
            + "ORDER BY next_retry_at LIMIT #{limit}")
    List<Long> selectPendingIds(@Param("limit") int limit);

    /**
     * 逐行 CAS 抢占：把 {@code next_retry_at} 推到 {@code NOW() + lease} 作为**时间租约**。
     *
     * <p>只有影响 1 行的实例才去投递（多实例安全）。用时间租约而不是 owner 列，原因有二：
     * ① 状态枚举按设计只有 PENDING/SUCCEEDED/PARKED（没有 SENDING 中间态）；
     * ② 进程崩在投递中途时，租约到期后这行自然回到可重投状态——**不需要单独的回收任务**（比 outbox 那套更简单）。
     */
    @Update("UPDATE t_message_retry SET next_retry_at = DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND) "
            + "WHERE id = #{id} AND status = 'PENDING' AND next_retry_at <= NOW()")
    int claimById(@Param("id") Long id, @Param("leaseSeconds") int leaseSeconds);

    /** 行锁读取：让并发失败对同一条事件的 attempt 递增串行化（短事务、无网络 IO） */
    @Select("SELECT * FROM t_message_retry WHERE event_id = #{eventId} AND consumer = #{consumer} FOR UPDATE")
    MessageRetry selectForUpdate(@Param("eventId") String eventId, @Param("consumer") String consumer);

    /** 成功（含 SKIPPED 与重复投递）时关闭账本；没有账本时影响 0 行，属正常 */
    @Update("UPDATE t_message_retry SET status = 'SUCCEEDED', next_retry_at = NULL "
            + "WHERE event_id = #{eventId} AND consumer = #{consumer} AND status = 'PENDING'")
    int markSucceeded(@Param("eventId") String eventId, @Param("consumer") String consumer);

    /**
     * 再失败一次：attempt+1、写 last_error、按阶梯推 next_retry_at。
     *
     * <p>刻意写成显式 SQL 而不是 {@code updateById(entity)}：MyBatis-Plus 默认忽略 null 字段，
     * 那样把 {@code next_retry_at} 置空（PARKED 时）会**静默失效**——这是本项目反复出现过的"写了不生效"类型。
     */
    @Update("UPDATE t_message_retry SET attempt = #{attempt}, status = 'PENDING', "
            + "next_retry_at = #{nextRetryAt}, last_error = #{lastError} WHERE id = #{id}")
    int markPendingAgain(@Param("id") Long id,
                         @Param("attempt") int attempt,
                         @Param("nextRetryAt") java.time.LocalDateTime nextRetryAt,
                         @Param("lastError") String lastError);

    /** 超过阶梯上限：PARKED（next_retry_at 必须置空，否则会被误当成"到期可重投"） */
    @Update("UPDATE t_message_retry SET attempt = #{attempt}, status = 'PARKED', "
            + "next_retry_at = NULL, last_error = #{lastError} WHERE id = #{id}")
    int markParked(@Param("id") Long id,
                   @Param("attempt") int attempt,
                   @Param("lastError") String lastError);
}

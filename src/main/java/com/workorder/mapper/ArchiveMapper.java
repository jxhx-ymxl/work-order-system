package com.workorder.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * P6 归档/清理任务的 SQL 入口。
 *
 * <p><b>三条语句都是静态 SQL，表名写死</b>——不拼表名、不用字符串替换占位符。
 * 白名单（{@link com.workorder.scheduler.ArchiveTarget}）决定"调哪个方法"，
 * 而不是"往 SQL 里塞什么字符串"：参数里写错表名时在解析阶段就失败，SQL 层不存在注入面。
 *
 * <p><b>分片条件 {@code MOD(id, #{shardTotal}) = #{shardIndex}} 是叠加在保留期谓词之上的</b>：
 * 它只决定"这一轮由谁删"，**不改变删除范围**（每个分片每轮都重新按时间筛，谁删到就是谁删到）。
 * 所以它可以安全并行——不存在"水位线 + 区间均分"那种"某个分片没跑完、区间就被推过去"的漏删。
 */
@Mapper
public interface ArchiveMapper {

    /** 消费去重记录：删 {@code consumed_at} 早于 cutoff 的行 */
    @Delete("DELETE FROM t_consume_record "
            + "WHERE consumed_at < #{cutoff} AND MOD(id, #{shardTotal}) = #{shardIndex} LIMIT #{batchSize}")
    int deleteConsumeRecordBefore(@Param("cutoff") LocalDateTime cutoff,
                                  @Param("shardIndex") int shardIndex,
                                  @Param("shardTotal") int shardTotal,
                                  @Param("batchSize") int batchSize);

    /** 重试账本：删 {@code created_at} 早于 cutoff 的行 */
    @Delete("DELETE FROM t_message_retry "
            + "WHERE created_at < #{cutoff} AND MOD(id, #{shardTotal}) = #{shardIndex} LIMIT #{batchSize}")
    int deleteMessageRetryBefore(@Param("cutoff") LocalDateTime cutoff,
                                 @Param("shardIndex") int shardIndex,
                                 @Param("shardTotal") int shardTotal,
                                 @Param("batchSize") int batchSize);

    /**
     * 发件箱：**只删 {@code status='SENT'}** 且 {@code sent_at} 早于 cutoff 的行。
     * 在途（PENDING/SENDING）与 FAILED（等人工介入）的行一律不碰——它们还有未完成的职责。
     */
    @Delete("DELETE FROM t_event_outbox "
            + "WHERE status = 'SENT' AND sent_at < #{cutoff} "
            + "AND MOD(id, #{shardTotal}) = #{shardIndex} LIMIT #{batchSize}")
    int deleteOutboxSentBefore(@Param("cutoff") LocalDateTime cutoff,
                               @Param("shardIndex") int shardIndex,
                               @Param("shardTotal") int shardTotal,
                               @Param("batchSize") int batchSize);

    /** 归档留痕：每（表 × 分片 × 轮次）一行 */
    @Insert("INSERT INTO t_archive_log "
            + "(job_key, ran_at, shard_index, shard_total, cutoff, deleted_rows, duration_ms, outcome) "
            + "VALUES (#{jobKey}, NOW(), #{shardIndex}, #{shardTotal}, #{cutoff}, #{deletedRows}, #{durationMs}, #{outcome})")
    int insertArchiveLog(@Param("jobKey") String jobKey,
                         @Param("shardIndex") int shardIndex,
                         @Param("shardTotal") int shardTotal,
                         @Param("cutoff") LocalDateTime cutoff,
                         @Param("deletedRows") long deletedRows,
                         @Param("durationMs") long durationMs,
                         @Param("outcome") String outcome);
}

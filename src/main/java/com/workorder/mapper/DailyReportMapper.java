package com.workorder.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日报汇总的 SQL 入口。**指标口径写在 {@code mapper/DailyReportMapper.xml} 的注释里**（唯一出处）。
 */
@Mapper
public interface DailyReportMapper {

    /** 某一天的日报：有则整行重算覆盖（幂等），无则插入 */
    int upsertDailyReport(@Param("day") LocalDate day,
                          @Param("dayStart") LocalDateTime dayStart,
                          @Param("dayEnd") LocalDateTime dayEnd,
                          @Param("shardTotal") int shardTotal);

    /** 读水位；无行返回 null（首次运行） */
    LocalDate selectWatermark(@Param("jobKey") String jobKey);

    /** 推水位（必须与当日结果行同事务——见 {@code DailyReportWriter}） */
    int upsertWatermark(@Param("jobKey") String jobKey, @Param("day") LocalDate day);

    /** 该日是否已有日报行（自愈判据） */
    int countReportRow(@Param("day") LocalDate day);
}

package com.workorder.scheduler;

import com.workorder.mapper.DailyReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 一天的日报写入：**结果行与水位推进在同一个事务里**。
 *
 * <p><b>为什么必须是独立 bean（而不是 {@link DailyReportJob} 自己的方法）</b>：Spring 的事务是代理生效的，
 * 类内自调用不会走代理 → {@code @Transactional} 形同虚设。这与 P4 的
 * {@code MessageRetryService} 是同一条理由（那边是 {@code REQUIRES_NEW} 隔离业务事务，这边是隔离"一天一个事务"）。
 *
 * <p><b>顺序不能反</b>：先写结果、后推水位。
 * 「先推水位再算」→ 崩在中间 = 那天**永久漏算**（水位已经过去了，没人会再算它）；
 * 「先算再推」→ 崩在中间 = 事务未提交，两者一起回滚，下一轮**重算一遍**（幂等，安全）。
 *
 * <p>补数模式（{@code advanceWatermark=false}）只写结果不碰水位：
 * 补数会往回算或往前跳，动水位要么把水位往回拉（之后重复算一大段）、要么跳过中间没算的天。
 */
@Component
@RequiredArgsConstructor
public class DailyReportWriter {

    private final DailyReportMapper dailyReportMapper;

    /**
     * 写一天（一个事务）。{@code REQUIRES_NEW} 让"一天"成为一个独立事务单元，
     * 前一天的提交不受后一天失败影响（失败的那天由异常中断整轮，水位停在最后成功的那天）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void writeDay(LocalDate day, String jobKey, boolean advanceWatermark, int shardTotal) {
        dailyReportMapper.upsertDailyReport(day, day.atStartOfDay(), day.plusDays(1).atStartOfDay(), shardTotal);
        if (advanceWatermark) {
            dailyReportMapper.upsertWatermark(jobKey, day);
        }
    }
}

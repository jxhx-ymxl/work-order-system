package com.workorder.scheduler;

import com.workorder.mapper.DailyReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 日报的写库动作：**分片写 part** 与 **收尾（主表 + 删 part + 推水位）**。
 *
 * <p><b>为什么必须是独立 bean（而不是 {@link DailyReportJob} 自己的方法）</b>：Spring 的事务是代理生效的，
 * 类内自调用不会走代理 → {@code @Transactional} 形同虚设。这与 P4 的
 * {@code MessageRetryService} 是同一条理由（那边是 {@code REQUIRES_NEW} 隔离业务事务，这边是隔离"一天一个事务"）。
 *
 * <p><b>收尾事务里顺序不能反</b>：先**认领**（删该日 part 行，删满 N 行才算认领成功）→ 再写主表结果 → 最后推水位。
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
     * 本分片的部分结果（一条语句）。
     *
     * <p>它**不加事务注解**：一条 INSERT ... ON DUPLICATE KEY UPDATE 本身就是原子的，
     * 而它也确实不该和"收尾"共享事务——收尾要等到所有分片都写完，两者时间上差着（可能差一整轮）。
     */
    public void writePart(LocalDate day, int shardIndex, int shardTotal) {
        dailyReportMapper.upsertDailyReportPart(day, day.atStartOfDay(), day.plusDays(1).atStartOfDay(),
                shardIndex, shardTotal);
    }

    /**
     * 收尾一天（一个事务）：**删该日 part 行（= 认领）+ 主表整天重算 + 推水位**，三者同进同退。
     *
     * <p>{@code REQUIRES_NEW} 让"一天"成为一个独立事务单元：前一天的提交不受后一天失败影响
     * （失败的那天会中断整轮，水位停在最后成功的那天）。
     *
     * <p><b>为什么用"删 part 行"来认领</b>：分片广播是"同时触发、跑完即返回"，没有 barrier，
     * 所以**有可能会出现两个分片同时看到"齐了"**（都读到 N 行）。删 part 行是原子的：
     * 只有一个分片能删满 N 行，另一个删到 0 行 → 抛 {@link PartClaimLostException} 并回滚（它什么都没写），
     * 由调用方当作"别的分片收尾了，本分片跳过"处理——**不是失败**。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void finalizeDay(LocalDate day, String jobKey, boolean advanceWatermark, int shardTotal) {
        int claimed = dailyReportMapper.deleteParts(day);
        // 判据是">= 分片数"而不是"== 分片数"：该日可能还有残留行（上一轮换了分片数留下的、
        // 或并发窗口里败方补写的），把"删到一整套"当成认领成功即可——多删的行本来就是垃圾。
        if (claimed < shardTotal) {
            // 别的分片先认领了（它已经把这天的 part 行删干净）：回滚，交调用方判为非失败
            throw new PartClaimLostException(day, claimed, shardTotal);
        }
        dailyReportMapper.upsertDailyReport(day, day.atStartOfDay(), day.plusDays(1).atStartOfDay(), shardTotal);
        if (advanceWatermark) {
            dailyReportMapper.upsertWatermark(jobKey, day);
        }
    }

    /** 收尾认领失败（另一个分片抢先收尾了）。**不是错误**，是并发下的正常结局。 */
    public static class PartClaimLostException extends RuntimeException {
        public PartClaimLostException(LocalDate day, int claimed, int expected) {
            super("收尾认领失败: day=" + day + " 实际删除 part 行=" + claimed + "，期望=" + expected
                    + "（多半是另一个分片同时收尾）");
        }
    }
}

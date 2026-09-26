package com.workorder.scheduler;

/**
 * xxl-job 分片参数的归一化（两个分片任务共用：{@link ArchiveJob} 与 {@link DailyReportJob}）。
 *
 * <p><b>分片数的唯一来源是执行器广播</b>（{@code XxlJobHelper.getShardTotal()/getShardIndex()}），
 * 不是任务参数——路由策略选"分片广播"时，调度中心会给每个执行器发一个 (index, total) 对。
 * 任务参数里的 {@code shardTotal} 只在"本地直接调用/手工触发"时做兜底，且**不作为业务判据**。
 */
final class XxlJobShards {

    private XxlJobShards() {
    }

    /**
     * 分片总数归一化：调度中心没给（&lt;=0，例如本地直接调用）时按"单分片"处理。
     * 这不是"兜底掩盖问题"——单分片本来就是合法形态（不分片的部署）；而 {@code shardTotal=-1} 直传进 SQL
     * 会让谓词恒假、**静默一行都不删/都不算**，那才是最坏的结果。
     */
    static int normalizeTotal(int shardTotal) {
        return shardTotal <= 0 ? 1 : shardTotal;
    }

    /** 分片下标归一化/校验：越界直接失败（宁可红一次，也不要某个分片静默不干活） */
    static int normalizeIndex(int shardIndex, int shardTotal) {
        if (shardIndex < 0) {
            return 0;
        }
        if (shardIndex >= shardTotal) {
            throw new IllegalArgumentException("分片下标越界: shardIndex=" + shardIndex + " shardTotal=" + shardTotal);
        }
        return shardIndex;
    }
}

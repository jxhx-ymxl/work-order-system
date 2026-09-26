package com.workorder.scheduler;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 归档/清理任务的**白名单**：只有这里列出的表才允许被 {@link ArchiveJob} 删除。
 *
 * <p><b>这是第一道硬约束</b>（见 {@code ArchiveJob} 类注释）：任务参数里的表名必须先在这里命中，
 * 否则直接失败——**业务表（工单、日志、通知、SLA 配置…）永远不在这个枚举里**，
 * 也就不可能因为一个参数写错而被删。
 *
 * <p><b>保留期锚点列</b>（{@code retentionColumn}）必须满足"这一行从此不再有业务价值"的语义：
 * <ul>
 *   <li>{@code t_consume_record.consumed_at}：消费完成时刻（幂等表，30 天口径见 init.sql 第 11 节）；</li>
 *   <li>{@code t_message_retry.created_at}：首次失败落库时刻（与上表同口径 30 天）；</li>
 *   <li>{@code t_event_outbox.sent_at}：**只删 {@code status='SENT'} 的行**——在途（PENDING/SENDING）
 *       与失败待人工（FAILED）的行一律不碰；锚点用 {@code sent_at}（投递成功时刻）而不是 {@code created_at}，
 *       因为"这条记录还有没有用"取决于它投出去多久了。</li>
 * </ul>
 *
 * <p><b>保留期由任务参数 {@code retentionDays} 决定，不在这里写死</b>（一处配置一个消费者：
 * 枚举里再存一份"默认保留期"就会变成第二处真相，而且没有任何代码会读它）。
 * 各表的**策略值**只是文档事实，写在 `sql/init.sql` 与 `deploy/UPGRADE-P6.md` 里：
 * 两张消费/重试表 **30 天**、outbox 的 SENT 行 **7 天**——所以把 {@code outbox_sent} 列进 {@code tables} 时
 * 要一起传 {@code retentionDays=7}；传 30 只会更保守（不会误删在途数据）。
 */
public enum ArchiveTarget {

    /** 消费去重记录：按 {@code consumed_at} 保留 30 天 */
    CONSUME_RECORD("consume_record", "t_consume_record", "consumed_at", null),

    /** 重试账本：按 {@code created_at} 保留 30 天 */
    MESSAGE_RETRY("message_retry", "t_message_retry", "created_at", null),

    /** 发件箱已投递行：按 {@code sent_at} 保留 **7 天**（比另两张表短），**只删 SENT** */
    OUTBOX_SENT("outbox_sent", "t_event_outbox", "sent_at", "status = 'SENT'");

    /** 参数里用的短名（{@code tables=consume_record,...}） */
    private final String key;

    /** 物理表名 */
    private final String table;

    /** 保留期锚点列 */
    private final String retentionColumn;

    /** 额外的固定谓词（如 {@code status = 'SENT'}），可为 null——**只用于生成日志里的谓词描述**，真实 SQL 在各 mapper 方法里静态写死 */
    private final String extraCondition;

    ArchiveTarget(String key, String table, String retentionColumn, String extraCondition) {
        this.key = key;
        this.table = table;
        this.retentionColumn = retentionColumn;
        this.extraCondition = extraCondition;
    }

    public String key() {
        return key;
    }

    public String table() {
        return table;
    }

    public String retentionColumn() {
        return retentionColumn;
    }

    /** 供日志用的谓词描述，例如 {@code status = 'SENT' AND sent_at < ?} */
    public String predicateDescription() {
        String time = retentionColumn + " < ?";
        return extraCondition == null ? time : extraCondition + " AND " + time;
    }

    /** 短名或物理表名都能命中（两种写法都接受，避免运维在参数里写全名就被拒） */
    public static Optional<ArchiveTarget> byKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String v = raw.trim();
        return Arrays.stream(values())
                .filter(t -> t.key.equalsIgnoreCase(v) || t.table.equalsIgnoreCase(v))
                .findFirst();
    }

    /** 全部白名单表（错误提示里用） */
    public static List<String> allKeys() {
        return Arrays.stream(values()).map(ArchiveTarget::key).toList();
    }
}

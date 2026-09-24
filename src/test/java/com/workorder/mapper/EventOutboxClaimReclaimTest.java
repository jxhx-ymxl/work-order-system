package com.workorder.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.entity.EventOutbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 抢占 / 回收 / 回写的 **SQL 语义**验证（在独立测试库上跑真 SQL）。
 *
 * <p>这三条是"不持有行锁做网络 IO + 抢占后崩溃可回收"的实现细节，只有真数据库能验：
 * 条件 UPDATE 的 WHERE 守卫、{@code AND status='SENDING'} 的乐观守卫、
 * 回收阈值对 {@code claimed_at} 的比较，Mockito 全都验不出来。
 *
 * <p>隔离：与 {@code OutboxWritePathTest} 同款做法——独立测试库 + id 水位线清理。
 *
 * <p><b>两个必须记住的坑（2026-09-24 实测踩到，P1 步骤 5 全量跑时报错）</b>：
 * <ol>
 *   <li>{@code claimPending} 是**全局带 LIMIT 的查询**（按 id 升序取前 N 条）。测试库若存在历史残留
 *       （例如上次运行被抢走后永久挂在 SENDING 的租约），LIMIT 会被残留占满，**本次新插入的行反而抢不到**。
 *       因此本类的抢占调用一律传一个远大于残留量的 LIMIT，断言里只关心自己插入的行。</li>
 *   <li>抢占会连**别人留下的**可投递行一起抢走（这是它的正常语义）。所以清理时除了按水位线删除自己插入的行，
 *       还要按 owner 清掉本次抢占留下的租约——否则那批行会永久停在 SENDING，既污染测试库，
 *       又让下一次运行撞上第 1 条。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class EventOutboxClaimReclaimTest {

    private static final String OWNER_A = "test-owner-a";
    private static final String OWNER_B = "test-owner-b";

    /** 见类注释第 1 条：远大于测试库可能存在的残留量，避免 LIMIT 被残留占满 */
    private static final int CLAIM_LIMIT = 100_000;

    @Autowired
    private EventOutboxMapper mapper;

    private Long watermark;

    @BeforeEach
    void setUp() {
        EventOutbox last = mapper.selectOne(new LambdaQueryWrapper<EventOutbox>()
                .select(EventOutbox::getId).orderByDesc(EventOutbox::getId).last("LIMIT 1"));
        watermark = last == null || last.getId() == null ? 0L : last.getId();
    }

    @AfterEach
    void cleanUp() {
        mapper.delete(new LambdaQueryWrapper<EventOutbox>().gt(EventOutbox::getId, watermark));
        // 清掉本次运行抢占留下的租约（可能包含不属于本类、由历史运行残留的可投递行）：
        // 不这么做就会留下永久 SENDING，把下次运行的 LIMIT 占满（见类注释第 2 条）
        mapper.delete(new LambdaQueryWrapper<EventOutbox>().eq(EventOutbox::getOwner, OWNER_A));
        mapper.delete(new LambdaQueryWrapper<EventOutbox>().eq(EventOutbox::getOwner, OWNER_B));
    }

    @Test
    @DisplayName("抢占只挑「PENDING 且已过退避」的记录；已被抢占的不会被第二个实例再抢")
    void claimPending_picksOnlyEligibleAndNeverSteals() {
        EventOutbox eligible = insert("PENDING", null, null);
        EventOutbox backoff = insert("PENDING", LocalDateTime.now().plusHours(1), null);

        // LIMIT 远大于库里残留量：本类只断言"自己插入的行被抢到"，不依赖测试库有多少历史行
        assertTrue(mapper.claimPending(OWNER_A, CLAIM_LIMIT) >= 1);

        EventOutbox claimed = mapper.selectById(eligible.getId());
        assertEquals("SENDING", claimed.getStatus());
        assertEquals(OWNER_A, claimed.getOwner(), "抢占必须写上 owner，投递侧才能只回写自己那批");
        assertNotNull(claimed.getClaimedAt(), "claimed_at 是回收判定「多久没动」的依据，必须写");

        EventOutbox untouched = mapper.selectById(backoff.getId());
        assertEquals("PENDING", untouched.getStatus(), "未过退避时间的记录不该被抢占");
        assertNull(untouched.getOwner());

        mapper.claimPending(OWNER_B, CLAIM_LIMIT);
        assertEquals(OWNER_A, mapper.selectById(eligible.getId()).getOwner(),
                "已被抢占（SENDING）的记录不得被第二个实例抢走，否则会重复投递");
    }

    @Test
    @DisplayName("回收：超过阈值无进展的 SENDING 改回 PENDING；刚抢占的不动")
    void reclaimStale_resetsOnlyStaleRows() {
        EventOutbox stale = insert("SENDING", null, 10); // claimed_at = now-10min
        EventOutbox fresh = insert("SENDING", null, 0);

        assertTrue(mapper.reclaimStale(5) >= 1);

        EventOutbox reclaimed = mapper.selectById(stale.getId());
        assertEquals("PENDING", reclaimed.getStatus(), "抢占后崩溃留下的中间态必须能回收");
        assertNull(reclaimed.getOwner());
        assertEquals("SENDING", mapper.selectById(fresh.getId()).getStatus(),
                "刚抢占的记录不能被回收，否则会与正在投递的实例重复投递");
    }

    @Test
    @DisplayName("回写守卫：SENDING → SENT 写 sent_at；重复回写不再命中")
    void markSent_onlyAppliesToSending() {
        EventOutbox row = insert("SENDING", null, 0);

        assertEquals(1, mapper.markSent(row.getId()));
        EventOutbox sent = mapper.selectById(row.getId());
        assertEquals("SENT", sent.getStatus());
        assertNotNull(sent.getSentAt());
        assertNull(sent.getOwner(), "投递结束必须清掉租约标记");
        assertNull(sent.getClaimedAt());
        assertEquals(0, mapper.markSent(row.getId()), "已不是 SENDING 的记录不得被再次回写（守卫失效会让状态机失真）");
    }

    @Test
    @DisplayName("失败回写：retry_count 加一、写退避时间；退回抢占不增加 retry_count")
    void failureIncrementsRetryCount_butReleaseDoesNot() {
        EventOutbox failed = insert("SENDING", null, 0);
        EventOutbox released = insert("SENDING", null, 0);
        LocalDateTime backoff = LocalDateTime.now().plusSeconds(30);

        assertEquals(1, mapper.markAttemptFailed(failed.getId(), "PENDING", backoff));
        assertEquals(1, mapper.releaseClaim(released.getId()));

        EventOutbox afterFailure = mapper.selectById(failed.getId());
        assertEquals("PENDING", afterFailure.getStatus());
        assertEquals(1, afterFailure.getRetryCount(), "失败必须计数，否则达到上限转 FAILED 的判定永远不会触发");
        assertNotNull(afterFailure.getNextRetryAt());
        assertNull(afterFailure.getOwner());

        EventOutbox afterRelease = mapper.selectById(released.getId());
        assertEquals("PENDING", afterRelease.getStatus());
        assertEquals(0, afterRelease.getRetryCount(), "没被尝试过的记录不是失败，不该累计次数");
        assertNull(afterRelease.getOwner());
    }

    @Test
    @DisplayName("按 owner 取回本批记录：不会取到别的实例的租约")
    void selectClaimedBy_isScopedToOwner() {
        EventOutbox mine = insert("SENDING", null, 0);
        EventOutbox other = insert("SENDING", null, 0);
        other.setOwner(OWNER_B);
        mapper.updateById(other);

        List<EventOutbox> mineRows = mapper.selectClaimedBy(OWNER_A);
        assertTrue(mineRows.stream().anyMatch(r -> r.getId().equals(mine.getId())),
                "本实例抢占的记录必须能取回");
        assertTrue(mineRows.stream().noneMatch(r -> r.getId().equals(other.getId())),
                "别的实例的租约不能被取回，否则会替别人回写状态");
    }

    // ────────────── helpers ──────────────

    /**
     * @param status         行状态
     * @param nextRetryAt    下次可重试时间（null=立即可投）
     * @param claimedMinutesAgo 抢占时间距现在几分钟（null=不写，模拟 PENDING）
     */
    private EventOutbox insert(String status, LocalDateTime nextRetryAt, Integer claimedMinutesAgo) {
        EventOutbox row = new EventOutbox();
        row.setEventId("test:" + java.util.UUID.randomUUID() + ":ORDER_RELEASE_CHECK");
        row.setEventType("ORDER_RELEASE_CHECK");
        row.setAggregateId(1L);
        row.setAggregateVersion(1);
        row.setPayload("{\"orderId\":1}");
        row.setDeliverAt(LocalDateTime.now());
        row.setOccurredAt(LocalDateTime.now());
        row.setStatus(status);
        row.setRetryCount(0);
        row.setNextRetryAt(nextRetryAt);
        if (claimedMinutesAgo != null) {
            row.setClaimedAt(LocalDateTime.now().minusMinutes(claimedMinutesAgo));
            row.setOwner(OWNER_A);
        }
        mapper.insert(row);
        return row;
    }
}

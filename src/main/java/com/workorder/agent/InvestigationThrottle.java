package com.workorder.agent;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * **受理层的两道闸门**（`docs/AGENT-PLAN.md` §4.3）：用户级频率限制 + 全局模型调用预算。
 *
 * <p>与 {@link AgentInvestigationService} 的**并发位**三者正交，别混为一谈：
 * <ul>
 *   <li>**并发位**（D93）＝**同时刻**最多几个在跑；</li>
 *   <li>**频率限制**（本类）＝**单位时间内每人几次**；</li>
 *   <li>**全局预算**（本类）＝**累计总量**上限（首版按"模型调用次数"计，不按 token——§3.4 已说明
 *       token 拿不到跨供应商一致的 usage，拿它当上限会变成假保护）。</li>
 * </ul>
 * 三者会**相乘**：容量 1 + 每人每分钟 3 次 + 全局 1000 次，意味着"总量受预算、节奏受频率、并发受容量"。
 *
 * <p><b>计数载体是进程内</b>：重启即丢、多实例各自独立（§4.3 明写"不是分布式配额"）。
 * 所以它是**成本护栏**，不是计费依据。
 *
 * <p>**时钟可注入**：窗口行为必须能在测试里稳定复现——靠 `sleep` 等窗口过去既慢又脆。
 */
public final class InvestigationThrottle {

    /** 不限制（测试/未装配时用）：窗口与上限都放到不可达。 */
    public static final InvestigationThrottle PERMISSIVE = new InvestigationThrottle(
            Duration.ofSeconds(60), Integer.MAX_VALUE, Long.MAX_VALUE, Clock.systemDefaultZone());

    private final Duration window;
    private final int maxPerUser;
    private final long globalModelCallBudget;
    private final Clock clock;

    /** 每用户一个时间戳队列（**进程内**，重启即丢）。 */
    private final Map<Long, Deque<Long>> userHits = new ConcurrentHashMap<>();
    /** 全局累计"模型调用次数"（逻辑轮次；重试的物理次数当前不可见，见 D92 的登记）。 */
    private final AtomicLong modelCalls = new AtomicLong();

    public InvestigationThrottle(Duration window, int maxPerUser, long globalModelCallBudget, Clock clock) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("窗口必须为正");
        }
        if (maxPerUser < 1) {
            throw new IllegalArgumentException("每用户上限至少 1");
        }
        if (globalModelCallBudget < 0) {
            throw new IllegalArgumentException("全局预算不得为负");
        }
        this.window = window;
        this.maxPerUser = maxPerUser;
        this.globalModelCallBudget = globalModelCallBudget;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    /**
     * 该不该拒绝这次请求；**返回 null = 放行**。
     *
     * <p>判断必须在**发起任何模型调用之前**（"不能扣在事后"）；放行时**立即记账**，
     * 否则"并发进来的两个请求"会都读到同一个旧计数。
     */
    public synchronized String rejectReason(Long userId) {
        long now = clock.millis();
        if (modelCalls.get() >= globalModelCallBudget) {
            return "BUDGET_EXHAUSTED";
        }
        Deque<Long> hits = userHits.computeIfAbsent(userId, key -> new ArrayDeque<>());
        while (!hits.isEmpty() && hits.peekFirst() <= now - window.toMillis()) {
            hits.pollFirst();   // 滑出窗口
        }
        if (hits.size() >= maxPerUser) {
            return "RATE_LIMITED";
        }
        hits.addLast(now);
        return null;
    }

    /** 调查结束后记账（**模型调用次数**）：不合格的调用不会走到这里。 */
    public void recordModelCalls(int calls) {
        if (calls > 0) {
            modelCalls.addAndGet(calls);
        }
    }

    /** 当前累计模型调用次数（排障/测试用）。 */
    public long modelCalls() {
        return modelCalls.get();
    }
}

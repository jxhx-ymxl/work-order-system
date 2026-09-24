package com.workorder.config;

import com.workorder.service.OrderTriageService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 启动自检（第三类：**外部依赖可用性**）：LLM 能不能用（P5 收口）。
 *
 * <p>与既有两类自检的关系（模式一致，语义不同）：
 * <ul>
 *   <li>{@code DataSourceAvailabilityCheck}：数据库连不上 → **fail-fast**（连不上库的应用没有价值）；</li>
 *   <li>{@code SlaConfigStartupCheck}：配置缺行 → 记 error、**不阻止启动**（服务仍有价值，让问题可见）；</li>
 *   <li>本类：LLM 不可用 → 记 error、**不阻止启动**。理由：**"LLM 不可用时提交仍成功"是设计好的降级路径**，
 *       让一个可降级依赖把服务拦住属于把能力问题升级成可用性问题。</li>
 * </ul>
 *
 * <p><b>为什么值得一次探测而不是只检查组配没配</b>：配了但"模型名不对（400）/key 无效（401）/不可达（超时）"
 * 在运行期的表现与"没配"完全一样——全是静默降级成 OTHER/普通。启动期打一次最小请求，
 * 把这三种原因分别打出来，用户一眼能分辨该改哪个变量。
 *
 * <p><b>代价</b>：配置了 LLM 时会多一次外部请求，最坏要等满 {@code llm.api.timeout}（默认 5s）才继续启动
 * ——但**不阻止启动**；且只在应用启动时发生一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmStartupCheck {

    private final OrderTriageService orderTriageService;

    @PostConstruct
    public void checkLlmUsable() {
        String failure = orderTriageService.probeFailure();
        if (failure == null) {
            log.info("[启动自检] LLM 探测通过（triage 可用）");
            return;
        }
        if (failure.startsWith("未配置")) {
            log.error("[启动自检] {}，triage 将始终降级为 OTHER/普通（提交仍成功，不是故障，但分类会失真）。"
                            + "排查：① 确认 deploy/.env 里填了 LLM_API_URL / LLM_API_KEY / LLM_MODEL；"
                            + "② 确认变量经 compose 传进了容器（部署冒烟项，见 deploy/UPGRADE-P1.md §6.4）；"
                            + "③ 重建容器 docker compose up -d backend。详见 README 排障章节",
                    failure);
        } else {
            log.error("[启动自检] LLM 探测失败：{}。triage 将降级为 OTHER/普通（**不阻止启动**：让问题可见，不让服务不可用）。"
                            + "排查三步见 README 排障章节", failure);
        }
    }
}

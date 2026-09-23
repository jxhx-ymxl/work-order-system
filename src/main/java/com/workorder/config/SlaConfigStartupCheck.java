package com.workorder.config;

import com.workorder.entity.SlaConfig;
import com.workorder.mapper.SlaConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 启动自检（第一类：**配置完整性**）：{@code t_sla_config} 必须覆盖 4 类 × 2 优先级共 8 条
 * （见 INVARIANTS.md I5）。
 *
 * <p><b>启动期检查有两类语义，必须分清，不得混在一起：</b>
 * <ol>
 *   <li><b>配置完整性（本类）</b>：表查得到、但行不全 → 记 {@code error} 日志，<b>不中止启动</b>。
 *       理由：漏配只影响部分工单的 SLA，服务仍有价值，让问题可见即可（与
 *       {@code UserServiceImpl.ensureAdminSurvival()} 同一模式）。</li>
 *   <li><b>依赖可用性（{@link DataSourceAvailabilityCheck}）</b>：数据库连不上 → <b>必须 fail-fast</b>。
 *       连不上库的应用没有价值，让它挂着"健康"假象比启动失败更危险。</li>
 * </ol>
 *
 * <p><b>因此本类刻意不再捕获数据库异常</b>：查询本身抛错（连不上库、表不存在）会向上传播并中止启动；
 * 只有"查得到但缺行"才走 {@code log.error} 后继续。历史教训：早期版本用
 * {@code try/catch (Exception)} 把连接失败也吞成一行日志，导致"连不上库"被降级成警告、
 * 应用照常启动（实测现象：Tomcat 起、Started WorkOrderApplication，直到首次访问数据库才报
 * {@code CannotGetJdbcConnectionException}）。
 *
 * <p>构造函数注入 {@link DataSourceAvailabilityCheck} 是**故意的**：它保证"依赖可用性检查"
 * 先于本类执行，否则本类会先撞上连不上库的异常，报错信息会指向配置而不是依赖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaConfigStartupCheck {

    /** 与 WorkOrderServiceImpl.ALLOWED_TYPES 保持一致（R4 的新类型集合） */
    private static final List<String> REQUIRED_TYPES = List.of("NETWORK", "UTILITY", "DORM", "OTHER");
    private static final List<Integer> REQUIRED_PRIORITIES = List.of(0, 1);

    private final SlaConfigMapper slaConfigMapper;

    /** 仅用于强制 Bean 创建顺序：依赖可用性检查必须先跑（见类注释） */
    private final DataSourceAvailabilityCheck dataSourceAvailabilityCheck;

    @PostConstruct
    public void ensureSlaConfigComplete() {
        // 不捕获异常：连不上库属于"依赖不可用"，必须中止启动（由 DataSourceAvailabilityCheck 先报更清晰的错）；
        // 表存在但缺行属于"配置不完整"，在 checkAndReport() 内部记 error 后继续。
        checkAndReport();
    }

    private void checkAndReport() {
        List<SlaConfig> existing = slaConfigMapper.selectList(null);
        Set<String> present = existing.stream()
                .map(c -> c.getType() + "//" + c.getPriority())
                .collect(Collectors.toSet());

        List<String> missing = new ArrayList<>();
        for (String type : REQUIRED_TYPES) {
            for (Integer priority : REQUIRED_PRIORITIES) {
                if (!present.contains(type + "//" + priority)) {
                    missing.add(type + "//" + priority);
                }
            }
        }

        int expected = REQUIRED_TYPES.size() * REQUIRED_PRIORITIES.size();
        if (missing.isEmpty()) {
            log.info("[启动自检] t_sla_config 覆盖完整：{}/{} 个组合", expected, expected);
        } else {
            log.error("[启动自检] t_sla_config 缺失 {} 个组合（期望 {} 条，当前表内 {} 行）：{}。"
                            + "缺失组合的工单会走兜底配置（OTHER//0），不会落 NULL，但类型/优先级语义会失真。"
                            + "补齐方式见 sql/init.sql 的 R4 待办（P0b 类型枚举替换）",
                    missing.size(), expected, existing.size(), missing);
        }
    }
}

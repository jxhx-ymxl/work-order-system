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
 * 启动自检：{@code t_sla_config} 必须覆盖 4 类 × 2 优先级共 8 条（见 INVARIANTS.md I5）。
 *
 * <p>与 {@code UserServiceImpl.ensureAdminSurvival()} 同一模式：@PostConstruct 触发，
 * <b>只让问题可见，不阻止应用启动</b>——自检的目的是暴露漏配，不是让服务不可用。
 *
 * <p>放在 config 包的理由：它与其余启动期组件（Redis/Sa-Token/MyBatis 配置）同类，
 * 不承担业务逻辑，也不需要 service 接口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaConfigStartupCheck {

    /** 与 WorkOrderServiceImpl.ALLOWED_TYPES 保持一致（R4 的新类型集合） */
    private static final List<String> REQUIRED_TYPES = List.of("NETWORK", "UTILITY", "DORM", "OTHER");
    private static final List<Integer> REQUIRED_PRIORITIES = List.of(0, 1);

    private final SlaConfigMapper slaConfigMapper;

    @PostConstruct
    public void ensureSlaConfigComplete() {
        try {
            checkAndReport();
        } catch (Exception e) {
            // 关键：自检"只让问题可见，不阻止启动"——因此任何异常都必须被吞掉并记录。
            // 反例：若让异常抛出，@PostConstruct 失败会导致整个 Spring 上下文启动失败，
            // 与"不阻止应用启动"的设计直接矛盾（P0a 实测踩到过：本地库连不上时启动直接被中止）。
            log.error("[启动自检] 校验 t_sla_config 失败（不影响启动，但请检查数据库连接与表结构）: {}", e.getMessage());
        }
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

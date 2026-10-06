package com.workorder.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 默认关：`agent.investigation.enabled` 不设时，agent 侧的 bean **一个都不装配**。
 *
 * <p>为什么必须钉住：否则 28 个既有 `@SpringBootTest` 会连带装配 agent bean（拖慢、并可能新增失败），
 * 与 `XxlJobConfig` 的"默认关"惯例一致。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("S4 接线：开关默认关")
class AgentConfigurationDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("不设 agent.investigation.enabled → 受理层与循环 bean 均不存在")
    void agentBeansAreAbsentByDefault() {
        assertEquals(0, context.getBeanNamesForType(AgentInvestigationService.class).length, "受理层不该装配");
        assertEquals(0, context.getBeanNamesForType(InvestigationAgent.class).length, "agent 循环不该装配");
        assertEquals(0, context.getBeanNamesForType(FixedFlowInvestigator.class).length, "基线不该装配");
    }
}

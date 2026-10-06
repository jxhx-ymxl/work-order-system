package com.workorder.config;

import com.workorder.agent.AgentInvestigationService;
import com.workorder.agent.AgentLimits;
import com.workorder.agent.AgentReportRenderer;
import com.workorder.agent.AgentToolRegistry;
import com.workorder.agent.FixedFlowInvestigator;
import com.workorder.agent.HttpAgentModel;
import com.workorder.agent.InvestigationAgent;
import com.workorder.agent.tool.DeptComparisonTool;
import com.workorder.agent.tool.OrderFactsTool;
import com.workorder.agent.tool.ReadEarlierEventsTool;
import com.workorder.agent.tool.ReadSlaContextTool;
import com.workorder.mapper.SlaConfigMapper;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.WorkOrderService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 工单调查助手的装配（S4 接线第一片）。
 *
 * <p><b>默认关</b>（{@code agent.investigation.enabled=false}）——与 `XxlJobConfig` 同一惯例：
 * 否则 28 个既有 `@SpringBootTest` 会连带装配 agent bean（拖慢并可能新增失败），
 * 而这条线的当前状态是"零件齐、默认不启用"。
 *
 * <p><b>默认模式 fixed</b>（{@code agent.investigation.mode=fixed}）——依据 §1 第八题：
 * 没有可证明的净收益就不把 agent 设为业务默认；mode=agent 是**对照用**，不是默认。
 */
@Configuration
@ConditionalOnProperty(name = "agent.investigation.enabled", havingValue = "true")
public class AgentConfiguration {

    @Bean
    public AgentLimits agentLimits() {
        return AgentLimits.s1Defaults();
    }

    @Bean
    public AgentToolRegistry agentToolRegistry(WorkOrderMapper workOrderMapper,
                                              WorkOrderLogMapper workOrderLogMapper,
                                              UserMapper userMapper,
                                              SlaConfigMapper slaConfigMapper) {
        return new AgentToolRegistry(List.of(
                new OrderFactsTool(workOrderMapper, workOrderLogMapper, userMapper),
                new DeptComparisonTool(workOrderMapper, userMapper),
                // 设计稿 L85 / L87 的两个只读工具（输入与约束严格照设计稿，不自行扩范围）
                new ReadEarlierEventsTool(workOrderMapper, workOrderLogMapper, userMapper),
                new ReadSlaContextTool(workOrderMapper, userMapper, slaConfigMapper)));
    }

    @Bean
    public HttpAgentModel agentModel(AgentToolRegistry agentToolRegistry, AgentLimits agentLimits,
                                     @Value("${llm.api.url:}") String apiUrl,
                                     @Value("${llm.api.key:}") String apiKey,
                                     @Value("${llm.api.model:}") String model) {
        return new HttpAgentModel(apiUrl, apiKey, model, agentToolRegistry.definitions(),
                agentLimits.modelConnectTimeout(), agentLimits.modelReadTimeout(),
                agentLimits.maxModelResponseBytes());
    }

    @Bean
    public InvestigationAgent investigationAgent(HttpAgentModel agentModel,
                                                AgentToolRegistry agentToolRegistry,
                                                AgentLimits agentLimits) {
        return new InvestigationAgent(agentModel, agentToolRegistry, agentLimits);
    }

    @Bean
    public FixedFlowInvestigator fixedFlowInvestigator(AgentToolRegistry agentToolRegistry,
                                                      AgentLimits agentLimits) {
        return new FixedFlowInvestigator(agentToolRegistry, agentLimits);
    }

    @Bean
    public AgentReportRenderer agentReportRenderer() {
        return new AgentReportRenderer();
    }

    @Bean
    public AgentInvestigationService agentInvestigationService(InvestigationAgent investigationAgent,
                                                               FixedFlowInvestigator fixedFlowInvestigator,
                                                               AgentReportRenderer agentReportRenderer,
                                                               WorkOrderService workOrderService,
                                                               @Value("${agent.investigation.mode:fixed}") String mode) {
        return new AgentInvestigationService(investigationAgent, fixedFlowInvestigator,
                agentReportRenderer, mode, workOrderService);
    }
}

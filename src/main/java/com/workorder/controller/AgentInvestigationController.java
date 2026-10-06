package com.workorder.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.workorder.agent.AgentInvestigationService;
import com.workorder.common.ErrorCode;
import com.workorder.common.Result;
import com.workorder.common.dto.AgentInvestigationReq;
import com.workorder.common.vo.AgentInvestigationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * **调查接口（同步版）**：`POST /api/agent/investigations`。
 *
 * <p><b>默认关</b>（`agent.investigation.enabled=true` 才注册）：与 `XxlJobConfig` / `AgentConfiguration`
 * 同一惯例——开关关时**这个 bean 根本不存在**，所以路径表现为 **404**；
 * **刻意不注册一个"永远返回 501"的空壳**：那会让"功能没开"和"功能坏了"在监控里长得一样。
 *
 * <p><b>身份</b>：调用者 id 从 Sa-Token 取（沿用 `WorkOrderController` 的既有写法），
 * 再交给 {@link AgentInvestigationService#investigate} —— 部门范围与准入判定都在受理层
 * （§11-2 / D79 / D85），**controller 不自己判权限**。
 *
 * <p><b>本轮不做</b>：异步（`Callable`/`DeferredResult`）、任务状态存储、取消——那是 S4 的下一片。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Tag(name = "工单调查助手（同步）")
@SaCheckLogin
@ConditionalOnProperty(name = "agent.investigation.enabled", havingValue = "true")
public class AgentInvestigationController {

    private final AgentInvestigationService agentInvestigationService;

    @PostMapping("/investigations")
    @Operation(summary = "发起一次调查（同步；只读）")
    public Result<AgentInvestigationVO> investigate(@Valid @RequestBody AgentInvestigationReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        AgentInvestigationService.Outcome outcome =
                agentInvestigationService.investigate(userId, req.getOrderNo(), req.getQuestion());

        if ("COMPLETED".equals(outcome.status())) {
            return Result.ok(toVO(outcome));
        }
        if ("INCOMPLETE".equals(outcome.status()) || "CANCELLED".equals(outcome.status())) {
            // **未完成是业务状态，不是系统失败**（D86）：要把"部分已核实事实 + 未完成 + 原因码"呈现给用户，
            // 所以走 Result.ok，但 **report 字段一律为 null**（`report == null` 的不变量不被绕过）。
            // `CANCELLED(PERMISSION_REVOKED)` 同理（§3.3 / L116）：它是"已取消 + 原因码"，不是服务器错误。
            // 客户端必须看 `status`，不能把 code=200 读成"调查成功"。
            return Result.ok(toVO(outcome));
        }
        // 其余终态：**不产出报告**（与 AgentRunResult 的构造期不变量一致）。
        // 越权走业务码 FORBIDDEN；模型/预算/报告校验等是服务端失败，归 INTERNAL_ERROR。
        String reason = outcome.failureCode() == null ? "未知" : outcome.failureCode();
        return "FORBIDDEN".equals(outcome.failureCode())
                ? Result.fail(ErrorCode.FORBIDDEN, "无权调查该工单（受理层拒绝）")
                : Result.fail(ErrorCode.INTERNAL_ERROR, "调查未完成：" + reason);
    }

    private static AgentInvestigationVO toVO(AgentInvestigationService.Outcome outcome) {
        AgentInvestigationVO vo = new AgentInvestigationVO();
        vo.setStatus(outcome.status());
        vo.setFailureCode(outcome.failureCode());
        vo.setRenderedText(outcome.renderedText());
        if (outcome.report() != null) {
            vo.setProblemType(outcome.report().problemType() == null
                    ? null : outcome.report().problemType().name());
            vo.setEvidenceIds(outcome.report().evidenceIds());
            vo.setSuggestionIds(outcome.report().suggestionIds());
        }
        return vo;
    }
}

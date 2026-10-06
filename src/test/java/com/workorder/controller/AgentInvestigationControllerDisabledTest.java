package com.workorder.controller;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 开关**默认关**时的对外表现：`/api/agent/investigations` **不存在**（404）。
 *
 * <p>刻意不注册"永远返回 501 的空壳"：那会让"功能没开"与"功能坏了"在监控里长得一样。
 * 这里**不设** `agent.investigation.enabled`（与 28 个既有 `@SpringBootTest` 的环境一致）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("调查接口：开关默认关 → 路径不存在")
class AgentInvestigationControllerDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void logout() {
        StpUtil.logout();
    }

    @Test
    @DisplayName("开关关（默认）+ 已登录 → POST 该路径 404（controller 压根没注册，不是 501 空壳）")
    void pathIsNotFoundWhenDisabled() throws Exception {
        mockMvc.perform(post("/api/agent/investigations")
                        .header("Authorization", tokenOf(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"WO-20261006-00001\",\"question\":\"到哪一步了？\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * 未登录时**先**撞到全局登录拦截器（`SaTokenConfig` 覆盖 `/api/**`），所以看到的是
     * `HTTP 200 + code=401` 而不是 404——那是**本项目对所有未知 `/api` 路径的既有行为**，
     * 不是本接口特有的。这条写下来，免得下一个人以为"开关关怎么不是 404"。
     */
    @Test
    @DisplayName("开关关 + 未登录 → 先被全局登录拦截器拦下：HTTP 200 + 业务码 401（不是 404）")
    void unauthenticatedHitsTheGlobalInterceptorFirst() throws Exception {
        mockMvc.perform(post("/api/agent/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"WO-20261006-00001\",\"question\":\"到哪一步了？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    private static String tokenOf(long userId) {
        StpUtil.login(userId);
        return StpUtil.getTokenValue();
    }
}

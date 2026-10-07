package com.workorder.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.workorder.entity.Dept;
import com.workorder.mapper.DeptMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 部门实体化（2026-10-08）的对外判据：新增 / 改名 / 启停、重名 → 400、注册校验、公开下拉只给启用项。
 *
 * <p>判据一律落在**业务 code**（`$.code`）上——本项目"参数错误 / 无权限 / 未登录"都是
 * **HTTP 200 + 业务码**（`GlobalExceptionHandler`），只看 HTTP 状态会把它们读成"成功"（CLAUDE §6 第 6 项）。
 *
 * <p>每个用例都建自己的部门（名字带随机后缀，避免与库里的存量行撞唯一键）；`@Transactional` 让改动回滚。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("部门管理：新增/改名/启停 + 重名 400 + 注册校验 + 公开下拉只给启用项")
class DeptManageControllerTest {

    /**
     * 用**种子里就有的** admin（id=1、SYS_ADMIN，`init.sql` 已绑定 `system:dept:manage`）。
     *
     * <p>为什么不在测试里新建一个管理员：本类**在整套里跑时**踩过一次——自建的用户与其角色绑定写在
     * 测试事务里，而权限查询（`StpInterfaceImpl`）在那一轮里看不到未提交的行，于是
     * `@SaCheckPermission` 一律 403（单跑本类却是绿的）。用种子账号就没有这层依赖。
     */
    private static final long ADMIN_ID = 1L;
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DeptMapper deptMapper;
    private String login(long userId) {
        StpUtil.login(userId);
        return StpUtil.getTokenValue();
    }

    /** 一名持有 SYS_ADMIN 的账号（`system:dept:manage` 就从它身上来）。 */
    private String adminToken() {
        return login(ADMIN_ID);
    }

    /**
     * 名字用 ASCII：`MockHttpServletRequestBuilder.content(String)` 的默认字符集不是 UTF-8，
     * 用中文会在**请求侧**就坏掉（那是测试自己的坑，不是被测代码的）；中文进库另有种子行覆盖。
     */
    private static String uniqueName(String prefix) {
        return prefix + "-" + System.nanoTime() % 1_000_000 + "-" + SEQ.incrementAndGet();
    }

    private static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private Dept insertDept(String name, int enabled) {
        Dept dept = new Dept();
        dept.setName(name);
        dept.setEnabled(enabled);
        deptMapper.insert(dept);
        return dept;
    }

    // ---------- ① 新增 / 改名 / 启停 ----------

    @Test
    @DisplayName("① 新增 → 改名 → 停用：三次都回业务码 200，字段如实回显（停用项仍在管理端列表里）")
    void createRenameAndDisable() throws Exception {
        String token = adminToken();
        String original = uniqueName("dept-create");
        String renamed = uniqueName("dept-renamed");

        String createdBody = mockMvc.perform(post("/api/admin/depts")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(body("{\"name\":\"" + original + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value(original))
                .andExpect(jsonPath("$.data.enabled").value(1))
                .andExpect(jsonPath("$.data.userCount").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = idOf(createdBody);

        mockMvc.perform(put("/api/admin/depts/" + id)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(body("{\"name\":\"" + renamed + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value(renamed));

        mockMvc.perform(put("/api/admin/depts/" + id)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(body("{\"enabled\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enabled").value(0));

        // 落库核对：改名 + 停用都生效（判据落在数据上，不只看响应回显）
        Dept stored = deptMapper.selectById(id);
        assertNotNull(stored);
        assertEquals(renamed, stored.getName());
        assertEquals(0, stored.getEnabled());

        // 管理端列表**含停用项**（"删除"= 停用，行不能消失）
        mockMvc.perform(get("/api/admin/depts").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(containsString("\"name\":\"" + renamed + "\"")));
    }

    // ---------- ② 重名 → 400 ----------

    @Test
    @DisplayName("② 重名 → 业务码 400（新增与改名两条路都拦）")
    void duplicateNameIsRejected() throws Exception {
        String token = adminToken();
        String name = uniqueName("dept-dup");
        insertDept(name, 1);
        Dept other = insertDept(uniqueName("dept-other"), 1);

        mockMvc.perform(post("/api/admin/depts")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(body("{\"name\":\"" + name + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/api/admin/depts/" + other.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(body("{\"name\":\"" + name + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ---------- ③ 注册校验 ----------

    @Test
    @DisplayName("③ 注册：deptId 不存在 → 400；deptId 已停用 → 400；deptId 为空仍可注册")
    void registerValidatesDeptSelectable() throws Exception {
        Dept disabled = insertDept(uniqueName("dept-disabled"), 0);
        String missingId = "999999";

        String missingBody = "{\"username\":\"" + uniqueName("u") + "\",\"password\":\"pwd123\",\"deptId\":" + missingId + "}";
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(body(missingBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        String disabledBody = "{\"username\":\"" + uniqueName("u") + "\",\"password\":\"pwd123\",\"deptId\":" + disabled.getId() + "}";
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(body(disabledBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 不选部门仍然允许（既有语义没变）
        String noDeptBody = "{\"username\":\"" + uniqueName("u") + "\",\"password\":\"pwd123\"}";
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(body(noDeptBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ---------- ④ 停用项不出现在 /api/depts ----------

    @Test
    @DisplayName("④ /api/depts 免登录可读，且**只**返回启用项（停用项不在其中）")
    void publicDirectoryIsAnonymousAndHidesDisabled() throws Exception {
        Dept enabled = insertDept(uniqueName("dept-enabled"), 1);
        Dept disabled = insertDept(uniqueName("dept-off"), 0);

        mockMvc.perform(get("/api/depts"))   // 刻意**不带** Authorization
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(containsString("\"" + enabled.getName() + "\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(not(containsString("\"" + disabled.getName() + "\""))))
                // 公开下拉只给 id + name（不带 enabled / userCount 这些管理端字段）
                .andExpect(jsonPath("$.data[0].enabled").doesNotExist())
                .andExpect(jsonPath("$.data[0].userCount").doesNotExist());
    }

    /** 从创建响应里取回 id（避免再查一次库、也避免依赖 @Transactional 内的可见性）。 */
    private static long idOf(String json) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("响应里没有 id：" + json);
        }
        return Long.parseLong(matcher.group(1));
    }
}

package com.workorder.agent.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * S5 的**一次性夹具**：建 `work_order_s5`（结构克隆自 `work_order_test`），
 * 复制参考行（`t_role` / `t_user` / `t_user_role` / `t_sla_config`），
 * 再把 admin（user_id=1）变成某个部门的 `DEPT_ADMIN` 并造一张起点单。
 *
 * <p>**不碰** `work_order_test` 的数据（只读它的结构与参考行）、**不碰**另一个项目。
 * 类名不带 `Test` → 默认 `mvn test` 不会跑它。
 */
@DisplayName("S5 夹具（建 work_order_s5）")
class S5FixtureHarness {

    private static final String SOURCE = "work_order_test";
    private static final String TARGET = "work_order_s5";
    private static final long DEPT = 9L;

    @Test
    @DisplayName("建库 + 克隆结构 + 复制参考行 + 造一张起点单")
    void build() throws Exception {
        String url = "jdbc:mysql://localhost:" + port()
                + "/?useUnicode=true&characterEncoding=utf8&connectionTimeZone=%2B08:00&useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection conn = DriverManager.getConnection(url, user(), password());
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + TARGET + "`");
            st.execute("CREATE DATABASE `" + TARGET + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");

            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema='" + SOURCE + "' AND table_type='BASE TABLE' ORDER BY table_name")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String table : tables) {
                st.execute("CREATE TABLE `" + TARGET + "`.`" + table + "` LIKE `" + SOURCE + "`.`" + table + "`");
            }
            for (String table : List.of("t_role", "t_user", "t_user_role", "t_sla_config")) {
                if (tables.contains(table)) {
                    st.execute("INSERT INTO `" + TARGET + "`.`" + table + "` SELECT * FROM `" + SOURCE + "`.`" + table + "`");
                }
            }
            // admin（user_id=1）变成 DEPT 9 的部门主管：受理层只放行 DEPT_ADMIN + 有部门
            st.execute("UPDATE `" + TARGET + "`.`t_user` SET dept_id=" + DEPT + " WHERE id=1");
            st.execute("INSERT INTO `" + TARGET + "`.`t_user_role` (user_id, role_id) VALUES (1, 4)");

            String orderNo = "WO-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-90001";
            st.execute("INSERT INTO `" + TARGET + "`.`t_work_order` "
                    + "(order_no,title,content,type,priority,status,submitter_id,assignee_id,reject_count,max_reject,"
                    + "triage_status,version,sla_deadline,created_at,updated_at) VALUES "
                    + "('" + orderNo + "','S5 夹具单','S5 资源与主业务影响测量','NETWORK',0,'IN_PROGRESS',1,1,0,3,"
                    + "'DONE',0,DATE_ADD(NOW(), INTERVAL 1 DAY),NOW(),NOW())");
            st.execute("INSERT INTO `" + TARGET + "`.`t_work_order_log` "
                    + "(order_id,order_no,operator_id,action,new_status,created_at) "
                    + "SELECT id,order_no,1,'ACCEPT','IN_PROGRESS',NOW() FROM `" + TARGET + "`.`t_work_order` "
                    + "WHERE order_no='" + orderNo + "'");
            System.out.println("[s5] " + TARGET + " ready; admin dept=" + DEPT + "; order=" + orderNo);
        }
    }

    private static String port() {
        return System.getenv().getOrDefault("MYSQL_PORT", "3306");
    }

    private static String user() {
        String v = System.getenv("MYSQL_USER");
        return v == null || v.isBlank() ? "root" : v;
    }

    private static String password() {
        String v = System.getenv("MYSQL_PASSWORD");
        return v == null ? "" : v;
    }
}

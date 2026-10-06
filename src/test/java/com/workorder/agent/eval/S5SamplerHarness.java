package com.workorder.agent.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalTime;

/**
 * S5 的**采样器**：每 2 秒打一行 `MySQL Threads_connected`，跑指定的秒数。
 *
 * <p>为什么单独一个进程：JDBC 只能从 JVM 里用（本机没有 mysql 客户端）；把它挂在后台跑，
 * 就能与"主业务 + 调查"的负载**同时**采样。
 * Hikari 的 active/idle/pending 没有 actuator / JMX 出口，**观察不到**——用 MySQL 侧的
 * `Threads_connected` 近似"应用占用了几条数据库连接"（差距写在 S5 记录里）。
 */
@DisplayName("S5 采样器（Threads_connected，每 2 秒）")
class S5SamplerHarness {

    @Test
    @DisplayName("按 S5_SAMPLE_SECONDS 采样")
    void sample() throws Exception {
        int seconds = Integer.parseInt(System.getenv().getOrDefault("S5_SAMPLE_SECONDS", "60"));
        String url = "jdbc:mysql://localhost:" + port()
                + "/?useUnicode=true&characterEncoding=utf8&connectionTimeZone=%2B08:00&useSSL=false&allowPublicKeyRetrieval=true";
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = DriverManager.getConnection(url, user(), password());
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SHOW STATUS LIKE 'Threads_connected'")) {
                rs.next();
                System.out.println("[s5-sample] " + LocalTime.now().withNano(0) + " threads_connected=" + rs.getString(2));
            }
            Thread.sleep(2000);
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

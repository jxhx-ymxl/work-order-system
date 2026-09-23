package com.workorder.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 启动自检（第二类：**依赖可用性**）：数据库连不上就 <b>fail-fast</b>。
 *
 * <p><b>为什么必须 fail-fast</b>：连不上数据库的应用没有任何价值；让它正常启动、端口可访问、容器状态
 * {@code Up}，会制造"健康"的假象——而真正的故障要等到第一次业务请求或第一次定时任务才暴露。
 * 部署时要手打一批环境变量（`MYSQL_HOST/PORT/DB_NAME/USER/PASSWORD`），**打错一个就会得到一个
 * 假装正常的容器**，这是 fail-fast 要消除的场景。
 *
 * <p><b>为什么不靠 Hikari 的 {@code initializationFailTimeout}</b>：Hikari 的连接池是**懒初始化**的
 * ——不在启动期主动取连接，池根本不会去连库，配置再错也不会暴露。实测现象就是"应用照常启动、
 * 直到首次访问数据库才抛 {@code CannotGetJdbcConnectionException}"。因此这里**显式取一次连接**。
 *
 * <p>与 {@link SlaConfigStartupCheck}（配置完整性，缺行只记 error、不中止）是两类语义，不要合并。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceAvailabilityCheck {

    private final DataSource dataSource;

    @PostConstruct
    public void verifyDatabaseReachable() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            rs.next();
            log.info("[启动自检] 数据库连接可用: {}", conn.getMetaData().getURL());
        } catch (Exception e) {
            // 抛出而不是记录：让 Spring 中止启动。错误信息里带上根因，便于一眼定位是地址/口令/网络哪一类。
            log.error("[启动自检] 数据库不可用，应用拒绝启动（fail-fast）: {}", e.getMessage());
            throw new IllegalStateException("数据库不可用，拒绝启动: " + e.getMessage(), e);
        }
    }
}

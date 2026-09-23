package com.workorder.config;

import com.workorder.WorkOrderApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 启动期依赖可用性检查：**数据库连不上时应用必须启动失败（fail-fast）**。
 *
 * <p>本测试不依赖真实数据库：故意指向一个必然拒绝连接的地址（127.0.0.1:1）来判断行为。
 *
 * <p>关键设计：显式把 Hikari 的 {@code initialization-fail-timeout} 设为 {@code -1}
 * （= 池初始化失败也不报错），这样"启动仍然失败"就**只能**由我们自己的
 * {@link DataSourceAvailabilityCheck#verifyDatabaseReachable()} 造成——
 * 从而把本检查与连接池的懒初始化行为**隔离开**，避免测试因为 Hikari 的默认行为而假通过。
 */
class StartupDependencyCheckTest {

    @Test
    @DisplayName("依赖可用性：数据库不可达时启动必须失败，且失败原因指向我们的检查")
    void applicationFailsToStart_whenDatabaseUnreachable() {
        Throwable thrown = assertThrows(Throwable.class, () ->
                        new SpringApplicationBuilder(WorkOrderApplication.class).run(
                                "--spring.main.web-application-type=none",
                                // 必然连不上的地址：端口 1 无人监听
                                "--spring.datasource.url=jdbc:mysql://127.0.0.1:1/work_order?connectTimeout=500&socketTimeout=500&useSSL=false",
                                "--spring.datasource.username=nobody",
                                "--spring.datasource.password=nobody",
                                "--spring.datasource.hikari.connection-timeout=1000",
                                // 让连接池自身不因初始化失败而报错，从而隔离出"是我们主动校验导致启动失败"
                                "--spring.datasource.hikari.initialization-fail-timeout=-1"),
                "数据库不可达时应用却启动成功了——fail-fast 失效（这正是要防的'假装健康'）");

        List<String> messages = new ArrayList<>();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            messages.add(String.valueOf(t.getMessage()));
        }
        String all = String.join(" | ", messages);
        assertTrue(all.contains("数据库不可用，拒绝启动"),
                "失败原因应指向 DataSourceAvailabilityCheck 的显式校验，实际异常链：" + all);
    }
}

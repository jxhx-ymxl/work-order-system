package com.workorder.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-Job **执行器**接入（P2 步骤 2a）。
 *
 * <h3>一、本轮只做"接入并证明注册成功"，不做任务迁移</h3>
 * 本轮**不加 `@XxlJob`、不动 `@Scheduled`、不改任何扫描逻辑**——两个扫描任务的迁移是 P2 步骤 2b 的事。
 * 因此这里只负责把执行器拉起来（注册到 admin、开始心跳），业务行为一个字不变。
 *
 * <h3>二、开关默认**关**，这不是可选项</h3>
 * {@code xxl.job.executor.enabled=false}（默认）时**本类根本不生效**：
 * <ul>
 *   <li>否则本地/CI 一启动就会去连 admin（连不上会刷错误日志）；</li>
 *   <li>还会**占用 9999 端口**——`@SpringBootTest` 起多个上下文时端口冲突会让测试连带失败；</li>
 *   <li>生产由 compose 显式置 `XXL_JOB_EXECUTOR_ENABLED=true`。</li>
 * </ul>
 * 说白了：这个 {@code @ConditionalOnProperty} 是"测试不被牵连"的唯一保障，**别省**。
 *
 * <h3>三、{@code accessToken} 必须两侧同值</h3>
 * 执行器与 admin 的 token 不一致时，注册被拒（**401**）。而 401 在日志里长得很像"网络不通"，
 * 排查时极易往网络方向查——所以配置里刻意让两侧**同源同一个键**（`XXL_JOB_ACCESS_TOKEN`）。
 *
 * <h3>四、Windows + Docker Desktop 下的已知限制（别在本机死磕）</h3>
 * 本机若 admin 跑在容器里、执行器跑在宿主机，**admin 回连宿主机的 9999 可能不成立**
 * （它拿到的是执行器自报的地址，Windows 上容器回连宿主并不天然可达）。
 * 所以本机的验证目标降级为"**执行器起来了 + 日志里有注册请求**"；
 * **"注册成功"的权威验证放在服务器**——那里 admin 与 backend 在同一 compose 网络内，回连必然可达。
 * 另：本地 9999 被占会让执行器启动失败，日志里能看到端口绑定异常，这属于"换端口/关开关"即可绕过的问题。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "xxl.job.executor.enabled", havingValue = "true")
public class XxlJobConfig {

    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;

    @Value("${xxl.job.executor.appname}")
    private String appname;

    @Value("${xxl.job.executor.port}")
    private int port;

    /**
     * 与 admin 侧同源（`XXL_JOB_ACCESS_TOKEN`）。**允许为空**——空值 = 关掉鉴权，
     * 与 admin 一起留空时两侧一致、注册可用；**接生产前必须设成非空且两侧同值**。
     */
    @Value("${xxl.job.executor.accessToken:}")
    private String accessToken;

    @Value("${xxl.job.executor.logpath}")
    private String logPath;

    @Value("${xxl.job.executor.logretentiondays}")
    private int logRetentionDays;

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appname);
        executor.setPort(port);
        executor.setAccessToken(accessToken);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(logRetentionDays);
        // 只记"参数已装配 + 即将注册"：真正的注册结果由 admin 侧（xxl_job_registry 表）与日志共同判定
        log.info("[xxl-job] 执行器已装配：admin={} appname={} port={} token={} logPath={} 保留={}天 —— 开始向 admin 注册（注册结果看 admin 的 xxl_job_registry）",
                adminAddresses, appname, port,
                (accessToken == null || accessToken.isBlank()) ? "（空：未启用鉴权）" : "（已设置）",
                logPath, logRetentionDays);
        return executor;
    }
}

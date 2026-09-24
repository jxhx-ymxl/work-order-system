package com.workorder.config;

import com.workorder.scheduler.OutboxDispatchTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 开关打开时的装配测试：拓扑与模板必须能在**没有 broker** 的情况下装配出来。
 *
 * <p><b>本类是一个真实缺陷的回归测试</b>：投递链路第一次真机启动时报
 * {@code Error creating bean with name 'rabbitOutboxConfig' ... Is there an unresolvable circular reference?}
 * ——配置类直接注入 RabbitTemplate，而本类又定义了构造 RabbitTemplate 所需的 customizer。
 * 当时 {@code mvn test} 全绿（测试 profile 里开关是关的，这个配置类根本没被装配），
 * 所以这条只有在"开关打开"时才会暴露的问题必须有一条测试专门盯着。
 *
 * <p>端口写成 1（必然拒绝）：既能触发真实的连接失败路径，又保证测试对任何真实 broker 无副作用。
 * **真正消除副作用的是 {@code @MockBean OutboxDispatchTask}**：{@code @Scheduled} 启动后会立刻跑一次，
 * 那一轮会去共享测试库里抢占 outbox 记录，并因为端口 1 连不上而给它们累加 retry_count（调度间隔只延缓、不阻止首轮）。
 * 把投递任务换成 mock 后不再注册调度方法，测试库就不会被这条路径改动——本类要验的是拓扑装配，不需要真的投递任务。
 */
@SpringBootTest(properties = {
        "workorder.outbox.dispatch.enabled=true",
        "workorder.outbox.dispatch.interval-ms=600000",
        "spring.rabbitmq.port=1"
})
@ActiveProfiles("test")
class RabbitOutboxConfigTest {

    /** 见类注释：避免 @Scheduled 首轮立刻执行去改动共享测试库的 outbox 记录 */
    @MockBean
    private OutboxDispatchTask outboxDispatchTask;

    @Autowired
    private CustomExchange orderDelayExchange;

    @Autowired
    private Queue orderReleaseQueue;

    @Autowired
    private Binding orderReleaseBinding;

    @Autowired
    private RabbitTemplateCustomizer outboxReturnsCallback;

    @Test
    @DisplayName("开关打开：延迟交换机/队列/绑定/退回回调都能装配，且不要求 broker 在线")
    void contextLoadsWithDispatchEnabled() {
        assertNotNull(orderDelayExchange, "延迟交换机 bean 缺失——投递链路无从发出");
        assertNotNull(orderReleaseQueue);
        assertNotNull(orderReleaseBinding);
        assertNotNull(outboxReturnsCallback, "退回回调缺失会让不可路由的消息静默消失");

        assertEquals("x-delayed-message", orderDelayExchange.getType(),
                "交换机类型不是延迟型：插件没生效时延迟语义会整体失效");
        assertEquals("direct", orderDelayExchange.getArguments().get("x-delayed-type"),
                "x-delayed-type 是延迟插件的必要参数，缺了 broker 会拒绝声明");
        assertTrue(orderDelayExchange.isDurable(), "非持久交换机在 broker 重启后消失");
        assertEquals(RabbitOutboxConfig.RELEASE_QUEUE, orderReleaseQueue.getName());
        assertEquals(RabbitOutboxConfig.RELEASE_ROUTING_KEY, orderReleaseBinding.getRoutingKey());
    }
}

package com.workorder.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * outbox → RabbitMQ 投递链路的拓扑（P1 步骤 3）。
 *
 * <p><b>延迟怎么实现的</b>：用 {@code x-delayed-message} 交换机（延迟插件提供）。
 * 投递任务把消息发给它，并在消息头带 {@code x-delay=剩余毫秒}；broker 把消息**留在交换机内部**，
 * 到点才路由进目标队列。因此延迟期间 {@code list_queues} 看到的是 0（这是该方案的已知代价，
 * 观察方式见 {@code deploy/rabbitmq/README.md}）。
 *
 * <p><b>为什么不像 TTL+DLX 那样用固定档位队列</b>：延迟值由 {@code t_sla_config.accept_minutes}
 * 现算，而这个值**管理员可以在界面上改**。档位化的结果是"配置改了、实际延迟没改"，且不报错——
 * 本项目已经在 {@code accept_minutes} 上踩过一次"写了不生效"（G5/I8）。理由与代价见 D32。
 *
 * <p><b>为什么要自建镜像</b>：官方 `rabbitmq:3-management` 不含该插件（实测
 * {@code {:plugins_not_found}}）。镜像由 {@code deploy/rabbitmq/Dockerfile} 构建。
 *
 * <p><b>bean 只在开关打开时创建</b>：本地与 CI 默认不连 broker（{@code workorder.outbox.dispatch.enabled=false}）。
 * Spring Boot 会自动声明这里的 Queue/Exchange/Binding（RabbitAdmin 在首次建立连接时声明），
 * 而"首次建立连接"只可能由投递任务或启动校验触发——开关关闭时两者都不存在。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "workorder.outbox.dispatch.enabled", havingValue = "true")
public class RabbitOutboxConfig {

    /** 延迟交换机名（消息在它内部等待，直到 x-delay 到期） */
    public static final String DELAY_EXCHANGE = "workorder.delay.exchange";

    /** 释放检查队列（P1 步骤 4 的消费者从这里取消息；本轮先让它堆积，这是预期状态） */
    public static final String RELEASE_QUEUE = "workorder.order.release.queue";

    /** 路由键 */
    public static final String RELEASE_ROUTING_KEY = "order.release.check";

    /** 延迟毫秒数（延迟插件约定） */
    public static final String HEADER_DELAY = "x-delay";

    /** 事件唯一键（消费端幂等用，避免消费者必须解析消息体才知道去重键） */
    public static final String HEADER_EVENT_ID = "x-event-id";

    /** 事件类型 */
    public static final String HEADER_EVENT_TYPE = "x-event-type";

    /**
     * <b>为什么用 {@link ObjectProvider} 而不是直接注入 {@link RabbitTemplate}</b>：
     * 本类定义的 {@code RabbitTemplateCustomizer} 是构造 RabbitTemplate 的必需依赖，
     * 直接注入就形成"配置类要 RabbitTemplate → RabbitTemplate 要本类的 customizer → 配置类"的循环，
     * 表现为启动失败（实测：Error creating bean with name 'rabbitOutboxConfig' ... circular reference）。
     * ObjectProvider 是延迟解析，构造期不需要 RabbitTemplate。
     */
    private final ObjectProvider<RabbitTemplate> rabbitTemplateProvider;

    public RabbitOutboxConfig(ObjectProvider<RabbitTemplate> rabbitTemplateProvider) {
        this.rabbitTemplateProvider = rabbitTemplateProvider;
    }

    /**
     * 延迟交换机。{@code x-delayed-type=direct} 表示"到期后按 direct 语义路由"，
     * 这是延迟插件的必要参数（延迟交换机本身不是一个可路由类型，它是对底层类型的包装）。
     */
    @Bean
    public CustomExchange orderDelayExchange() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-delayed-type", "direct");
        return new CustomExchange(DELAY_EXCHANGE, "x-delayed-message", true, false, args);
    }

    @Bean
    public Queue orderReleaseQueue() {
        // durable=true：broker 重启后队列与消息仍在（延迟消息同样能扛 broker 重启，已实测）
        return new Queue(RELEASE_QUEUE, true);
    }

    @Bean
    public Binding orderReleaseBinding(Queue orderReleaseQueue, CustomExchange orderDelayExchange) {
        return BindingBuilder.bind(orderReleaseQueue).to(orderDelayExchange).with(RELEASE_ROUTING_KEY).noargs();
    }

    /**
     * 退回消息的痕迹化处理。
     *
     * <p><b>它防的不是 NO_ROUTE</b>：实测表明延迟插件对每条延迟消息都会返回 NO_ROUTE
     * （route/2 返回"无立即路由"），所以 {@code mandatory} 已显式关掉——开着只会给每条消息
     * 生成一条假告警，且消息其实照常到点入队。
     *
     * <p>它真正覆盖的是**队列侧拒收**（例如队列设置了 max-length 且 reject-publish，
     * 消息被退回给发布方）——这类退回在 {@code publisher-returns: true} 下会走到这个回调。
     * 不处理的话，这种"消息发出去但没人收到"的形态是静默的，而它比一条报错危险得多。
     */
    @Bean
    public RabbitTemplateCustomizer outboxReturnsCallback() {
        return template -> template.setReturnsCallback(returned -> log.error(
                "[outbox] 消息被 broker 退回（不可路由，说明交换机/绑定与发送方不一致）：exchange={} routingKey={} replyCode={} replyText={}",
                returned.getExchange(), returned.getRoutingKey(),
                returned.getReplyCode(), returned.getReplyText()));
    }

    /**
     * 启动期校验：交换机必须真的是延迟型（等价于"镜像里确实启用了延迟插件"）。
     *
     * <p><b>为什么不 fail-fast</b>：broker 不可达是**可恢复的临时故障**，让整个应用起不来
     * 是比"延迟投递暂时不可用"更坏的结局（outbox 记录会一直重试，兜底扫描仍在释放工单）。
     * 所以这里只记日志——但必须是 ERROR，且写清后果与排查方向。
     *
     * <p>这个校验防的是本项目特有的一种事故：**服务器上误用官方镜像部署**。
     * 那种情况下交换机不会被创建，投递会以 channel 级错误持续失败；没有这条日志，
     * 现象只是"outbox 里 retry_count 涨"，排查要绕一大圈。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyDelayExchangeUsable() {
        try {
            rabbitTemplateProvider.getObject().execute(channel -> {
                // 主动声明一次：插件缺失时 broker 返回 "invalid exchange type"，
                // 交换机类型不符时返回 PRECONDITION_FAILED，两者都会抛到这里。
                channel.exchangeDeclare(DELAY_EXCHANGE, "x-delayed-message", true, false,
                        Map.of("x-delayed-type", "direct"));
                return null;
            });
            log.info("[outbox] 延迟交换机校验通过：{} 类型=x-delayed-message（延迟插件已生效）", DELAY_EXCHANGE);
        } catch (AmqpConnectException e) {
            // 连不上 broker：可能是临时故障（broker 正在重启）或部署顺序问题。
            // 只记 WARN，不换语义——否则全新部署时会因 broker 尚未就绪而刷一条误导性的 ERROR。
            log.warn("[outbox] 启动校验未执行：连不上 broker（{}）。"
                    + "若 broker 正在启动属正常；持续如此请检查 rabbitmq 容器与 RABBITMQ_* 环境变量。原因：{}",
                    DELAY_EXCHANGE, e.getMessage());
        } catch (AmqpException e) {
            log.error("[outbox] 延迟交换机校验失败：{}。"
                            + "后果：投递任务会持续失败、outbox 记录 retry_count 上涨（工单本身仍由兜底扫描释放）。"
                            + "排查方向：① rabbitmq 是否用 deploy/rabbitmq/Dockerfile 构建的镜像（官方镜像不含延迟插件）；"
                            + "② broker 是否可达；③ 同名交换机是否已按别的类型存在。原因：{}",
                    DELAY_EXCHANGE, e.getMessage());
        }
    }
}

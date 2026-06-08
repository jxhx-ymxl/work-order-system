# RabbitMQ 迁移指南：Mock → 真实消息队列

> **当前状态：** 接口驱动 + Mock 控制台日志 + `@Scheduled` 本地兜底  
> **目标状态：** RabbitMQ 延迟消息主攻 + `@Scheduled` 兜底（按 TECHNICAL-PLAN.md 原始设计）  
> **预计迁移工时：** 2 小时（不含 RabbitMQ 中间件部署）

---

## 一、架构现状

### 1.1 当前 Mock 机制

```
┌────────────────────────────────────────────────────┐
│  WorkOrderServiceImpl                              │
│    acceptOrder() / assignOrder()                   │
│      └─ afterCommit → messagePublishService        │
│    rejectOrder() [ESCALATED_ADMIN 分支]             │
│      └─ afterCommit → messagePublishService        │
└───────────────────────┬────────────────────────────┘
                        │ 依赖接口（非具体实现）
                        ▼
┌────────────────────────────────────────────────────┐
│  MessagePublishService (接口)                       │
│    sendReleaseCheck(Long orderId)                  │
│    sendSlaEscalation(Long orderId)                 │
└───────────────────────┬────────────────────────────┘
                        │
          ┌─────────────┴─────────────┐
          │ 当前激活                   │ 上线后激活
          ▼                           ▼
  MockMessagePublishServiceImpl   RabbitMQPublishServiceImpl
  ┌──────────────────────┐      ┌──────────────────────────┐
  │ log.info("[MOCK-MQ]") │      │ rabbitTemplate           │
  │ 只打印日志，不发消息    │      │   .convertAndSend(...)   │
  └──────────────────────┘      └──────────────────────────┘
```

**业务层（WorkOrderServiceImpl）完全解耦**——它只持有 `MessagePublishService` 接口引用，不感知底层是 Mock 还是 RabbitMQ。上生产时只需替换 Spring 容器中的 Bean 实现，Service 代码零改动。

### 1.2 本地兜底：ReleaseTimeoutScheduler

```
@Scheduled(fixedRate = 60_000)  每分钟触发
         │
         ▼
  SELECT * FROM t_work_order
  WHERE status = 'ACCEPTED'
    AND updated_at <= NOW() - 30 MINUTES
         │
         ▼
  workOrderService.releaseOrder(orderId)
         │
         ▼
  UPDATE t_work_order SET assignee_id = NULL,
    status = 'RELEASED', version = version + 1
  WHERE id = ? AND status = 'ACCEPTED'
```

**为什么保留这个兜底？** 即使 RabbitMQ 延迟消息正常工作，定时扫表仍然是容灾底线——MQ 消息可能因 broker 重启、网络抖动、队列堆积而丢失。TECHNICAL-PLAN.md 3.3 节明确：**主路径快且精准，兜底路径不漏。**

---

## 二、依赖与配置恢复清单

### 2.1 pom.xml —— **已就绪，无需操作**

```xml
<!-- 已存在，无需添加 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

验证命令：`mvn dependency:tree | grep amqp`

### 2.2 application.yml —— **已就绪，确认连通性即可**

```yaml
# 当前配置（已存在）
spring:
  rabbitmq:
    host: localhost           # 生产环境改为实际 IP
    port: 5672
    username: guest           # 生产环境改为专用账号
    password: guest           # 生产环境改为强密码
    listener:
      simple:
        acknowledge-mode: manual    # 手动 ACK，消费成功才确认
        prefetch: 10               # 每次预取 10 条，避免一个消费者积压
```

**需要补充的配置：**

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASS:guest}
    publisher-confirm-type: correlated    # 开启发布确认
    publisher-returns: true               # 开启失败回调
    listener:
      simple:
        acknowledge-mode: manual
        prefetch: 10
        retry:
          enabled: true
          max-attempts: 3                 # 消费失败重试 3 次
          initial-interval: 1000ms        # 退避策略
```

**RabbitMQ 插件前置条件：**

```bash
# 进入 RabbitMQ 容器或宿主机，启用延迟消息插件
rabbitmq-plugins enable rabbitmq_delayed_message_exchange
# 重启 RabbitMQ
docker restart rabbitmq
```

验证：RabbitMQ 管理界面（`:15672`）→ Exchanges 标签 → 新增 Exchange 时 Type 下拉框出现 `x-delayed-message` 选项。

---

## 三、核心代码替换步骤

### 步骤 1：创建 RabbitMQConfig（基础设施声明）

**新文件：** `src/main/java/com/workorder/config/RabbitMQConfig.java`

```java
package com.workorder.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.transaction.RabbitTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMQConfig {

    // ── 交换机 ──

    public static final String DELAY_EXCHANGE = "order.delay.exchange";
    public static final String RELEASE_QUEUE = "order.release.queue";
    public static final String SLA_ESCALATION_QUEUE = "sla.escalation.queue";
    public static final String RELEASE_ROUTING_KEY = "order.release";
    public static final String SLA_ESCALATION_ROUTING_KEY = "sla.escalation";

    /** 延迟交换机 —— 支持 x-delayed-message */
    @Bean
    public CustomExchange delayExchange() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-delayed-type", "direct");
        return new CustomExchange(DELAY_EXCHANGE, "x-delayed-message", true, false, args);
    }

    // ── 队列 ──

    @Bean
    public Queue releaseQueue() {
        return new Queue(RELEASE_QUEUE, true);
    }

    @Bean
    public Queue slaEscalationQueue() {
        return new Queue(SLA_ESCALATION_QUEUE, true);
    }

    // ── 绑定 ──

    @Bean
    public Binding releaseBinding() {
        return BindingBuilder.bind(releaseQueue())
                .to(delayExchange())
                .with(RELEASE_ROUTING_KEY)
                .noargs();
    }

    @Bean
    public Binding slaEscalationBinding() {
        return BindingBuilder.bind(slaEscalationQueue())
                .to(delayExchange())
                .with(SLA_ESCALATION_ROUTING_KEY)
                .noargs();
    }

    // ── RabbitTemplate 配置发布确认 ──

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                // 生产环境接入监控告警
                log.warn("消息发送失败: correlationData={}, cause={}", correlationData, cause);
            }
        });
        return template;
    }
}
```

> **注意：** 如果 RabbitMQ 服务未启动，`ConnectionFactory` 创建失败会导致应用启动失败。  
> 迁移过渡期可通过以下配置允许启动时连接失败：
> ```yaml
> spring:
>   rabbitmq:
>     connection-timeout: 3000
> ```
> 配合 `@ConditionalOnProperty` 或 Spring Profile 实现平滑切换。

### 步骤 2：创建 RabbitMQPublishServiceImpl（真实实现）

**新文件：** `src/main/java/com/workorder/service/impl/RabbitMQPublishServiceImpl.java`

```java
package com.workorder.service.impl;

import com.workorder.config.RabbitMQConfig;
import com.workorder.service.MessagePublishService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import static com.workorder.config.RabbitMQConfig.DELAY_EXCHANGE;
import static com.workorder.config.RabbitMQConfig.RELEASE_ROUTING_KEY;
import static com.workorder.config.RabbitMQConfig.SLA_ESCALATION_QUEUE;
import static com.workorder.config.RabbitMQConfig.SLA_ESCALATION_ROUTING_KEY;

@Slf4j
@Service
@RequiredArgsConstructor
public class RabbitMQPublishServiceImpl implements MessagePublishService {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void sendReleaseCheck(Long orderId) {
        rabbitTemplate.convertAndSend(DELAY_EXCHANGE, RELEASE_ROUTING_KEY, orderId,
                msg -> {
                    // 延迟 30 分钟触发消费者
                    msg.getMessageProperties().setDelay(30 * 60 * 1000);
                    return msg;
                });
        log.debug("延迟释放消息已发送: orderId={}, delay=30min", orderId);
    }

    @Override
    public void sendSlaEscalation(Long orderId) {
        // SLA 升级通知不需要延迟，直接投递
        rabbitTemplate.convertAndSend(DELAY_EXCHANGE, SLA_ESCALATION_ROUTING_KEY, orderId);
        log.debug("SLA升级通知已发送: orderId={}", orderId);
    }
}
```

### 步骤 3：Bean 切换策略 —— 不改业务代码一行

**方案 A（推荐）：Spring Profile**

```java
// MockMessagePublishServiceImpl.java —— 添加 Profile 限定
@Service
@Profile("!prod")   // 非生产环境激活
public class MockMessagePublishServiceImpl implements MessagePublishService { ... }

// RabbitMQPublishServiceImpl.java —— 添加 Profile 限定
@Service
@Profile("prod")    // 仅生产环境激活
public class RabbitMQPublishServiceImpl implements MessagePublishService { ... }
```

启动命令：
```bash
# 开发环境（默认 profile，Mock 生效）
mvn spring-boot:run

# 生产环境（RabbitMQ 生效）
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

**方案 B（备选）：`@ConditionalOnMissingBean`**

```java
@Service
@ConditionalOnMissingBean(RabbitMQPublishServiceImpl.class)
public class MockMessagePublishServiceImpl implements MessagePublishService { ... }
```

当 `RabbitMQPublishServiceImpl` 在 classpath 上时，Mock 自动失效。

| 方案 | 优点 | 缺点 |
|------|------|------|
| Profile | 显式可控，不会被意外替换 | 启动需指定 profile |
| ConditionalOnMissingBean | 全自动 | Bean 加载顺序依赖，不够透明 |

---

## 四、消费者实现指引

### 4.1 超时释放消费者

**新文件：** `src/main/java/com/workorder/listener/OrderReleaseListener.java`

```java
package com.workorder.listener;

import com.rabbitmq.client.Channel;
import com.workorder.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static com.workorder.config.RabbitMQConfig.RELEASE_QUEUE;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderReleaseListener {

    private final WorkOrderService workOrderService;

    @RabbitListener(queues = RELEASE_QUEUE)
    public void onReleaseCheck(Long orderId, Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            workOrderService.releaseOrder(orderId);
            log.info("延迟消息触发释放成功: orderId={}", orderId);
        } catch (Exception e) {
            // releaseOrder 内部有乐观锁保护——如果工单状态已不是 ACCEPTED，
            // 说明处理人已经开始了处理，这是正常情况，不应重试
            log.warn("释放检查跳过（状态已变更或已释放）: orderId={}, reason={}",
                    orderId, e.getMessage());
        }

        // 无论如何都 ACK——因为 releaseOrder 内部乐观锁兜底，不存在需要重试的场景
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException ex) {
            log.error("ACK失败: orderId={}", orderId, ex);
        }
    }
}
```

> **ACK 策略解释：**  
> 释放消息是"检查型"操作而非"交易型"操作。如果工单已被处理人 start，`releaseOrder` 内部的 `WHERE status='ACCEPTED'` 条件不匹配，affected rows = 0，方法直接 return。  
> 这是预期内路径，不应 NACK 重试。**永远 ACK，让兜底定时任务补漏。**

### 4.2 SLA 升级通知消费者

**新文件：** `src/main/java/com/workorder/listener/SlaEscalationListener.java`

```java
package com.workorder.listener;

import com.rabbitmq.client.Channel;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.NotificationService;
import com.workorder.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.workorder.config.RabbitMQConfig.SLA_ESCALATION_QUEUE;

@Slf4j
@Component
@RequiredArgsConstructor
public class SlaEscalationListener {

    private final WorkOrderMapper workOrderMapper;
    private final NotificationService notificationService;
    private final UserService userService;
    private final StringRedisTemplate redisTemplate;

    /** 幂等 Key 前缀：防止 MQ 重发导致重复骚扰管理员 */
    private static final String SLA_NOTIFIED_KEY = "sla_notified:";

    @RabbitListener(queues = SLA_ESCALATION_QUEUE)
    public void onSlaEscalation(Long orderId, Channel channel,
                                @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            // ═══════════════════════════════════════════════════════════
            // 幂等守卫：Redis SETNX 原子判断——同一工单的升级通知只发一次。
            //
            // 为什么需要？
            // RabbitMQ 是 At-Least-Once 语义：消息可能因网络抖动被重复投递。
            // 没有这个守卫 → 所有管理员收到两条一模一样的告警 → 对用户是事故。
            //
            // 为什么用 Redis 而不是 MySQL？
            // 1. SETNX 单条指令原子完成"判断+写入"，无需分布式锁
            // 2. TTL 自动清理，不占存储；工单升级是低频事件
            // 3. MySQL 没有原子 GET-IF-ABSENT 语义，SELECT 后 INSERT 中间有竞态窗口
            // ═══════════════════════════════════════════════════════════
            String dedupKey = SLA_NOTIFIED_KEY + orderId;
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(dedupKey, "1", java.time.Duration.ofHours(24));
            if (!Boolean.TRUE.equals(acquired)) {
                log.info("SLA通知已发送过，跳过重复投递: orderId={}", orderId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            WorkOrder order = workOrderMapper.selectById(orderId);
            if (order == null) {
                log.warn("SLA升级通知——工单不存在: orderId={}", orderId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 查询所有 SYS_ADMIN
            List<Long> adminIds = userService.findUserIdsByRole("SYS_ADMIN");
            for (Long adminId : adminIds) {
                notificationService.notify(adminId,
                        "工单" + order.getOrderNo() + " 驳回次数已达上限",
                        "类型:" + order.getType()
                                + ", 优先级:" + order.getPriority()
                                + ", 请介入处理");
            }
            log.info("SLA升级通知已发送给{}位管理员: orderId={}", adminIds.size(), orderId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("SLA升级通知处理异常: orderId={}", orderId, e);
            // 通知型消息——NACK 不重入队列，避免死循环
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ex) {
                log.error("NACK失败", ex);
            }
        }
    }
}
```

---

## 五、双路径协作全景

```
接单成功 (acceptOrder)
    │
    ├─ 主路径（RabbitMQ 延迟消息）──────────────────────┐
    │                                                   │
    │  RabbitMQPublishServiceImpl.sendReleaseCheck()    │
    │    └─ convertAndSend(DELAY_EXCHANGE,              │
    │           orderId, msg.setDelay(30min))           │
    │           │                                       │
    │           ▼ (30 分钟后)                            │
    │  OrderReleaseListener.onReleaseCheck(orderId)     │
    │    └─ workOrderService.releaseOrder(orderId)      │
    │         └─ UPDATE WHERE status='ACCEPTED' ← 乐观锁 │
    │                                                   │
    └───────────────────────────────────────────────────┘
    
    ┌─ 兜底路径（@Scheduled 定时扫表）─────────────────┐
    │                                                   │
    │  ReleaseTimeoutScheduler.scanAndReleaseTimeout()  │
    │    每分钟触发                                       │
    │    └─ SELECT WHERE status='ACCEPTED'               │
    │         AND updated_at <= NOW() - 30min ← idx_sla  │
    │         └─ workOrderService.releaseOrder(orderId)  │
    │              └─ UPDATE WHERE status='ACCEPTED'     │
    │                                                   │
    └───────────────────────────────────────────────────┘

两条路径同时调 releaseOrder，但由于 SQL 带 WHERE status='ACCEPTED'：
  - 先到达的 → affected rows=1，释放成功
  - 后到达的 → affected rows=0，静默跳过
  → 永远不会重复释放
```

---

## 六、迁移 Checklist

上线前逐项确认：

### 环境准备
- [ ] RabbitMQ 服务可访问（宿主机 `5672` / 管理界面 `15672`）
- [ ] `rabbitmq_delayed_message_exchange` 插件已启用
- [ ] 生产环境专用账号已创建（非 guest/guest）
- [ ] `application-prod.yml` 中 rabbitmq 连接参数指向生产实例
- [ ] `publisher-confirm-type: correlated` 已配置
- [ ] Spring Profile `prod` 可用

### 代码部署
- [ ] `RabbitMQConfig.java` → `src/main/java/com/workorder/config/`
- [ ] `RabbitMQPublishServiceImpl.java` → `src/main/java/com/workorder/service/impl/`
- [ ] `OrderReleaseListener.java` → `src/main/java/com/workorder/listener/`
- [ ] `SlaEscalationListener.java` → `src/main/java/com/workorder/listener/`
- [ ] `MockMessagePublishServiceImpl` 添加 `@Profile("!prod")`
- [ ] `ReleaseTimeoutScheduler` **保留不动**（兜底路径永久生效）

### 验证步骤
- [ ] 启动应用 → RabbitMQ 管理界面可见 `order.delay.exchange`、`order.release.queue`、`sla.escalation.queue`
- [ ] 抢单成功 → `order.release.queue` 中有一条消息（`GetMessage` 查看，30min 内不消费）
- [ ] 30 分钟后 → 消费者触发 `releaseOrder`，工单状态变更为 RELEASED
- [ ] 故意停止 RabbitMQ → 定时任务继续扫表释放（兜底路径验证）
- [ ] 驳回 3 次后 → `sla.escalation.queue` 收到升级通知消息

### 回滚方案
- [ ] 若 RabbitMQ 不可用，去掉 `-Dspring-boot.run.profiles=prod` 参数启动即可回退到 Mock 模式
- [ ] 定时兜底不受影响——即使 RabbitMQ 完全不可用，超时工单仍会在 30 分钟内被定时任务释放

---

## 七、面试话术速记

> **"为什么有两个释放路径？"**  
> 延迟队列是精准触发器——30 分钟后恰好触发，不扫百万行表。但延迟消息可能因 broker 重启或网络抖动丢失。定时任务每 1 分钟全量扫一次 `idx_sla` 索引，兜底捕获消息丢失的工单。主路径快且精准，兜底路径不漏——两条腿走路。

> **"为什么不在生产环境下线定时任务？"**  
> MQ 消息是"通知型"的——丢了的最坏结果是工单晚几分钟释放，不是数据错误。但定时任务的持续扫描是"容错底线"。这和 Redis 过期回调的取舍逻辑一致：MQ 延迟消息是提速手段，定时扫表是容错底线。两条路径同时跑，乐观锁保证不重复释放。

> **"为什么要用 MessagePublishService 接口解耦？"**  
> 业务层不应该关心消息的具体传输方式。今天用 RabbitMQ，明天可能切 Kafka 或 RocketMQ——WorkOrderService 的代码一行不改。Mock 实现让本地开发和 CI 环境无需启动 RabbitMQ 也能跑通全部流程。

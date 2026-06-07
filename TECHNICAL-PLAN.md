# 企业工单流转平台 — 完整技术方案

---

## 一、数据库设计

### 1.1 工单表 `t_work_order`

```sql
CREATE TABLE t_work_order (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键(内部用)',
    order_no        VARCHAR(22) NOT NULL UNIQUE COMMENT '工单编号: WO-YYYYMMDD-XXXXX',
    title           VARCHAR(200) NOT NULL COMMENT '工单标题',
    content         TEXT NOT NULL COMMENT '工单内容',
    type            VARCHAR(32) NOT NULL COMMENT '工单类型: REPAIR/LEAVE/REIMBURSE/OTHER',
    priority        TINYINT NOT NULL DEFAULT 0 COMMENT '优先级: 0普通 1紧急',
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '当前状态',
    submitter_id    BIGINT NOT NULL COMMENT '提交人ID',
    assignee_id     BIGINT DEFAULT NULL COMMENT '处理人ID(接单后赋值)',
    reject_count    INT NOT NULL DEFAULT 0 COMMENT '驳回次数',
    max_reject      INT NOT NULL DEFAULT 3 COMMENT '最大驳回次数',
    sla_deadline    DATETIME DEFAULT NULL COMMENT 'SLA截止时间',
    version         INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_order_no (order_no),
    INDEX idx_status (status),
    INDEX idx_submitter (submitter_id),
    INDEX idx_assignee (assignee_id),
    INDEX idx_sla (status, sla_deadline)  -- 定时任务扫超时工单用
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**设计要点：**
- `order_no` 是业务编号，对外展示；`id` 是自增主键，内部关联用。分离两者的原因是业务编号格式需要日期+序号语义，不适合做主键
- `reject_count` 和 `max_reject` 控制驳回次数上限，达到上限后自动升级
- `version` 字段是乐观锁核心——每次更新状态时 `WHERE version = ?`，避免状态覆盖
- `idx_sla` 联合索引专为定时任务扫描设计，避免全表扫描
- `status` 用 VARCHAR 而非 TINYINT——可读性优先，通过索引保证查询性能

### 1.2 工单操作日志表 `t_work_order_log`

```sql
CREATE TABLE t_work_order_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id        BIGINT NOT NULL,
    order_no        VARCHAR(22) NOT NULL COMMENT '冗余工单编号，方便查询',
    operator_id     BIGINT NOT NULL COMMENT '操作人ID',
    action          VARCHAR(32) NOT NULL COMMENT '操作类型',
    old_status      VARCHAR(20) DEFAULT NULL,
    new_status      VARCHAR(20) NOT NULL,
    remark          VARCHAR(500) DEFAULT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order (order_id),
    INDEX idx_order_no (order_no),
    INDEX idx_created (created_at)  -- 定时任务按时间范围扫
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**不要用触发器写日志**——用 Service 层手动插入，配合 `@Transactional` 保证与状态更新原子提交。

### 1.3 RBAC 权限体系 — 5 张表

```sql
-- 用户表
CREATE TABLE t_user (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    username      VARCHAR(50) NOT NULL UNIQUE,
    password      VARCHAR(255) NOT NULL,
    phone         VARCHAR(20) DEFAULT NULL,
    dept_id       BIGINT DEFAULT NULL COMMENT '所属部门ID',
    status        TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 角色表
CREATE TABLE t_role (
    id        BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_code VARCHAR(32) NOT NULL UNIQUE COMMENT '角色编码: SUBMITTER/HANDLER/DEPT_ADMIN/SYS_ADMIN',
    role_name VARCHAR(50) NOT NULL COMMENT '角色名称',
    remark    VARCHAR(200) DEFAULT NULL
);

-- 权限表
CREATE TABLE t_permission (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    perm_code   VARCHAR(64) NOT NULL UNIQUE COMMENT '权限码: order:accept, order:stats, system:user:manage',
    perm_name   VARCHAR(50) NOT NULL COMMENT '权限名称',
    parent_id   BIGINT DEFAULT 0 COMMENT '父权限ID(支持菜单树)'
);

-- 用户-角色关联表
CREATE TABLE t_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

-- 角色-权限关联表
CREATE TABLE t_role_permission (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id)
);
```

### 1.4 权限码设计

```
order:accept         抢单
order:start          开始处理
order:complete       提交验收
order:approve        验收通过
order:reject         验收驳回
order:assign         手动分配工单（主管权限）
order:stats          查看本部门统计
order:stats:all      查看全局统计（管理员）
system:user:manage   用户管理
system:role:manage   角色管理
sla:config:manage    SLA配置管理
```

### 1.5 RBAC 接口拦截实现（Sa-Token）

```java
// 接口上标注所需权限码
@RestController
public class WorkOrderController {

    @SaCheckPermission("order:accept")
    @PostMapping("/api/orders/{id}/accept")
    public Result<Void> accept(@PathVariable Long id) { ... }

    @SaCheckPermission("order:stats:all")
    @GetMapping("/api/admin/orders/stats")
    public Result<StatsVO> globalStats() { ... }
}
```

**为什么选 Sa-Token 而不是 Spring Security？** Spring Security 的配置成本和学习曲线对实习项目过高。Sa-Token 一个依赖 + 一个注解就能完成 RBAC，且它的 `@SaCheckPermission` 粒度正好匹配我们设计的权限码体系。面试时可以诚实地说："我评估过 Spring Security，但当前项目的权限需求用 Sa-Token 足够覆盖，且实现成本更低——工程选择要看投入产出比。"

### 1.6 SLA 配置表 `t_sla_config`

```sql
CREATE TABLE t_sla_config (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    type            VARCHAR(32) NOT NULL COMMENT '工单类型',
    priority        TINYINT NOT NULL,
    accept_minutes  INT NOT NULL COMMENT 'N分钟内必须接单',
    finish_minutes  INT NOT NULL COMMENT 'N分钟内必须处理完成',
    UNIQUE KEY uk_type_priority (type, priority)
);
```

### 1.7 站内信表 `t_notification`

```sql
CREATE TABLE t_notification (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT NOT NULL COMMENT '接收人ID',
    title       VARCHAR(200) NOT NULL COMMENT '通知标题',
    content     VARCHAR(500) DEFAULT NULL COMMENT '通知内容',
    ref_type    VARCHAR(20) DEFAULT NULL COMMENT '关联类型: ORDER/SYSTEM',
    ref_id      BIGINT DEFAULT NULL COMMENT '关联ID(工单ID等)',
    is_read     TINYINT NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_read (user_id, is_read, created_at)
);
```

---

## 二、状态机设计

```
                        ┌──────────────────────────────┐
                        │                              │
    ┌─────┐       ┌─────┴──────┐       ┌──────────┐   │
    │待分配│ ──抢单─→ │  已接单   │ ─开始→ │  处理中  │   │
    │PENDING│       │ACCEPTED  │       │IN_PROGRESS│   │
    └─────┘       └─────┬──────┘       └────┬─────┘   │
                        │                   │         │
                        │ 超时30分钟          │ 提交验收  │
                        ↓ 未处理             ↓         │
                    ┌─────────┐        ┌─────────┐    │
                    │  已释放  │        │  待验收  │    │
                    │RELEASED│        │AWAIT_APPROVAL│ │
                    └─────────┘        └────┬────┘    │
                                           │         │
                                      ┌────┴────┐    │
                                      │         │    │
                                  验收通过   验收不通过  │
                                      │    (驳回)     │
                                      │   次数未超限   │
                                      │         │    │
                                      ↓         └────┘
                                  ┌──────┐   (回到处理中)
                                  │ 已关闭 │
                                  │CLOSED │
                                  └──────┘

                              验收不通过 & 驳回次数已达上限
                                        │
                                        ↓
                              ┌──────────────────┐
                              │   已升级管理员     │
                              │ ESCALATED_ADMIN  │
                              └──────────────────┘
```

**合法的状态转移（必须检查）：**

| 当前状态 | 允许的操作 | 目标状态 | 权限要求 |
|---|---|---|---|
| PENDING | ACCEPT（抢单） | ACCEPTED | `order:accept` |
| PENDING | ASSIGN（管理员指派） | ACCEPTED | `order:assign` |
| ACCEPTED | START | IN_PROGRESS | 仅当前处理人 |
| ACCEPTED | RELEASE（超时释放） | RELEASED | 系统触发 |
| IN_PROGRESS | COMPLETE | AWAIT_APPROVAL | 仅当前处理人 |
| AWAIT_APPROVAL | APPROVE | CLOSED | 仅提交人 |
| AWAIT_APPROVAL | REJECT（驳回，次数未满） | IN_PROGRESS | 仅提交人 |
| AWAIT_APPROVAL | REJECT（驳回，次数已满） | ESCALATED_ADMIN | 仅提交人 |

**任何不在上表中的转移请求，直接抛异常拒绝。**

**驳回计数逻辑：**
```java
public void rejectOrder(Long orderId, String remark) {
    WorkOrder order = workOrderMapper.selectById(orderId);
    if (!"AWAIT_APPROVAL".equals(order.getStatus())) {
        throw new BizException("只有待验收状态才能驳回");
    }
    if (order.getRejectCount() >= order.getMaxReject()) {
        // 已达上限 → 升级管理员
        workOrderMapper.updateStatus(orderId, "AWAIT_APPROVAL", "ESCALATED_ADMIN",
            order.getVersion(), order.getRejectCount());
        // 发站内信通知管理员
        notificationService.notifyAdmins("工单" + order.getOrderNo() + "驳回次数已达上限，请介入处理");
    } else {
        // 普通驳回 → 回到处理中
        workOrderMapper.updateStatusAndIncrementReject(orderId, "AWAIT_APPROVAL",
            "IN_PROGRESS", order.getVersion());
    }
}
```

---

## 三、核心模块实现方案

### 3.1 工单编号生成 — Redis 自增 + 日期重置

```java
@Component
public class OrderNoGenerator {
    private static final String PREFIX = "WO";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public String next() {
        String today = LocalDate.now().format(DATE_FMT);
        String key = "order:seq:" + today;

        // Redis INCR 是原子操作，天然线程安全
        Long seq = redisTemplate.opsForValue().increment(key);
        // 首次使用当天key时设过期时间（凌晨自动清理昨天的key）
        if (seq != null && seq == 1) {
            redisTemplate.expire(key, 1, TimeUnit.DAYS);
        }

        return String.format("%s-%s-%05d", PREFIX, today, seq);
    }
}
```

**面试展开点：为什么不用数据库自增？** 自增 ID 只有整数没有业务语义，且分库分表后会冲突。为什么不用雪花算法？雪花算法生成的是纯数字，没有日期语义——运营人员一眼看不出工单是哪天创建的。Redis INCR 既简单又能按日期——天然分隔。到了第二天 key 变了，序列自动从 1 开始。

### 3.2 抢单——并发安全的核心战场

**问题：** 一张工单（PENDING 状态）被多个处理人同时看到，同时点击"接单"。

**错误做法（会超卖）：**

```java
// ❌ 先查后更——两个请求都可能读到 assignee_id IS NULL
WorkOrder order = mapper.selectById(orderId);
if (order.getAssigneeId() == null) {
    order.setAssigneeId(currentUserId);
    mapper.updateById(order);  // 后到的会覆盖先到的！
}
```

**推荐做法——单条 SQL 原子更新：**

```java
// ✅ 原子抢单：一行 SQL 完成查找+更新
public interface WorkOrderMapper {
    @Update("UPDATE t_work_order SET assignee_id = #{userId}, " +
            "status = 'ACCEPTED', version = version + 1 " +
            "WHERE id = #{orderId} AND assignee_id IS NULL AND status = 'PENDING'")
    int grabOrder(@Param("orderId") Long orderId, @Param("userId") Long userId);
    // 返回值 = 1 抢成功，= 0 被别人抢走了
}
```

### 3.3 超时释放 — 延迟队列主攻 + 定时任务兜底

**设计思路：**

| 路径 | 机制 | 触发时间 | 职责 |
|---|---|---|---|
| **主路径** | RabbitMQ 延迟消息 | 接单后 30 分钟精准触发 | 精准，不扫表 |
| **兜底路径** | 定时任务 30 分钟轮询 | 每 30 分钟全量扫一次 | 容灾，防消息丢失 |

**第一步：接单成功后发延迟消息 + 写 Redis 标记**

```java
// 抢单事务提交后
@Transactional
public void acceptOrder(Long orderId, Long userId) {
    // ...抢单逻辑（UPDATE SQL）...

    // 1. Redis 快速标记——供释放操作校验
    String key = "order:accept_timeout:" + orderId;
    redisTemplate.opsForValue().set(key, userId.toString(), 30, TimeUnit.MINUTES);

    // 2. 发一条 30 分钟延迟消息（主路径）
    rabbitTemplate.convertAndSend("order.delay.exchange", "order.release",
        orderId, msg -> {
            msg.getMessageProperties().setDelay(30 * 60 * 1000);
            return msg;
        });
}
```

**第二步：延迟消息消费端（主路径）**

```java
@RabbitListener(queues = "order.release.queue")
public void onReleaseCheck(Long orderId) {
    WorkOrder order = workOrderMapper.selectById(orderId);
    if ("ACCEPTED".equals(order.getStatus())) {
        // 状态仍是 ACCEPTED → 说明 30 分钟内处理人没点"开始处理"
        workOrderService.releaseOrder(orderId);
    }
    // 如果状态已经不是 ACCEPTED → 处理人已经开始处理，跳过
}
```

**第三步：定时任务兜底（每 30 分钟全量扫一次）**

```java
@XxlJob("releaseTimeoutFallback")
public void releaseTimeoutFallback() {
    List<Long> timeoutOrderIds = workOrderService.findTimeoutAccepted(30);
    for (Long orderId : timeoutOrderIds) {
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent("order:release_lock:" + orderId, "1", 10, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) continue;
        try {
            workOrderService.releaseOrder(orderId);
        } finally {
            redisTemplate.delete("order:release_lock:" + orderId);
        }
    }
}
```

**为什么延迟队列 + 定时任务都要保留？** 延迟队列是精准触发器——30 分钟后恰好触发，不扫百万行表。但延迟消息可能因为 broker 重启/网络抖动/队列堆积而丢失，**消息丢了就是丢了，不会重发**。定时任务每 30 分钟全量扫一次 `idx_created` 索引，兜底捕获那些消息丢失的工单。**主路径快且精准，兜底路径不漏。**

**面试展开点：为什么不用 Redis 过期回调？** Redis 的 keyspace notification 是 best-effort——key 过期瞬间如果 Redis 在做 RDB 快照、主从切换、或 CPU 打满，事件直接丢弃。和 MQ 延迟消息一样，都不如定时扫表可靠。所以延迟消息是提速手段，扫表才是容错底线。

### 3.4 SLA 超时升级 + 通知解耦

```java
@XxlJob("slaEscalationCheck")
public void slaEscalationCheck() {
    // 分页扫表，每批 200 条，用 idx_sla 索引
    List<WorkOrder> expired = workOrderService.findSlaExpired(200);
    for (WorkOrder order : expired) {
        // 通过 MQ 发通知，不阻塞主循环
        rabbitTemplate.convertAndSend("sla.escalation", order.getId());
    }
}
```

**通知消费端——策略模式多渠道路由：**

```java
// 通知渠道接口
public interface NotifyChannel {
    void send(Long userId, String title, String content);
}

// 当前仅实现站内信
@Component
public class InAppNotifyChannel implements NotifyChannel {
    public void send(Long userId, String title, String content) {
        notificationMapper.insert(new Notification(userId, title, content));
    }
}

// MQ 消费端
@RabbitListener(queues = "sla.escalation")
public void onSlaEscalation(Long orderId) {
    WorkOrder order = workOrderMapper.selectById(orderId);
    // 通知所有管理员
    List<Long> adminIds = userService.findUserIdsByRole("SYS_ADMIN");
    for (Long adminId : adminIds) {
        notifyChannel.send(adminId, 
            "工单" + order.getOrderNo() + " SLA超时",
            "类型:" + order.getType() + ",优先级:" + order.getPriority());
    }
}
```

**面试展开点：为什么通知走策略模式但是只实现了站内信？** 策略模式的价值在于扩展点——当前只有站内信，但如果后续要接邮件/企业微信/短信，只需新增一个实现类注册进去，调用方代码一行不改。这是设计模式的正确用法：不是功能越多越牛，而是扩展点留得对。

### 3.5 操作日志 — AOP 非侵入式

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OrderAction {
    String action();
}

@Aspect
@Component
public class OrderLogAspect {
    @Around("@annotation(action)")
    public Object log(ProceedingJoinPoint joinPoint, OrderAction action) throws Throwable {
        Long orderId = (Long) joinPoint.getArgs()[0];
        WorkOrder before = workOrderMapper.selectById(orderId);
        Object result = joinPoint.proceed();
        WorkOrder after = workOrderMapper.selectById(orderId);
        if (!before.getStatus().equals(after.getStatus())) {
            saveLog(orderId, before.getStatus(), after.getStatus(), action.action());
        }
        return result;
    }
}
```

### 3.6 事务边界控制

```
抢单Service:
  ┌────────────────── @Transactional ──────────────────┐
  │                                                      │
  │  1. UPDATE t_work_order SET assignee_id=?, status=?  │
  │     WHERE id=? AND assignee_id IS NULL  ← 行锁     │
  │                                                      │
  │  2. INSERT INTO t_work_order_log (action=ACCEPT)     │
  │                                                      │
  │  3. SET redis key "order:accept_timeout:..."         │
  │                                                      │
  └──────────────────────────────────────────────────────┘
  Redis 操作失败不影响事务提交（事后定时任务兜底）
```

### 3.7 驳回幂等 — Redis Token 防重复提交

**为什么抢单不需要这个，但驳回需要？**

抢单 SQL 是天然幂等的——`WHERE assignee_id IS NULL AND status = 'PENDING'`，第二次点击 affected rows = 0。但驳回操作会递增 `reject_count`，用户狂点"驳回"按钮可能让计数异常飙升，导致不应升级的工单被升级到管理员。

**方案：服务端下发一次性 Token，驳回时原子校验+删除。**

```java
// 1. 前端进入工单详情时，向服务端申请操作 Token
@GetMapping("/api/orders/{id}/action-token")
@SaCheckPermission("order:reject")
public Result<String> getActionToken(@PathVariable Long id) {
    String token = UUID.randomUUID().toString();
    redisTemplate.opsForValue()
        .set("token:reject:" + token, id.toString(), 30, TimeUnit.SECONDS);
    return Result.ok(token);
}

// 2. 驳回时校验 Token——Lua 保证 GET + DEL 原子性
@PostMapping("/api/orders/{id}/reject")
@SaCheckPermission("order:reject")
public Result<Void> reject(@PathVariable Long id, @RequestBody RejectReq req) {
    String script = """
        if redis.call('get', KEYS[1]) == ARGV[1] 
        then return redis.call('del', KEYS[1]) 
        else return 0 
        end""";
    Long result = redisTemplate.execute(
        new DefaultRedisScript<>(script, Long.class),
        List.of("token:reject:" + req.getToken()),
        id.toString()
    );
    if (result == 0) {
        throw new BizException("请勿重复提交");
    }
    workOrderService.rejectOrder(id, req.getRemark());
    return Result.ok();
}
```

**面试展开点：为什么用 Redis 做 Token 而不是存 MySQL？** MySQL 没有原子 GET+DELETE 语义——两个并发请求可能同时查到 Token 存在然后都继续执行。Redis Lua 脚本天然单线程，一个原子操作完成"读→校验→删"，性能也更高。Token 30 秒过期后自动清理，不占存储。

**面试追问：Token 方案能 100% 防重复吗？** 不能，它只防"短时间内同一用户的重复点击"。30 秒后 Token 过期，用户可以再次提交——但这属于"正常操作"而非"重复提交"。真正的兜底在 SQL 层的状态校验——第一次驳回成功后状态已是 `IN_PROGRESS`，第二次驳回的 `WHERE status = 'AWAIT_APPROVAL'` 直接失败。

---

## 四、API 设计

```
POST   /api/orders                   提交工单
GET    /api/orders                   工单列表（按角色+权限范围过滤）
GET    /api/orders/{id}              工单详情（RBAC检查是否本人/处理人/管理员）
GET    /api/orders/{id}/logs         工单操作日志
GET    /api/orders/{id}/action-token 获取操作Token（驳回前申请） [order:reject]
POST   /api/orders/{id}/accept       抢单              [order:accept]
POST   /api/orders/{id}/start        开始处理
POST   /api/orders/{id}/complete     提交验收
POST   /api/orders/{id}/approve      验收通过
POST   /api/orders/{id}/reject       验收驳回（需携带Token）
POST   /api/orders/{id}/assign       管理员分配工单    [order:assign]

GET    /api/notifications            站内信列表
PUT    /api/notifications/{id}/read  标记已读

GET    /api/admin/orders/stats       全局统计          [order:stats:all]
GET    /api/admin/sla/config         查看SLA配置       [sla:config:manage]
PUT    /api/admin/sla/config         更新SLA配置       [sla:config:manage]
GET    /api/admin/users              用户管理          [system:user:manage]

-- 扩展点（不实现，仅留设计注释）--
-- GET  /api/orders/{id}/upload-token   获取OSS预签名URL（客户端直传，减轻服务端带宽压力）
-- POST /api/orders/{id}/attachments    上传完成后回调确认，记录附件元数据
```

**RBAC 数据过滤规则：**
- 提交人：只看到自己提交的工单
- 处理人：看到自己接的单 + 待分配池
- 部门主管：看到本部门所有工单
- 系统管理员：看到全部

---

## 五、Redis Key 设计总表

| Key | 用途 | 过期时间 | 为什么这样设 |
|---|---|---|---|
| `order:accept_timeout:{orderId}` | 接单超时标记 | 30 min | 业务规则，与延迟消息时间对齐 |
| `order:release_lock:{orderId}` | 释放操作分布式锁 | 10 sec | 只锁释放操作本身 |
| `order:seq:{yyyyMMdd}` | 工单编号当日自增 | 1 day | 自增归日，自动重置 |
| `token:reject:{uuid}` | 驳回操作一次性Token | 30 sec | 只防重复点击，短期即可 |
| `user:token:{userId}:{token}` | 登录态 | 7 days | JWT 缓存 |
| `order:count:today:{userId}` | 今日提交工单数 | 当天剩余秒数 | 限制单用户每日提交量 |

---

## 六、智能体嵌入点

**唯一位置：工单提交时的自动 triage。**

```java
@Service
public class OrderTriageService {
    public TriageResult triage(String title, String content) {
        String prompt = """
            根据以下工单内容，判断工单类型和优先级。
            类型可选: REPAIR(报修), LEAVE(请假), REIMBURSE(报销), OTHER(其他)
            优先级: 0(普通), 1(紧急)
            返回JSON: {"type":"REPAIR","priority":1}
            
            工单标题: %s
            工单内容: %s
            """.formatted(title, content);
        
        String response = llmClient.chat(prompt);
        return parseResult(response);
    }
}
```

**设计原则：**
- LLM 只是建议值，提交人可手动修改
- LLM 挂了 → 回退到默认值，工单正常提交
- 唯一接入点，面试时只需为一处决策辩护

---

## 七、数据造数脚本 — 500 万条工单 & Explain 报告

### 7.1 造数存储过程

```sql
DELIMITER $$
CREATE PROCEDURE generate_work_orders(IN total INT)
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE batch_size INT DEFAULT 1000;
    DECLARE random_type VARCHAR(32);
    DECLARE random_priority TINYINT;
    DECLARE random_status VARCHAR(20);
    DECLARE random_submitter BIGINT;
    DECLARE random_assignee BIGINT;
    DECLARE random_reject INT;
    DECLARE base_date DATE DEFAULT '2025-01-01';
    DECLARE random_date DATE;
    
    WHILE i <= total DO
        START TRANSACTION;
        
        SET @j = 0;
        WHILE @j < batch_size AND i <= total DO
            SET random_type = ELT(1 + FLOOR(RAND() * 4), 'REPAIR', 'LEAVE', 'REIMBURSE', 'OTHER');
            SET random_priority = FLOOR(RAND() * 2);
            -- 数据分布: 70% CLOSED(历史单据), 10% PENDING, 10% ACCEPTED, 5% IN_PROGRESS, 5% 其他
            SET random_status = ELT(1 + FLOOR(RAND() * 100), 
                REPEAT('CLOSED', 70), REPEAT('PENDING', 10), 
                REPEAT('ACCEPTED', 10), REPEAT('IN_PROGRESS', 5),
                REPEAT('AWAIT_APPROVAL', 3), REPEAT('RELEASED', 2));
            SET random_submitter = 1 + FLOOR(RAND() * 100);
            SET random_assignee = IF(random_status IN ('PENDING','RELEASED'), NULL, 1 + FLOOR(RAND() * 20));
            SET random_reject = FLOOR(RAND() * 4);
            SET random_date = DATE_ADD(base_date, INTERVAL FLOOR(RAND() * 500) DAY);
            
            INSERT INTO t_work_order (order_no, title, content, type, priority, status,
                submitter_id, assignee_id, reject_count, max_reject, sla_deadline, version, created_at, updated_at)
            VALUES (
                CONCAT('WO-', DATE_FORMAT(random_date, '%Y%m%d'), '-', LPAD(i, 5, '0')),
                CONCAT('工单标题-', i),
                CONCAT('工单内容描述-', i),
                random_type, random_priority, random_status,
                random_submitter, random_assignee, random_reject, 3,
                DATE_ADD(NOW(), INTERVAL 2 HOUR),
                0, random_date, random_date
            );
            
            SET i = i + 1;
            SET @j = @j + 1;
        END WHILE;
        
        COMMIT;
    END WHILE;
END$$
DELIMITER ;

CALL generate_work_orders(5000000);
```

**数据分布设计：**
- 70% CLOSED（模拟历史归档数据）
- 10% PENDING（待抢单池）
- 10% ACCEPTED（待处理中）
- 5% IN_PROGRESS（处理中）
- 3% AWAIT_APPROVAL（待验收）
- 2% RELEASED（已释放）

### 7.2 索引优化对比——面试会用到的 Explain 结果

**优化前（全表扫描）：**
```sql
-- 查询某处理人的待处理工单
EXPLAIN SELECT * FROM t_work_order WHERE assignee_id = 5 AND status = 'ACCEPTED';
-- type: ALL, rows: 5000000  ← 全表扫描，灾难
```

**优化后（索引 + 覆盖索引）：**
```sql
-- idx_assignee 已命中，再加 status 条件
-- type: ref, rows: ~20  ← 索引直查
-- Extra: Using index condition
```

**SLA 超时扫描验证：**
```sql
EXPLAIN SELECT id FROM t_work_order 
WHERE status IN ('PENDING','ACCEPTED','IN_PROGRESS') AND sla_deadline < NOW();
-- type: range, key: idx_sla  ← 联合索引起作用
-- 500万数据中返回 < 1000 条超时工单
```

**面试回答要点：** "我造数 500 万条后跑了 Explain，验证了 `idx_sla` 索引对定时任务扫表的有效性，也确认了 `idx_assignee` 索引在处理人查自己工单列表时不会退化为全表扫描。"

---

## 八、前端方案 — 不为界面拖累后端精力

### 方案：Swagger/Knife4j 在线 API 文档 + Postman Collection

**不使用自建前端页面的理由：**

面试官看的是你的后端设计能力，不是切图。一个 Knife4j 在线文档 + 直接调接口，比一个粗糙的 Vue 页面更专业。

```xml
<!-- pom.xml 加一个依赖即可 -->
<dependency>
    <groupId>com.github.xiaoymin</groupId>
    <artifactId>knife4j-openapi3-jakarta-spring-boot-starter</artifactId>
    <version>4.5.0</version>
</dependency>
```

**如果面试官想要可视化体验：**
- 使用 AI 编程助手生成一套基于 Element-UI 的管理后台模板（3 天工作量外包给 AI）
- 自己只负责联调接口，不在前端设计上花时间
- 职责边界：让 AI 写页面壳子，你维护后端接口和 SQL

**演示时的建议流程：**
1. 先用 Knife4j 在线文档调接口，展示 API 设计和 RBAC 拦截效果
2. 再展示 Explain 执行计划截图，证明索引生效
3. 如果时间充裕，再打开 AI 生成的管理页面做快速演示

---

## 九、面试官会问的 18 个问题 & 你的回答要点

### Q1: 抢单为什么不用 Redis 分布式锁？
**答：** 抢单本质是一个单行 UPDATE 的原子操作，MySQL 的行锁 + WHERE 条件已经解决了并发问题。引入 Redis 分布式锁反而多一次网络 IO，而且锁释放时机复杂。分布式锁应该用在跨多步操作的竞争区间上，单条 SQL 就能原子完成的场景不需要。**知道什么时候不用一个技术，比知道什么时候用更重要。**

### Q2: 你的状态机怎么防止状态跳变？
**答：** 两层防护。第一层：Service 层维护合法状态转移规则表，非法转移直接抛异常。第二层：SQL 更新时带 `WHERE status = #{oldStatus} AND version = #{version}`，并发请求中有一个先改动，另一个的 affected rows = 0 → 回滚。

### Q3: 工单编号为什么不用雪花算法或纯自增 ID？
**答：** 工单编号需要有业务语义——运营人员一眼看出来是什么时候创建的。`WO-20260607-00001` 格式天然含有日期信息。自增 ID 没有语义，雪花算法是纯数字也没有日期。Redis INCR 按天分 key，每天序列从 1 开始，格式清晰且原子安全。另外趋势递增的特性对 B+ 树聚簇索引友好，避免了 UUID 那种随机值导致的页分裂和 Buffer Pool 缓存命中率下降。

### Q4: 超时释放为什么用延迟队列而不是纯定时扫表？
**答：** 延迟队列是精准触发器——30 分钟后恰好触发检查，不需要扫百万行表。但延迟消息可能因为 broker 重启或队列堆积而丢失，所以保留了定时任务每 30 分钟全量扫一次作为兜底。主路径快且精准，兜底路径不漏——两条腿走路。这和 Redis 过期回调的取舍逻辑一致：MQ 延迟消息和 Redis 回调都是"提速"，定时扫表才是"容错底线"。

### Q5: 延迟消息丢了怎么办？如何保证兜底可靠？
**答：** 定时任务每 30 分钟扫一次 `t_work_order_log` 表的 `idx_created` 索引，找到 30 分钟前状态变为 ACCEPTED 但至今仍无后续状态变更的工单，触发释放。扫表用分页（每批 200 条）+ Redis SETNX 分布式锁防多节点重复。即使延迟消息 100% 丢失，最多延迟 30 分钟仍会被释放，没有永久遗漏。

### Q6: 两个释放路径同时触发会冲突吗？
**答：** 不会。释放操作内部用 `WHERE status = 'ACCEPTED' AND version = ?` 乐观锁——延迟消息先到则状态已变为 RELEASED，定时任务的 WHERE 不匹配直接跳过；反之亦然。两个路径争抢的是同一个行锁，不可能重复释放。

### Q7: 驳回为什么加了 Token 防重，但抢单没加？
**答：** 因为我区分了"天然幂等"和"需要额外防重"。抢单 SQL 天然幂等——`WHERE assignee_id IS NULL AND status = 'PENDING'`，第二次点击 affected rows = 0。但驳回会递增 `reject_count`，用户狂点可能导致计数异常飙升，所以需要 Token 机制。**不是每个接口都加防重，而是识别出哪些操作真正需要。**

### Q8: 驳回的 Redis Token 为什么用 Lua 脚本？能 100% 防重吗？
**答：** Lua 保证 GET + DEL 原子性——Redis 单线程执行，两个并发请求不可能同时拿到同一个 Token。MySQL 做不到这一点，即使是 `SELECT ... FOR UPDATE` 也会阻塞而非原子判断。但它只能防 30 秒内的重复点击，30 秒后 Token 过期可以再提交，那是正常操作而非重复提交。真正的兜底在 SQL 层——第一次驳回成功后状态已是 `IN_PROGRESS`，第二次的 `WHERE status = 'AWAIT_APPROVAL'` 直接失败。

### Q9: RBAC 为什么选 Sa-Token 而不是 Spring Security？
**答：** Spring Security 的配置成本和概念复杂度（SecurityFilterChain、AuthenticationManager、UserDetailsService 等）对项目需求而言过重。Sa-Token 一个依赖 + `@SaCheckPermission` 注解即可完成 RBAC，且它的权限码粒度正好匹配我们设计的体系。工程选择要看投入产出比——我评估过两者的能力范围，当前场景下轻量方案更合适。

### Q10: 驳回次数上限到了怎么处理？为什么不是无限驳回？
**答：** 驳回上限防止工单在"处理"和"验收"之间无限循环——这是真实企业工单系统的规则。达到上限后工单进入 `ESCALATED_ADMIN` 状态，通知管理员介入。这个设计让状态机从一维变成二维——状态 + 驳回计数共同决定下一步转移。

### Q11: 你的日志为什么不用 binlog CDC 采集？
**答：** 工单系统 QPS 不超 3 位数，业务事务里直接写日志表足够。CDC 适合对实时性要求极高且不想侵入业务代码的场景（例如同步到 ES），但会引入运维复杂度——Canal/Debezium 组件需要额外维护。

### Q12: 如果工单量大了，定时任务扫表会不会慢？
**答：** 扫表已经从每分钟一次降级为每 30 分钟一次兜底——主路径已被延迟队列取代。即使兜底扫表，`idx_created` 索引保证走索引而非全表扫描。500 万条工单中，30 分钟内变为 ACCEPTED 且仍无变更的通常不到几百条。千万级以上考虑 ShardingSphere 按时间分表。

### Q13: MQ 消息丢了怎么办？为什么不用本地消息表？
**答：** 我区分了消息的重要性等级。当前 MQ 消息只有两类：SLA 升级通知和工单状态变更通知——都是"通知型"消息，丢了的最坏结果是管理员晚几分钟看到，不是数据错误。定时任务的持续扫描本身就是兜底。我用了 publisher confirm + 消费方手动 ACK 打日志，不做补偿重试。**本地消息表用于"交易型"消息（支付回调、库存同步），丢一条就有财务损失。当前场景投入产出比不合理。** 这是工程判断——不是所有 MQ 场景都需要本地消息表。

### Q14: 你的事务范围为什么这样划？
**答：** DB 写入必须同一事务——比如抢单的 UPDATE + INSERT LOG 要么都有要么都没。Redis 不在事务范围，因为 Redis 没有回滚机制。Redis 操作出问题由定时任务兜底，这是最终一致性思路而非强一致性。

### Q15: LLM 判断类型出错了怎么办？
**答：** LLM triage 只是建议值，提交人可手动修改，管理员也可重新分类。LLM 的定位是"减少人工操作"而非"替代人工决策"。LLM 接口调用失败则回退到默认类型 OTHER + 普通优先级，工单正常提交，不影响核心流程。

### Q16: 通知渠道为什么只做了站内信，却设计了策略模式扩展点？
**答：** 策略模式的价值不在当前功能多不多，而在未来扩展的成本——后续接邮件/企业微信只需新增一个实现类，调用方一行不改。设计模式的正确用法是控制未来的变更成本，而不是展示"我用了设计模式"。

### Q17: 附件上传你打算怎么设计？为什么没实现？
**答：** 设计是 OSS 预签名 URL 客户端直传——后端生成带过期时间的上传凭证，前端直接推文件到 OSS，Java 服务带宽压力清零。没实现是因为它不是核心逻辑——工单系统的评审重点在状态机、并发、锁，附件上传不会加分但会消耗开发时间。我在 API 层留了扩展点注释，面试时能讲清楚方案即可。

### Q18: 这个项目你学到的最重要的是什么？
**答：** "工程的核心不是你会用多少工具，是你知道什么时候不用它。"抢单场景一开始我想上分布式锁，后来意识到单条 UPDATE 的 WHERE 条件就是天然并发控制。RBAC 我评估了 Spring Security 后选了 Sa-Token，因为投入产出比更合理。超时释放我拒绝纯定时扫表和纯延迟队列两个极端——选了延迟队列主攻 + 扫表兜底。MQ 消息我没上本地消息表，因为我判断了消息的重要性等级。**每次技术决策都是基于约束条件的取舍，没有银弹。**

---

## 十、部署方案

```yaml
# docker-compose.yml
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: work_order
    ports:
      - "3306:3306"
    volumes:
      - ./sql/init.sql:/docker-entrypoint-initdb.d/init.sql
    command: --innodb-buffer-pool-size=512M
  
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
  
  rabbitmq:
    image: rabbitmq:3-management
    ports:
      - "5672:5672"
      - "15672:15672"
  
  xxl-job-admin:
    image: xuxueli/xxl-job-admin:2.4.0
    depends_on: [mysql]
    ports:
      - "8080:8080"
  
  app:
    build: .
    ports:
      - "9000:9000"
    depends_on: [mysql, redis, rabbitmq]
```

2C4G 云服务器，`docker compose up -d` 一把拉起。

---

## 十一、开发顺序建议

| 天数 | 内容 | 产出 |
|---|---|---|
| 1-2 | 项目脚手架 + 建表 + Sa-Token 集成 + 用户登录 | 能跑的项目 |
| 3-4 | 工单 CRUD + Redis 编号生成 + 状态机 | 核心流程走通 |
| 5-6 | RBAC 权限体系（用户/角色/权限 + 接口拦截 + 数据过滤） | **完整的权限体系** |
| 7-8 | 抢单（原子 SQL + 乐观锁） + 驳回（状态机 + 计数 + Token 防重） | **第一个深度点** |
| 9-10 | 超时释放（延迟队列主攻 + 定时扫表兜底） + SLA 升级 + MQ 通知 | **第二个深度点** |
| 11 | 站内信（策略模式） + 操作日志 AOP | 收尾 |
| 12 | LLM triage 接入（建议值 + 失败回退） | 智能体亮点 |
| 13 | 500 万造数 + Explain 索引对比 + 截图存档 | **面试弹药** |
| 14 | Knife4j 文档 + Docker 部署 + 18 问面试话术准备 | 交付 |

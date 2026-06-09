# 企业工单流转平台 — 工程待办清单

> 拆解粒度：每个 Issue 控制在 1-2 小时工作量。
> 拆解范围：第 1 天 ~ 第 8 天（共 35 个 Issue）。
> 里程碑 1（Day 1-4, Issue #1~#21）：工单底座 + 权限底座 ✅ 已交付
> 里程碑 2（Day 5-8, Issue #22~#35）：RBAC 权限体系 + 抢单/驳回状态机 ⬅ 当前冲刺

---

## 第 1 天：项目脚手架 + 基础设施

### - [ ] Issue #1: 创建 Spring Boot 3 + Maven 项目并配置 pom.xml

**具体目标：**
使用 Spring Initializr（或手动）创建 Maven 项目，引入全部依赖：Spring Boot Web、MyBatis-Plus、MySQL Driver、Sa-Token、Redis、RabbitMQ、Knife4j、Lombok、Validation、XXL-Job。确保 `mvn compile` 通过。

**涉及文件：**
- `pom.xml`
- `src/main/java/com/workorder/WorkOrderApplication.java`

**验收标准：**
- [ ] `pom.xml` 中包含以下依赖：spring-boot-starter-web、mybatis-plus-boot-starter、mysql-connector-j、sa-token-spring-boot3-starter、sa-token-redis-jackson、spring-boot-starter-data-redis、spring-boot-starter-amqp、knife4j-openapi3-jakarta-spring-boot-starter（4.5.0）、lombok、spring-boot-starter-validation、xxl-job-core
- [ ] `mvn clean compile` 在命令行执行无报错
- [ ] `WorkOrderApplication.java` 有 `@SpringBootApplication` 注解且能启动（端口占用的报错可以忽略，只要 Spring 上下文加载成功）

---

### - [ ] Issue #2: 配置 application.yml 多环境基础配置

**具体目标：**
编写 `application.yml` 主配置文件（数据源、Redis、RabbitMQ、Sa-Token、MyBatis-Plus、Knife4j、Server 端口），确保每个中间件连接参数正确。

**涉及文件：**
- `src/main/resources/application.yml`（或 `application-dev.yml`）

**验收标准：**
- [ ] `application.yml` 中 `spring.datasource` 指向 `jdbc:mysql://localhost:3306/work_order`，用户名/密码为 root/root
- [ ] `spring.redis` 配置 host=localhost, port=6379
- [ ] `spring.rabbitmq` 配置 host=localhost, port=5672, username=guest, password=guest
- [ ] `sa-token.token-name=Authorization`，`sa-token.timeout=604800`（7天）
- [ ] `mybatis-plus.configuration.map-underscore-to-camel-case=true`
- [ ] `server.port=9000`
- [ ] `knife4j.enable=true`
- [ ] `xxl.job.admin.addresses=http://localhost:8080/xxl-job-admin`

---

### - [ ] Issue #3: 编写 Docker Compose 基础设施编排文件

**具体目标：**
编写 `docker-compose.yml`，一键启动 MySQL 8.0、Redis 7、RabbitMQ（带管理插件）、XXL-Job Admin 四个中间件。MySQL 挂载 `init.sql` 自动建表。

**涉及文件：**
- `docker-compose.yml`
- `sql/init.sql`（空文件，后续 Issue 填充）

**验收标准：**
- [ ] `docker compose up -d` 执行后四个容器状态均为 Up
- [ ] `docker ps` 可以看到 mysql:8.0、redis:7-alpine、rabbitmq:3-management、xuxueli/xxl-job-admin:2.4.0 容器
- [ ] `docker compose down` 可正常停止并移除容器
- [ ] MySQL 容器健康检查通过（`docker compose ps` 显示 healthy）

---

### - [ ] Issue #4: 编写 init.sql — 核心业务表（t_work_order + t_work_order_log）

**具体目标：**
写出 `t_work_order` 和 `t_work_order_log` 两张核心业务表的完整 DDL，字段、索引、注释严格对齐技术方案 1.1 和 1.2 节。

**涉及文件：**
- `sql/init.sql`（追加内容）

**验收标准：**
- [ ] `t_work_order` 包含所有字段：id（自增主键）、order_no（VARCHAR(22) UNIQUE）、title、content、type、priority（TINYINT DEFAULT 0）、status（VARCHAR(20) DEFAULT 'PENDING'）、submitter_id、assignee_id（可空）、reject_count（DEFAULT 0）、max_reject（DEFAULT 3）、sla_deadline（可空）、version（DEFAULT 0）、created_at、updated_at
- [ ] 五个索引：idx_order_no（UNIQUE）、idx_status、idx_submitter、idx_assignee、idx_sla（status, sla_deadline 联合索引）
- [ ] `t_work_order_log` 包含：id、order_id、order_no、operator_id、action、old_status（可空）、new_status、remark（VARCHAR(500) 可空）、created_at
- [ ] 三个索引：idx_order（order_id）、idx_order_no、idx_created（created_at）
- [ ] ENGINE=InnoDB, CHARSET=utf8mb4
- [ ] 在 MySQL 8.0 中执行无语法错误

---

### - [ ] Issue #5: 编写 init.sql — RBAC 五张表

**具体目标：**
写出 `t_user`、`t_role`、`t_permission`、`t_user_role`、`t_role_permission` 五张权限表的 DDL。

**涉及文件：**
- `sql/init.sql`（追加内容）

**验收标准：**
- [ ] `t_user` 字段：id、username（VARCHAR(50) UNIQUE）、password（VARCHAR(255)）、phone（可空）、dept_id（可空）、status（TINYINT DEFAULT 1）、created_at
- [ ] `t_role` 字段：id、role_code（VARCHAR(32) UNIQUE）、role_name、remark
- [ ] `t_permission` 字段：id、perm_code（VARCHAR(64) UNIQUE）、perm_name、parent_id（DEFAULT 0）
- [ ] `t_user_role` 联合主键：(user_id, role_id)
- [ ] `t_role_permission` 联合主键：(role_id, permission_id)
- [ ] 在 MySQL 8.0 中执行无语法错误

---

### - [ ] Issue #6: 编写 init.sql — 辅助表（t_sla_config + t_notification）+ 种子数据

**具体目标：**
写出 `t_sla_config` 和 `t_notification` 两张辅助表的 DDL，并写入默认角色、权限、管理员用户的 INSERT 种子数据。

**涉及文件：**
- `sql/init.sql`（追加内容）

**验收标准：**
- [ ] `t_sla_config` 字段：id、type、priority、accept_minutes、finish_minutes，UNIQUE KEY uk_type_priority (type, priority)
- [ ] `t_notification` 字段：id、user_id、title、content、ref_type、ref_id、is_read（DEFAULT 0）、created_at，索引 idx_user_read (user_id, is_read, created_at)
- [ ] INSERT 4 条角色：SUBMITTER、HANDLER、DEPT_ADMIN、SYS_ADMIN
- [ ] INSERT 12 条权限：order:accept、order:start、order:complete、order:approve、order:reject、order:assign、order:stats、order:stats:all、system:user:manage、system:role:manage、sla:config:manage（以及父级菜单权限，如 order:*、system:*、sla:*）
- [ ] INSERT 1 个管理员用户（admin / BCrypt 加密后的密码，username=admin, 明文可设为 admin123）
- [ ] INSERT 角色-权限关联数据（SYS_ADMIN 拥有全部权限）
- [ ] INSERT 用户-角色关联数据（admin 绑定 SYS_ADMIN 角色）

---

## 第 2 天：通用基础设施 + Sa-Token + 用户登录

### - [ ] Issue #7: 创建项目包结构 + 统一响应体 Result + 业务异常类

**具体目标：**
创建标准分层包结构，编写 `Result<T>` 统一响应体和 `BizException` 业务异常类，确保所有 Controller 返回格式一致。

**涉及文件：**
- `src/main/java/com/workorder/common/Result.java`
- `src/main/java/com/workorder/common/BizException.java`
- `src/main/java/com/workorder/common/ErrorCode.java`（错误码枚举）
- 创建包目录：`config/`、`controller/`、`service/`、`service/impl/`、`mapper/`、`entity/`、`common/`、`common/enums/`、`utils/`

**验收标准：**
- [ ] `Result<T>` 包含 `code`、`message`、`data` 三个字段，提供静态工厂方法 `Result.ok(T data)` 和 `Result.fail(String code, String message)`
- [ ] `BizException` 继承 `RuntimeException`，携带 `ErrorCode`（code + message）
- [ ] `ErrorCode` 枚举至少包含：SUCCESS(200, "操作成功")、BAD_REQUEST(400, "参数错误")、UNAUTHORIZED(401, "未登录")、FORBIDDEN(403, "无权限")、NOT_FOUND(404, "资源不存在")、CONFLICT(409, "状态冲突")、INTERNAL_ERROR(500, "服务器内部错误")
- [ ] 新建的包目录结构在 IDE 中可见

---

### - [ ] Issue #8: 全局异常处理器 + 参数校验异常拦截

**具体目标：**
编写 `GlobalExceptionHandler`（`@RestControllerAdvice`），拦截 `BizException`、`MethodArgumentNotValidException`、`BindException`、`Exception`，统一返回 `Result` 格式。启用 Spring Validation。

**涉及文件：**
- `src/main/java/com/workorder/config/GlobalExceptionHandler.java`

**验收标准：**
- [ ] `BizException` 被捕获后返回 `Result.fail(exception.getCode(), exception.getMessage())`
- [ ] `MethodArgumentNotValidException` 被捕获后返回 400 + 第一条字段校验错误信息
- [ ] `Exception` 兜底捕获，返回 500 + 通用错误信息（不泄露堆栈）
- [ ] 在 Controller 中手动 `throw new BizException(ErrorCode.CONFLICT, "工单已被抢走")` 返回 JSON `{"code":409,"message":"工单已被抢走","data":null}`
- [ ] 用 `@Valid` + `@NotBlank` 注解在 DTO 上验证，发送空字段能收到校验错误 JSON

---

### - [ ] Issue #9: Sa-Token 集成与 RBAC 注解基础验证

**具体目标：**
配置 Sa-Token（读取 application.yml 中的 token 配置），创建 `SaTokenConfig`（如有自定义需求）和 `StpInterfaceImpl`（权限加载实现类），通过一个临时测试接口验证 `@SaCheckPermission` 注解生效。

**涉及文件：**
- `src/main/java/com/workorder/config/SaTokenConfig.java`（可选，如果默认配置够用则不创建）
- `src/main/java/com/workorder/config/StpInterfaceImpl.java`（实现 `StpInterface`，从 DB 查询权限码列表）
- `src/main/java/com/workorder/controller/HealthController.java`（临时测试接口）

**验收标准：**
- [ ] `StpInterfaceImpl` 实现 `getPermissionList(Object loginId, String loginType)`，查询逻辑：根据 userId 联表查出所有 perm_code 并返回列表
- [ ] `HealthController` 中写一个 `@SaCheckPermission("order:stats:all")` 注解的 GET 接口
- [ ] 未登录时调用该接口返回 401（Sa-Token 默认行为）
- [ ] 登录后（用 `StpUtil.login(userId)` 模拟），如果用户有 `order:stats:all` 权限则返回 200，否则返回 403
- [ ] 确认 Sa-Token Redis 集成生效——登录后可在 Redis 中看到 token 相关 key

---

### - [ ] Issue #10: 用户注册 API（User 实体 + Mapper + Service + Controller）

**具体目标：**
创建 `User` 实体（MyBatis-Plus 映射 `t_user` 表）、`UserMapper`、`UserService` 注册逻辑（BCrypt 密码加密），暴露 `POST /api/users/register` 接口。

**涉及文件：**
- `src/main/java/com/workorder/entity/User.java`
- `src/main/java/com/workorder/mapper/UserMapper.java`
- `src/main/java/com/workorder/service/UserService.java`
- `src/main/java/com/workorder/service/impl/UserServiceImpl.java`
- `src/main/java/com/workorder/controller/UserController.java`
- `src/main/java/com/workorder/common/dto/RegisterReq.java`（DTO）

**验收标准：**
- [ ] `User` 实体用 `@TableName("t_user")`，字段与数据库一致，Lombok `@Data`
- [ ] `UserMapper` 继承 `BaseMapper<User>`，无需自定义方法
- [ ] `UserService.register(RegisterReq req)` 逻辑：校验 username 唯一性 → BCrypt 加密密码 → 插入记录（默认 status=1，role 不在此处分配）→ 返回 User
- [ ] `POST /api/users/register` 请求体 `{"username":"test","password":"123456","phone":"13800138000"}` 返回 `Result<User>`，密码字段不返回
- [ ] 重复 username 注册返回错误信息 "用户名已存在"
- [ ] 数据库 `t_user` 表中出现新记录，password 字段存的是 BCrypt 密文（$2a$ 开头）

---

### - [ ] Issue #11: 用户登录 API（Sa-Token 登录 + 返回 Token）

**具体目标：**
实现登录逻辑：校验用户名密码 → BCrypt 密码匹配 → `StpUtil.login(userId)` → 返回 Token 和用户基本信息。

**涉及文件：**
- `src/main/java/com/workorder/service/UserService.java`（新增 login 方法）
- `src/main/java/com/workorder/service/impl/UserServiceImpl.java`（新增 login 实现）
- `src/main/java/com/workorder/controller/UserController.java`（新增 POST /api/users/login）
- `src/main/java/com/workorder/common/dto/LoginReq.java`
- `src/main/java/com/workorder/common/vo/LoginVO.java`

**验收标准：**
- [ ] `POST /api/users/login` 请求体 `{"username":"admin","password":"admin123"}` 返回 token（Sa-Token 生成的字符串）和用户基本信息（id、username、phone）
- [ ] 密码错误返回 "用户名或密码错误"
- [ ] 用户被禁用（status=0）返回 "账号已被禁用"
- [ ] 登录成功后，调用 `StpUtil.isLogin()` 返回 true
- [ ] 登录成功后，调用 `StpUtil.getLoginIdAsLong()` 返回正确的 userId

---

### - [ ] Issue #12: Docker Compose 启动 + 项目首次启动 + Knife4j 可访问验证

**具体目标：**
整合前面所有 Issue 的产出：启动 Docker 中间件 → 执行 init.sql → 启动 Spring Boot → 访问 Knife4j 文档页面 → 用 Knife4j 调用 register + login 接口完成冒烟测试。

**涉及文件：**
- 无新建文件（验证已有产出）

**验收标准：**
- [ ] `docker compose up -d` 四个容器全部 Running
- [ ] 用 DBeaver/Navicat 连接 MySQL，确认全部 9 张表已创建，种子数据已插入
- [ ] `mvn spring-boot:run` 启动无报错，日志显示 "Started WorkOrderApplication in X seconds"
- [ ] 浏览器访问 `http://localhost:9000/doc.html` 显示 Knife4j 接口文档页面
- [ ] 在 Knife4j 中执行 `POST /api/users/register` 注册一个新用户 → 返回 200
- [ ] 在 Knife4j 中执行 `POST /api/users/login` 用刚注册的用户登录 → 返回 token
- [ ] 在 Knife4j 中将返回的 token 填入全局 Auth Header（Authorization: <token>），后续接口自动携带

---

## 第 3 天：工单核心 CRUD + Redis 编号生成

### - [ ] Issue #13: WorkOrder 实体类与 MyBatis-Plus 映射

**具体目标：**
创建 `WorkOrder` 实体类，用 MyBatis-Plus 注解精确映射 `t_work_order` 表（包含驼峰命名转换、主键策略、乐观锁字段）。

**涉及文件：**
- `src/main/java/com/workorder/entity/WorkOrder.java`

**验收标准：**
- [ ] `@TableName("t_work_order")` 注解
- [ ] `id` 字段：`@TableId(type = IdType.AUTO)`，`Long` 类型
- [ ] `version` 字段：`@Version`（MyBatis-Plus 乐观锁插件需要）
- [ ] 所有字段与 `t_work_order` 表一一对应，类型正确（priority 用 Integer、status 用 String、rejectCount/maxReject 用 Integer、slaDeadline 用 LocalDateTime、createdAt/updatedAt 用 LocalDateTime）
- [ ] 用 Lombok `@Data`，不写 getter/setter
- [ ] 实体类编译无报错

---

### - [ ] Issue #14: WorkOrderMapper 基础查询 + MyBatis-Plus 分页插件配置

**具体目标：**
创建 `WorkOrderMapper` 继承 MyBatis-Plus `BaseMapper`，配置 MyBatis-Plus 分页插件，编写自定义分页查询方法（支持按状态、提交人、处理人等条件组合过滤）。

**涉及文件：**
- `src/main/java/com/workorder/mapper/WorkOrderMapper.java`
- `src/main/java/com/workorder/config/MyBatisPlusConfig.java`（分页插件 + 乐观锁插件）

**验收标准：**
- [ ] `WorkOrderMapper` 继承 `BaseMapper<WorkOrder>`
- [ ] 自定义方法 `selectPageWithConditions(Page<WorkOrder> page, @Param("status") String status, @Param("submitterId") Long submitterId, @Param("assigneeId") Long assigneeId)` 使用 MyBatis-Plus 的 `QueryWrapper` 或在 XML 中写动态 SQL
- [ ] `MyBatisPlusConfig` 中 `@Bean` 注册 `MybatisPlusInterceptor`，添加 `PaginationInnerInterceptor` 和 `OptimisticLockerInnerInterceptor`
- [ ] 编写单元测试（用 `@MybatisPlusTest` 或直接启动上下文）：插入 5 条 WorkOrder，调用 `selectPageWithConditions` 验证分页结果正确

---

### - [ ] Issue #15: Redis 配置验证 + OrderNoGenerator 工单编号生成器

**具体目标：**
配置 `RedisTemplate` 的序列化方式（Jackson2Json），实现 `OrderNoGenerator`——用 Redis INCR 原子自增生成 `WO-YYYYMMDD-XXXXX` 格式的工单编号，每日自动重置。

**涉及文件：**
- `src/main/java/com/workorder/config/RedisConfig.java`
- `src/main/java/com/workorder/utils/OrderNoGenerator.java`

**验收标准：**
- [ ] `RedisConfig` 中配置 `RedisTemplate<String, Object>`，Key 用 String 序列化，Value 用 Jackson2Json 序列化
- [ ] `OrderNoGenerator.next()` 生成编号格式为 `WO-20260607-00001`（日期为当天）
- [ ] 当天第一次调用时 INCR 返回 1，Redis key 为 `order:seq:20260607` 并被设置过期时间为 1 天
- [ ] 同一天多次调用，序号依次递增：00001 → 00002 → 00003
- [ ] 编写单元测试：调用 3 次 `next()`，验证三个编号的序号部分递增且格式正确
- [ ] Redis 中能看到 `order:seq:yyyyMMdd` 的 key 及其 TTL

---

### - [ ] Issue #16: 工单状态机枚举 + 状态转移校验器

**具体目标：**
创建 `Status` 枚举（定义 7 个状态值），创建 `StateMachineValidator`（包含合法转移规则表 + 校验方法），禁止非法转移。

**涉及文件：**
- `src/main/java/com/workorder/common/enums/Status.java`
- `src/main/java/com/workorder/common/enums/OrderAction.java`（操作枚举：ACCEPT/START/COMPLETE/APPROVE/REJECT/ASSIGN/RELEASE）
- `src/main/java/com/workorder/service/StateMachineValidator.java`

**验收标准：**
- [ ] `Status` 枚举包含：PENDING、ACCEPTED、IN_PROGRESS、AWAIT_APPROVAL、CLOSED、RELEASED、ESCALATED_ADMIN
- [ ] `OrderAction` 枚举包含：ACCEPT、START、COMPLETE、APPROVE、REJECT、ASSIGN、RELEASE
- [ ] `StateMachineValidator.validate(Status current, OrderAction action)` 依据技术方案 2.1 节的状态转移表严格校验：
  - [ ] PENDING + ACCEPT → ACCEPTED（合法）
  - [ ] ACCEPTED + START → IN_PROGRESS（合法）
  - [ ] IN_PROGRESS + COMPLETE → AWAIT_APPROVAL（合法）
  - [ ] AWAIT_APPROVAL + APPROVE → CLOSED（合法）
  - [ ] PENDING + START → 抛异常（非法）
  - [ ] CLOSED + REJECT → 抛异常（非法）
  - [ ] 共 8 条合法转移 + 任意不在表中的组合均抛 `BizException("非法的状态转移: {current} -> {action}")`
- [ ] 编写单元测试覆盖全部 8 条合法转移 + 至少 3 条非法转移case

---

## 第 4 天：工单提交流程 + API 上线

### - [ ] Issue #17: 工单提交 Service 层（编号生成 + 状态初始化 + SLA 计算 + 日志记录）

**具体目标：**
实现 `WorkOrderService.submitOrder(SubmitOrderReq req, Long submitterId)` 完整提交流程：生成编号 → 查询 SLA 配置计算 deadline → INSERT 工单记录 → INSERT 操作日志。整个过程用 `@Transactional` 保证原子性。

**涉及文件：**
- `src/main/java/com/workorder/service/WorkOrderService.java`
- `src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java`
- `src/main/java/com/workorder/mapper/WorkOrderMapper.java`（可能需要新增 insert 返回 id 的确认）
- `src/main/java/com/workorder/mapper/WorkOrderLogMapper.java`
- `src/main/java/com/workorder/service/impl/WorkOrderLogServiceImpl.java`
- `src/main/java/com/workorder/service/WorkOrderLogService.java`
- `src/main/java/com/workorder/mapper/SlaConfigMapper.java`
- `src/main/java/com/workorder/entity/SlaConfig.java`
- `src/main/java/com/workorder/common/dto/SubmitOrderReq.java`

**验收标准：**
- [ ] `submitOrder` 方法上标注 `@Transactional(rollbackFor = Exception.class)`
- [ ] 提交逻辑顺序：① `orderNoGenerator.next()` 生成编号 → ② 查 `t_sla_config` 表根据 type+priority 取 `finish_minutes`，计算 `slaDeadline = now + finishMinutes` → ③ INSERT `t_work_order`（status=PENDING, version=0） → ④ INSERT `t_work_order_log`（action=SUBMIT, old_status=null, new_status=PENDING）
- [ ] 如果 `t_sla_config` 中无匹配配置，`sla_deadline` 设为 NULL（不阻断提交流程）
- [ ] 任意步骤失败（如工单编号生成异常）整个事务回滚，`t_work_order` 和 `t_work_order_log` 均无脏数据
- [ ] 单元测试：调用 submitOrder → 断言数据库中出现一条 status=PENDING 的记录 + 一条 action=SUBMIT 的日志

---

### - [ ] Issue #18: 工单列表查询与详情查询 Service 层

**具体目标：**
实现 `WorkOrderService.listOrders(PageQuery query, Long currentUserId)` 分页查询和 `WorkOrderService.getOrderDetail(Long orderId)` 详情查询。列表支持按状态筛选、按工单编号精确搜索。

**涉及文件：**
- `src/main/java/com/workorder/service/WorkOrderService.java`（新增 listOrders、getOrderDetail 方法签名）
- `src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java`（新增实现）
- `src/main/java/com/workorder/common/dto/PageQuery.java`
- `src/main/java/com/workorder/common/vo/WorkOrderVO.java`

**验收标准：**
- [ ] `listOrders` 使用 MyBatis-Plus `Page<WorkOrder>` 分页，默认 pageSize=20，最大不超过 100
- [ ] 支持可选参数：status（精确匹配）、orderNo（模糊搜索 LIKE '%xxx%'）、submitterId、assigneeId
- [ ] 返回 `PageResult<WorkOrderVO>`，包含 total、pages、current、records
- [ ] `getOrderDetail` 根据 orderId 查询单条，不存在时抛出 `BizException(ErrorCode.NOT_FOUND, "工单不存在")`
- [ ] 返回的 `WorkOrderVO` 不暴露 `version` 字段（乐观锁版本号对内使用）
- [ ] 单元测试：插入 10 条不同状态的工单，调用 listOrders 按 status 筛选，验证分页结果数和内容正确

---

### - [ ] Issue #19: 工单操作日志查询 Service 层

**具体目标：**
创建 `WorkOrderLog` 实体和 Mapper，实现按工单 ID 查询全量操作日志（按时间正序），供前端时间线展示。

**涉及文件：**
- `src/main/java/com/workorder/entity/WorkOrderLog.java`
- `src/main/java/com/workorder/mapper/WorkOrderLogMapper.java`
- `src/main/java/com/workorder/service/WorkOrderLogService.java`
- `src/main/java/com/workorder/service/impl/WorkOrderLogServiceImpl.java`
- `src/main/java/com/workorder/common/vo/WorkOrderLogVO.java`

**验收标准：**
- [ ] `WorkOrderLog` 实体映射 `t_work_order_log` 表，字段完整
- [ ] `WorkOrderLogMapper` 继承 `BaseMapper<WorkOrderLog>`，自定义方法 `selectByOrderId(Long orderId)` 返回 `List<WorkOrderLog>`，按 `created_at ASC` 排序
- [ ] `WorkOrderLogService.queryLogs(Long orderId)` 返回 `List<WorkOrderLogVO>`，VO 中包含操作人姓名（需要 JOIN t_user 表查 username）
- [ ] 单元测试：对某工单插入 3 条日志（SUBMIT → ACCEPT → START），调用 queryLogs 返回 3 条结果且顺序正确

---

### - [ ] Issue #20: WorkOrderController REST API 完整上线

**具体目标：**
创建 `WorkOrderController`，暴露工单提交、列表查询、详情查询、日志查询四个接口，所有接口携带 Sa-Token 鉴权（提取当前登录用户 ID），统一返回 `Result` 格式。

**涉及文件：**
- `src/main/java/com/workorder/controller/WorkOrderController.java`
- `src/main/java/com/workorder/common/dto/SubmitOrderReq.java`（补充 Validation 注解）
- `src/main/java/com/workorder/common/vo/WorkOrderVO.java`
- `src/main/java/com/workorder/common/vo/WorkOrderDetailVO.java`（包含工单信息 + 日志列表）

**验收标准：**
- [ ] `POST /api/orders` — 提交工单
  - [ ] 请求体 `SubmitOrderReq` 包含 `@NotBlank title`、`@NotBlank content`、`@NotBlank type`、`Integer priority`
  - [ ] 用 `StpUtil.getLoginIdAsLong()` 获取当前登录用户 ID 作为 submitterId
  - [ ] 成功后返回工单完整信息（包含生成的 orderNo）
- [ ] `GET /api/orders` — 工单列表
  - [ ] 支持 query 参数：status、orderNo、page、size
  - [ ] 返回分页结果
- [ ] `GET /api/orders/{id}` — 工单详情
  - [ ] 返回 `WorkOrderDetailVO`（工单信息 + 操作日志列表）
- [ ] `GET /api/orders/{id}/logs` — 工单操作日志
  - [ ] 返回该工单的全部操作日志，按时间正序
- [ ] 所有接口在 Knife4j 中按 "工单管理" 分组显示（`@Tag(name = "工单管理")`）
- [ ] 在 Knife4j 中测试完整流程：登录 → 提交工单 → 查列表 → 查详情 → 查日志

---

### - [ ] Issue #21: 端到端流程验证 + Knife4j 接口文档整理

**具体目标：**
端到端验证第 1~4 天所有产出的完整性：从 Docker 环境 → 用户注册 → 登录 → 提交工单 → 查看列表 → 查看详情 → 查看日志的完整闭环。为所有 API 添加 Knife4j 文档注解（`@Operation` 描述），确保接口文档可读性。

**涉及文件：**
- `src/main/java/com/workorder/controller/UserController.java`（补充 Knife4j 注解）
- `src/main/java/com/workorder/controller/WorkOrderController.java`（补充 Knife4j 注解）
- `src/main/java/com/workorder/common/dto/SubmitOrderReq.java`（补充 `@Schema` 注解）

**验收标准：**
- [ ] 所有 Controller 方法有 `@Operation(summary = "...")` 注解
- [ ] 所有 DTO 字段有 `@Schema(description = "...")` 注解（至少关键字段）
- [ ] Knife4j 文档页面（`/doc.html`）展示清晰的接口分组：用户管理、工单管理
- [ ] 端到端脚本验证（手动或用 Knife4j 顺序调用）：
  1. 注册新用户 handler01
  2. 用 handler01 登录，拿到 token
  3. 提交一条工单（title="空调报修", content="3楼空调不制冷", type="REPAIR", priority=0）→ 返回 status=PENDING, orderNo 格式正确
  4. 调用 `GET /api/orders` 列表接口 → 能看到刚提交的工单
  5. 调用 `GET /api/orders/{id}` 详情接口 → 包含工单信息 + 一条 SUBMIT 日志
  6. 调用 `GET /api/orders/{id}/logs` → 返回 1 条操作日志
- [ ] Docker 环境重启后（`docker compose down && docker compose up -d`）系统能再次正常运行，数据不丢（MySQL 数据卷挂载）

---

## 第 5 天：RBAC 管理服务层（实体/Mapper 已于 Day 1-2 完成）

### - [ ] Issue #22: RoleService — 角色 CRUD + 权限分配业务逻辑

**具体目标：**
基于已存在的 `Role`、`Permission`、`RolePermission` 实体与 Mapper（Day 2 Issue #9 已为 `StpInterfaceImpl` 创建），实现 `RoleService` 业务层：角色 CRUD、为角色分配/移除权限、校验角色编码唯一性。所有写操作用 `@Transactional` 保证原子性。

**涉及文件：**
- `src/main/java/com/workorder/service/RoleService.java`
- `src/main/java/com/workorder/service/impl/RoleServiceImpl.java`
- `src/main/java/com/workorder/common/dto/RoleCreateReq.java`
- `src/main/java/com/workorder/common/dto/RoleUpdateReq.java`
- `src/main/java/com/workorder/common/dto/RolePermissionAssignReq.java`
- `src/main/java/com/workorder/common/vo/RoleVO.java`

**验收标准：**
- [ ] `createRole(RoleCreateReq req)`：校验 `roleCode` 唯一性 → INSERT → 返回 Role
- [ ] `updateRole(Long id, RoleUpdateReq req)`：校验存在性 → UPDATE → 返回更新后数据
- [ ] `deleteRole(Long id)`：校验 `t_user_role` 中无用户关联 → DELETE role + DELETE role_permission 关联
- [ ] `listRoles()`：返回全部角色列表
- [ ] `getRoleDetail(Long roleId)`：返回角色信息 + 已分配权限 ID 列表
- [ ] `assignPermissions(Long roleId, List<Long> permIds)`：原子操作——先 DELETE 该角色全部旧权限关联 → 批量 INSERT 新关联，`@Transactional`
- [ ] 保护种子数据：禁止删除 `role_code IN ('SUBMITTER','HANDLER','DEPT_ADMIN','SYS_ADMIN')` 的角色
- [ ] 单元测试：创建角色 → 分配权限 → 查询验证 → 删除（无用户关联时成功）

---

### - [ ] Issue #23: PermissionService — 权限查询 + 权限树组装

**具体目标：**
基于已存在的 `PermissionMapper`（Day 2 Issue #9 已为 `StpInterfaceImpl` 创建），实现 `PermissionService`：全量权限列表查询、按角色查权限、按 parentId 组装树形结构。

**涉及文件：**
- `src/main/java/com/workorder/service/PermissionService.java`
- `src/main/java/com/workorder/service/impl/PermissionServiceImpl.java`
- `src/main/java/com/workorder/common/vo/PermissionTreeVO.java`

**验收标准：**
- [ ] `listAll()`：返回全部权限列表（按 id 排序）
- [ ] `listByRoleId(Long roleId)`：返回指定角色的权限列表
- [ ] `buildTree()`：从平铺权限列表按 `parentId` 组装为树形结构（父节点包含 children 列表）
  - [ ] 第一层为 parentId=0 的根节点（如 order:*、system:*、sla:*）
  - [ ] 第二层为叶子权限码（如 order:accept、system:user:manage 等）
- [ ] 单元测试：调用 `buildTree()` → 验证树的层级结构正确（至少 3 个根节点 + 各含子节点）

---

### - [ ] Issue #24: UserService 扩展 — 用户角色分配 + 用户列表分页查询

**具体目标：**
扩展 `UserService`（Day 2 Issue #10 中已创建，含 register/login 逻辑）：新增管理员功能——为用户分配/移除角色、按条件分页查询用户列表、查询用户详情（含角色和权限）。

**涉及文件：**
- `src/main/java/com/workorder/service/UserService.java`（新增方法签名）
- `src/main/java/com/workorder/service/impl/UserServiceImpl.java`（新增实现）
- `src/main/java/com/workorder/common/dto/UserRoleAssignReq.java`
- `src/main/java/com/workorder/common/vo/UserDetailVO.java`

**验收标准：**
- [ ] `assignRoles(Long userId, List<Long> roleIds)`：校验用户存在 → DELETE 旧关联 → 批量 INSERT，`@Transactional`
- [ ] `listUsers(Integer page, Integer size, String username, Long deptId)`：分页查询，支持按 username 模糊搜索、deptId 精确筛选，返回 `IPage<UserDetailVO>`（含用户信息 + 角色编码列表）
- [ ] `getUserDetail(Long userId)`：返回用户基本信息 + 角色列表（roleCode+roleName）+ 权限码列表（permCode）
- [ ] 硬编码保护：admin 用户的角色不允许被清空（至少保留一个角色）
- [ ] 单元测试：为 test 用户分配 HANDLER 角色 → `getUserDetail` 验证 role 和 permission 正确返回

---

## 第 6 天：RBAC Controller + 数据过滤 + 统计

### - [ ] Issue #25: AdminController — 角色/权限/用户管理 + SLA 配置 REST API

**具体目标：**
创建 `RoleController` 和 `AdminController`，暴露角色 CRUD、权限树查询、用户角色分配、用户列表、SLA 配置查询/更新等管理员接口。所有接口标注 `@SaCheckPermission` 权限注解和 Knife4j 文档注解。

**涉及文件：**
- `src/main/java/com/workorder/controller/RoleController.java`
- `src/main/java/com/workorder/controller/AdminController.java`
- `src/main/java/com/workorder/common/dto/SlaConfigUpdateReq.java`

**验收标准：**
- [ ] `RoleController`（`@Tag(name = "角色管理")`）：
  - [ ] `GET /api/admin/roles` — 角色列表（`@SaCheckPermission("system:role:manage")`）
  - [ ] `GET /api/admin/roles/{id}` — 角色详情 + 已分配权限
  - [ ] `POST /api/admin/roles` — 创建角色（`@Valid`）
  - [ ] `PUT /api/admin/roles/{id}` — 更新角色
  - [ ] `DELETE /api/admin/roles/{id}` — 删除角色
  - [ ] `PUT /api/admin/roles/{id}/permissions` — 为角色分配权限
  - [ ] `GET /api/admin/permissions/tree` — 权限树
- [ ] `AdminController`（`@Tag(name = "管理员")`）：
  - [ ] `GET /api/admin/users` — 用户列表分页（`@SaCheckPermission("system:user:manage")`）
  - [ ] `GET /api/admin/users/{id}` — 用户详情（含角色+权限）
  - [ ] `PUT /api/admin/users/{id}/roles` — 分配用户角色
  - [ ] `GET /api/admin/sla/config` — 查看全部 SLA 配置（`@SaCheckPermission("sla:config:manage")`）
  - [ ] `PUT /api/admin/sla/config` — 更新某条 SLA 配置（根据 type+priority 定位）
- [ ] 所有接口在 Knife4j 中按分组正确显示
- [ ] Knife4j 测试：admin 登录 → 创建角色 "测试处理人" → 分配 `order:accept`/`order:start`/`order:complete` 权限 → 为 handler01 分配此角色 → 验证 handler01 获得对应权限

---

### - [ ] Issue #26: WorkOrderService RBAC 数据过滤 + 工单统计 Service

**具体目标：**
修改 `WorkOrderService.listOrders()`（Day 4 Issue #18 中已创建），根据当前登录用户的角色动态过滤工单数据范围。新增 `getStats()` 方法，按部门/全局维度统计工单状态分布。

**涉及文件：**
- `src/main/java/com/workorder/service/WorkOrderService.java`（新增 getStats 方法签名，修改 listOrders 签名增加 currentUserId）
- `src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java`（修改 listOrders 实现 + 新增 getStats 实现）
- `src/main/java/com/workorder/common/vo/StatsVO.java`

**验收标准：**
- [ ] `listOrders` 角色过滤逻辑（通过 `StpUtil.getRoleList()` 获取当前角色）：
  - [ ] SUBMITTER：`WHERE submitter_id = #{currentUserId}`
  - [ ] HANDLER：`WHERE (assignee_id = #{currentUserId}) OR (status = 'PENDING' AND assignee_id IS NULL)`
  - [ ] DEPT_ADMIN：`WHERE t_work_order.submitter_id IN (SELECT id FROM t_user WHERE dept_id = #{currentUserDeptId})`
  - [ ] SYS_ADMIN：无额外过滤
  - [ ] 多角色取最宽松规则
- [ ] `getStats(String scope)` 逻辑：
  - [ ] scope=DEPT：按 `operatorId` 所属部门统计（`SELECT status, COUNT(*) FROM t_work_order WHERE submitter_id IN (dept_users) GROUP BY status`）
  - [ ] scope=ALL：全局统计（需 `order:stats:all` 权限），按 status 分组计数
- [ ] 单元测试：
  - [ ] submitter01 的 listOrders 只返回自己提交的工单
  - [ ] handler01 的 listOrders 返回自己接的单 + PENDING 池
  - [ ] admin 的 listOrders 返回全部

---

### - [ ] Issue #27: RBAC + 统计 Controller 集成 + 端到端 RBAC 验证

**具体目标：**
在 `WorkOrderController` 中新增统计接口，并在 Knife4j 中完成完整 RBAC 闭环验证：创建角色 → 分配权限 → 为用户分配角色 → 权限拦截生效 → 数据过滤正确。

**涉及文件：**
- `src/main/java/com/workorder/controller/WorkOrderController.java`（新增 GET /api/admin/orders/stats）
- 无其他新建文件

**验收标准：**
- [ ] `GET /api/admin/orders/stats?scope=DEPT`（`@SaCheckPermission("order:stats")`）→ 返回部门级统计
- [ ] `GET /api/admin/orders/stats?scope=ALL`（`@SaCheckPermission("order:stats:all")`）→ 返回全局统计
- [ ] 端到端 RBAC 验证（在 Knife4j 中顺序执行）：
  1. admin 创建自定义角色 "custom_handler"（仅分配 order:accept 权限）
  2. admin 为 handler01 分配 custom_handler 角色
  3. handler01 登录 → 调用 `GET /api/orders` → 能看到 PENDING 池（数据过滤生效）
  4. handler01 调用 `GET /api/orders/{id}/logs` → 成功（仅需登录）
  5. handler01 调用 `GET /api/admin/users` → **403 Forbidden**（无 system:user:manage 权限）
  6. handler01 调用 `GET /api/admin/orders/stats?scope=ALL` → **403 Forbidden**（无 order:stats:all 权限，只有 order:accept）
  7. submitter01 调用 `GET /api/admin/orders/stats?scope=ALL` → **403 Forbidden**
- [ ] 确认 `@SaCheckPermission` 注解在所有新增管理接口上生效

---

## 第 7 天：工单状态流转 — 服务层（状态机已在 Issue #16 完成）

### - [x] Issue #28: MessagePublishService 接口隔离 + @Scheduled 定时释放 ⚡架构调整

**架构调整说明：** 废弃真实 RabbitMQ 集成（当前物理机无 MQ 环境），改为接口驱动 + 本地兜底策略。

**具体目标：**
定义 `MessagePublishService` 接口（sendReleaseCheck / sendSlaEscalation），提供 `MockMessagePublishServiceImpl`（控制台日志打印）。WorkOrderService 的抢单/驳回流程依赖此接口，实现完全解耦。直接使用 Spring `@Scheduled` 每分钟扫描 `t_work_order` 表中 ACCEPTED 超 30 分钟的工单并释放。

**涉及文件：**
- `src/main/java/com/workorder/service/MessagePublishService.java` [新增]
- `src/main/java/com/workorder/service/impl/MockMessagePublishServiceImpl.java` [新增]
- `src/main/java/com/workorder/scheduler/ReleaseTimeoutScheduler.java` [新增]
- `src/main/java/com/workorder/mapper/WorkOrderMapper.java` [新增 releaseOrder]
- `src/main/java/com/workorder/service/WorkOrderService.java` [新增 releaseOrder]
- `src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java` [新增 releaseOrder 实现]
- `src/main/java/com/workorder/WorkOrderApplication.java` [添加 @EnableScheduling]

**验收标准：**
- [x] `MessagePublishService` 接口定义 `sendReleaseCheck(Long orderId)` 和 `sendSlaEscalation(Long orderId)`
- [x] `MockMessagePublishServiceImpl` 使用 `@Slf4j` 日志输出 mock 消息
- [x] `ReleaseTimeoutScheduler` @Scheduled(fixedRate=60000) 扫表释放超时工单
- [x] 释放条件：status=ACCEPTED AND updated_at <= now-30min
- [x] 释放操作：清除 assignee_id + status→RELEASED + 写操作日志
- [x] acceptOrder/assignOrder 在事务提交后通过 MessagePublishService 发送消息

---

### - [x] Issue #29: 抢单 Accept Service — 原子 SQL + CountDownLatch 并发验证

**具体目标：**
`WorkOrderMapper.grabOrder` 原子 SQL 单条 UPDATE 完成抢单。`acceptOrder` 完整业务逻辑：stateMachineValidator.validate → grabOrder → 操作日志 → afterCommit(Redis超时标记 + MQ消息)。多线程 CountDownLatch 测试验证乐观锁 100% 防超卖。

**涉及文件：**
- `src/main/java/com/workorder/mapper/WorkOrderMapper.java` [新增 grabOrder @Update 注解SQL]
- `src/main/java/com/workorder/service/WorkOrderService.java` [新增 acceptOrder]
- `src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java` [新增 acceptOrder 实现]
- `src/test/java/com/workorder/service/WorkOrderFlowServiceTest.java` [新增 17 个测试]

**验收标准：**
- [x] `grabOrder` SQL: `UPDATE t_work_order SET assignee_id=?, status='ACCEPTED', version=version+1 WHERE id=? AND assignee_id IS NULL AND status='PENDING'`
- [x] 受影响行数=1抢成功，=0抛 `BizException("工单已被抢走")`
- [x] `stateMachineValidator.validate(PENDING, ACCEPT)` 显式调用
- [x] `@Transactional` 覆盖 grabOrder + INSERT 日志
- [x] `TransactionSynchronization.afterCommit()` 中写 Redis 超时标记 + 调 MQ 接口
- [x] **CountDownLatch 10 线程并发测试**：10 个线程同时抢同一工单 → 仅 1 个成功，9 个失败
- [x] 已 ACCEPTED 工单再次 accept → BizException
- [x] 抢单后日志正确记录

---

### - [x] Issue #30: Start + Complete Service — 仅处理人 + 乐观锁

**具体目标：**
`updateStatus` 带乐观锁的通用状态更新 SQL。`startOrder` 校验处理人身份 + 状态转移 + Redis 超时标记清除。`completeOrder` 校验处理人身份 + 状态转移。

**涉及文件：**
- `src/main/java/com/workorder/mapper/WorkOrderMapper.java` [新增 updateStatus @Update 注解SQL]
- `src/main/java/com/workorder/service/WorkOrderService.java` [新增 startOrder, completeOrder]
- `src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java` [新增实现]

**验收标准：**
- [x] `updateStatus` SQL: `UPDATE t_work_order SET status=?, version=version+1 WHERE id=? AND status=? AND version=?`
- [x] `startOrder`: stateMachineValidator.validate(ACCEPTED, START) → 校验操作人==assigneeId → updateStatus → 日志 → 删Redis超时Key
- [x] `completeOrder`: stateMachineValidator.validate(IN_PROGRESS, COMPLETE) → 校验操作人==assigneeId → updateStatus → 日志
- [x] 单元测试：非处理人 start/complete → BizException
- [x] 单元测试：正常流程 ACCEPTED → start → IN_PROGRESS → complete → AWAIT_APPROVAL

---

### - [x] Issue #31: Approve + Reject Service — 提交人权限 + 驳回计数 + 升级

**具体目标：**
实现 `approveOrder`（AWAIT_APPROVAL→CLOSED，仅提交人）和 `rejectOrder`（驳回核心逻辑：rejectCount+1 >= maxReject 时升级 ESCALATED_ADMIN，否则回退 IN_PROGRESS）。Reject SQL 同时校验 version 和 rejectCount 防并发。

**涉及文件：**
- `src/main/java/com/workorder/mapper/WorkOrderMapper.java` [新增 updateStatusAndIncrementReject @Update 注解SQL]
- `src/main/java/com/workorder/service/WorkOrderService.java` [新增 approveOrder, rejectOrder]
- `src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java` [新增实现]

**验收标准：**
- [x] `approveOrder`: stateMachineValidator.validate(AWAIT_APPROVAL, APPROVE) → 校验提交人 → updateStatus → 日志
- [x] `rejectOrder`: stateMachineValidator.validate(AWAIT_APPROVAL, REJECT) → 校验提交人 → 分支判断
- [x] `updateStatusAndIncrementReject` SQL: `UPDATE ... SET status=?, reject_count=reject_count+1, version=version+1 WHERE ... AND version=? AND reject_count=?`
- [x] 分支逻辑：`rejectCount + 1 >= maxReject` → ESCALATED_ADMIN（发 MQ 通知管理员）；否则 → IN_PROGRESS
- [x] 第1次驳回: AWAIT_APPROVAL → IN_PROGRESS, rejectCount=1
- [x] 第2次驳回: AWAIT_APPROVAL → IN_PROGRESS, rejectCount=2
- [x] 第3次驳回(maxReject=3): AWAIT_APPROVAL → ESCALATED_ADMIN, rejectCount=3
- [x] 第4次驳回(ESCALATED_ADMIN状态): 状态机抛异常
- [x] 驳回日志包含 remark 字段

---

## 第 8 天：防重 + Controller + 端到端交付

### - [x] Issue #32: 驳回幂等 — Redis Token + Lua 脚本防重复提交

**具体目标：**
实现驳回操作的一次性 Token 机制：前端先申请 Token → 驳回时携带 Token，Redis Lua 脚本原子校验 + 删除。Token 30 秒过期。技术方案 3.7 节。

**涉及文件：**
- `src/main/java/com/workorder/service/WorkOrderService.java`（新增 generateRejectToken 方法签名）
- `src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java`（新增实现）
- `src/main/java/com/workorder/common/dto/RejectReq.java`（新增 DTO，含 token 字段）

**验收标准：**
- [ ] `generateRejectToken(Long orderId)`：
  - [ ] 生成 UUID，Redis key=`token:reject:{uuid}`, value=`orderId`, TTL=30 秒
  - [ ] 返回 token 字符串
- [ ] Redis Lua 脚本（在 reject 流程最前面执行）：
  ```lua
  if redis.call('get', KEYS[1]) == ARGV[1]
  then return redis.call('del', KEYS[1])
  else return 0
  end
  ```
  - [ ] 返回 0 → `BizException("请勿重复提交或Token已过期")`
  - [ ] 返回 1 → 继续执行 `rejectOrder` 业务逻辑
- [ ] 单元测试（需 Embedded Redis）：
  - [ ] 生成 Token → 携带正确 Token 调用完整 reject 流程 → 成功
  - [ ] 同一 Token 第二次调用 → "请勿重复提交"
  - [ ] 伪造 Token → "请勿重复提交或Token已过期"
  - [ ] 30 秒后 Token 过期 → 调用失败
- [ ] **面试记忆点**：Token 只防 30 秒内重复点击，真正兜底在 Issue #31 的 SQL 层状态校验

---

### - [x] Issue #33: Assign 管理员分配 Service — 原子 SQL + 权限控制

**具体目标：**
实现 `assignOrder`：管理员将 PENDING 工单直接指派给指定处理人。原子 SQL + 状态机校验 + `order:assign` 权限控制。与抢单共用同一行锁竞争——assign 和 accept 只有一个能成功。

**涉及文件：**
- `src/main/java/com/workorder/mapper/WorkOrderMapper.java`（新增 assignOrder 方法）
- `src/main/resources/mapper/WorkOrderMapper.xml`（assignOrder SQL）
- `src/main/java/com/workorder/service/WorkOrderService.java`（新增 assignOrder 方法签名）
- `src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java`（新增实现）
- `src/main/java/com/workorder/common/dto/AssignReq.java`

**验收标准：**
- [ ] `WorkOrderMapper.assignOrder(Long orderId, Long assigneeId)` SQL：
  ```sql
  UPDATE t_work_order SET assignee_id = #{assigneeId}, status = 'ACCEPTED', version = version + 1
  WHERE id = #{orderId} AND assignee_id IS NULL AND status = 'PENDING'
  ```
- [ ] `assignOrder(Long orderId, Long assigneeId, Long operatorId)`：
  - [ ] `stateMachineValidator.validate(PENDING, ASSIGN)`
  - [ ] 校验 `assigneeId` 对应账号存在且未被禁用（status=1）
  - [ ] `assignOrder` → affected rows=0 → `BizException("工单已被抢走或状态异常")`
  - [ ] INSERT 操作日志（action=ASSIGN, remark 含操作人和被指派人信息）
  - [ ] 事务提交后：Redis 超时标记 + MQ 延迟消息（与 Issue #29 抢单一致）
- [ ] 单元测试：
  - [ ] admin 分配工单给 handler01 → 成功，status=ACCEPTED, assignee=handler01
  - [ ] 同时 admin assign 和 handler02 accept 同一工单 → 行锁竞争，只有一个成功
  - [ ] 对已 ACCEPTED 的工单 assign → 状态机抛异常

---

### - [x] Issue #34: WorkOrderController 状态流转端点 + action-token 接口暴露

**具体目标：**
在 `WorkOrderController`（Day 4 Issue #20 已创建）中新增全部工单状态流转 REST 接口：accept、start、complete、approve、reject、assign、action-token。每个接口标注 `@SaCheckPermission` 和 Knife4j `@Operation` 注解。`StpUtil.getLoginIdAsLong()` 获取当前操作人。

**涉及文件：**
- `src/main/java/com/workorder/controller/WorkOrderController.java`

**验收标准：**
- [ ] `GET /api/orders/{id}/action-token`（`@SaCheckPermission("order:reject")`）→ 返回一次性 Token
- [ ] `POST /api/orders/{id}/accept`（`@SaCheckPermission("order:accept")`）→ 抢单
- [ ] `POST /api/orders/{id}/start`（`@SaCheckLogin`，Service 层校验处理人身份）→ 开始处理
- [ ] `POST /api/orders/{id}/complete`（`@SaCheckLogin`，Service 层校验处理人身份）→ 提交验收
- [ ] `POST /api/orders/{id}/approve`（`@SaCheckLogin`，Service 层校验提交人身份）→ 验收通过
- [ ] `POST /api/orders/{id}/reject`（`@SaCheckPermission("order:reject")`）→ 验收驳回（携带 Token），请求体 `@Valid RejectReq`
- [ ] `POST /api/orders/{id}/assign`（`@SaCheckPermission("order:assign")`）→ 管理员分配，请求体 `@Valid AssignReq`
- [ ] 所有接口 `@Operation(summary = "...")` 在 Knife4j 中正确显示
- [ ] Knife4j 快速测试：admin 登录 → 提交工单 → accept → start → complete → approve（黄金路径 200 OK）

---

### - [x] Issue #35: 抢单+驳回端到端流程验证 + Knife4j 文档整理 + `mvn test`

**具体目标：**
端到端验证 Day 5~8 所有产出的完整性和正确性。覆盖 RBAC 权限 → 工单提交 → 抢单并发 → 驳回升级 → 管理员介入 → 验收闭环的 13 步完整流程。确保 `mvn test` 全部通过，Knife4j 文档完整。

**涉及文件：**
- 无新建文件（验证已有产出 + 必要时补充 Knife4j 注解）

**验收标准：**
- [ ] 所有新增 Controller 方法有 `@Operation(summary = "...")` 注解
- [ ] 所有新增 DTO 字段有 `@Schema(description = "...")` 注解（关键字段）
- [ ] Knife4j 文档新增分组：角色管理、管理员
- [ ] **端到端 13 步完整闭环**（Knife4j 顺序执行）：
  1. admin 创建自定义角色 + 分配权限 + 为 handler01 分配角色
  2. submitter01 提交工单 → PENDING
  3. **RBAC 数据过滤**：submitter01 列表只看到自己；handler01 列表看到 PENDING 池
  4. handler01 accept → ACCEPTED（`grabOrder` 返回 1）
  5. **并发抢单**：handler02 对同一工单 accept → "工单已被抢走"
  6. handler01 start → IN_PROGRESS（Redis 超时标记被清除）
  7. handler01 complete → AWAIT_APPROVAL
  8. submitter01 GET action-token → 拿到 Token
  9. submitter01 reject（带 Token, remark="图片不清晰"）→ IN_PROGRESS, rejectCount=1
  10. **Token 防重**：相同 Token 再次 reject → "请勿重复提交或Token已过期"
  11. handler01 complete → submitter01 获取新 Token 后 reject → rejectCount=2
  12. handler01 complete → submitter01 获取新 Token 后 reject → **ESCALATED_ADMIN**, rejectCount=3
  13. admin 查看升级工单 + 操作日志完整性验证（SUBMIT→ACCEPT→START→COMPLETE→REJECT×3）
- [ ] **黄金路径**（另一工单）：submit → accept → start → complete → approve → CLOSED（全流程无阻塞）
- [ ] `mvn test` 全部测试通过
- [ ] Docker 重启后（`docker compose down && docker compose up -d`）系统正常运行，数据不丢

---

## 里程碑 3（Day 9-14, Issue #36-#47）：站内信策略模式 + SLA 升级 + LLM 分拣 + 500万造数 + 交付

---

## 第 9 天：SLA 超时升级 + 通知策略模式

### - [x] Issue #36: SLA 超时升级定时扫描器 — `@Scheduled` + `idx_sla` 索引扫表

**具体目标：**
新增 `SlaEscalationScheduler`，用 `@Scheduled` 每 5 分钟扫一次 `t_work_order` 表，找出 `sla_deadline < NOW()` 且状态处于未完结的工单，触发通知。**只依赖 Mock MQ 环境**，不引入真实 RabbitMQ。

**涉及文件：**
- `src/main/java/com/workorder/scheduler/SlaEscalationScheduler.java` [新增]
- `src/main/java/com/workorder/mapper/WorkOrderMapper.java` [新增 findSlaExpired 方法]
- `src/main/resources/mapper/WorkOrderMapper.xml` [新增 findSlaExpired SQL]

**验收标准：**
- [ ] `SlaEscalationScheduler.scanSlaExpired()` 标注 `@Scheduled(fixedRate = 300_000)`（每 5 分钟）
- [ ] `findSlaExpired` SQL：`SELECT id, order_no, type, priority, status, sla_deadline FROM t_work_order WHERE status IN ('PENDING','ACCEPTED','IN_PROGRESS') AND sla_deadline < NOW() LIMIT #{batchSize}`，走 `idx_sla` 联合索引
- [ ] 扫描结果分页处理，每批 200 条，避免一次性加载过多数据
- [ ] 每条超时工单调用 `MessagePublishService.sendSlaEscalation(orderId)`（在 Mock 模式下打日志即可，真实通知由 Issue #37 的策略模式实现）
- [ ] 日志记录扫描到的超时工单数量（`log.info("SLA扫描: 发现{}条超时工单", count)`）
- [ ] 单元测试：插入 5 条不同 sla_deadline 的工单（3条已过期+2条未过期），调用 `findSlaExpired` 验证只返回过期且状态未完结的 3 条

---

### - [x] Issue #37: NotifyChannel 策略模式 + InAppNotifyChannel 站内信实现

**具体目标：**
创建 `NotifyChannel` 接口（策略模式扩展点），实现 `InAppNotifyChannel`（写入 `t_notification` 表）。未来接邮件/企业微信只需新增实现类，调用方零改动。技术方案 3.4 节。

**涉及文件：**
- `src/main/java/com/workorder/service/NotifyChannel.java` [新增接口]
- `src/main/java/com/workorder/service/impl/InAppNotifyChannel.java` [新增实现]
- `src/main/java/com/workorder/service/NotificationService.java` [新增]
- `src/main/java/com/workorder/service/impl/NotificationServiceImpl.java` [新增]
- `src/main/java/com/workorder/mapper/NotificationMapper.java` [新增]
- `src/main/java/com/workorder/entity/Notification.java` [新增]

**验收标准：**
- [ ] `NotifyChannel` 接口定义：`void send(Long userId, String title, String content)` 和 `default String channelName() { return this.getClass().getSimpleName(); }`
- [ ] `InAppNotifyChannel` 实现 `NotifyChannel`：调用 `NotificationMapper.insert(Notification)` 写入站内信记录
- [ ] `Notification` 实体映射 `t_notification` 表（使用 `@TableName`、`@TableId`、Lombok `@Data`）
- [ ] `NotificationMapper` 继承 `BaseMapper<Notification>`，自定义方法 `selectByUserId(Long userId, Integer page, Integer size)` 分页查询（按 `created_at DESC`）
- [ ] `NotificationService`：
  - [ ] `send(Long userId, String title, String content)`：委托 `InAppNotifyChannel.send()`
  - [ ] `sendToRole(String roleCode, String title, String content)`：查询该角色全部用户，逐条发送站内信
  - [ ] `listByUser(Long userId, Integer page, Integer size)`：分页查询当前用户的站内信
  - [ ] `markAsRead(Long notificationId, Long userId)`：校验归属 → `UPDATE SET is_read=1`
  - [ ] `getUnreadCount(Long userId)`：`SELECT COUNT(*) FROM t_notification WHERE user_id=? AND is_read=0`
- [ ] **策略模式验证**：`NotificationService` 持有 `NotifyChannel` 引用（构造注入），调用方只依赖接口不依赖具体实现
- [ ] 单元测试：构造 `NotificationService` 注入 `InAppNotifyChannel` → `send()` → 数据库 `t_notification` 中可见新记录

---

### - [x] Issue #38: 站内信 REST API — 列表/未读数/标记已读

**具体目标：**
创建 `NotificationController`，暴露站内信查询、未读计数、标记已读三个接口。所有接口需要登录态，且只能操作本人的站内信。

**涉及文件：**
- `src/main/java/com/workorder/controller/NotificationController.java` [新增]
- `src/main/java/com/workorder/common/vo/NotificationVO.java` [新增]
- `src/main/java/com/workorder/service/NotificationService.java` [如 Issue #37 已创建则追加方法]

**验收标准：**
- [ ] `GET /api/notifications?page=1&size=20` — 当前用户站内信分页列表
  - [ ] 返回 `NotificationVO` 含：id、title、content、refType、refId、isRead、createdAt
  - [ ] 按 `created_at DESC` 排序
  - [ ] 用 `StpUtil.getLoginIdAsLong()` 限定只能看自己的
- [ ] `GET /api/notifications/unread-count` — 当前用户未读数量，返回 `{"count": 5}`
- [ ] `PUT /api/notifications/{id}/read` — 标记已读
  - [ ] 校验该通知属于当前用户，否则抛 `BizException(NOT_FOUND)`
  - [ ] 重复标记已读不报错（幂等）
- [ ] 所有接口在 Knife4j 中按 `@Tag(name = "站内信")` 分组显示
- [ ] Knife4j 冒烟测试：admin 登录 → 查列表 → 看未读数 → 标记已读 → 未读数减 1

---

## 第 10 天：SLA 升级通知集成 + 站内信端到端闭环

### - [x] Issue #39: SLA 升级 → 站内信通知完整链路集成

**具体目标：**
将 Issue #36（SLA 扫描器）、Issue #37（策略模式通知）、Issue #38（站内信 API）串联为完整闭环：SLA 超时工单被扫描到 → 通知管理员 → 管理员在站内信列表中可见。同时将 #31 中已完成的三次驳回升级逻辑也接入 `NotificationService`，替换当前的 Mock 日志打点。

**涉及文件：**
- `src/main/java/com/workorder/scheduler/SlaEscalationScheduler.java` [修改——接入 NotificationService]
- `src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java` [修改——rejectOrder 中接入真实通知]

**验收标准：**
- [ ] `SlaEscalationScheduler` 扫描到超时工单后，调用 `notificationService.sendToRole("SYS_ADMIN", title, content)` 而非仅打 Mock 日志
  - [ ] title：`"工单 {orderNo} SLA 超时"`
  - [ ] content：`"类型:{type}, 优先级:{priority}, 当前状态:{status}, 超时时间:{slaDeadline}"`
- [ ] `WorkOrderServiceImpl.rejectOrder()` 中，当驳回次数达上限进入 `ESCALATED_ADMIN` 时，调用 `notificationService.sendToRole("SYS_ADMIN", ...)` 而非仅调 `messagePublishService.sendSlaEscalation()`
  - [ ] title：`"工单 {orderNo} 驳回次数已达上限"`
  - [ ] content：`"类型:{type}, 优先级:{priority}, 请介入处理"`
- [ ] `messagePublishService.sendSlaEscalation()` 调用保留（Mock 模式下继续打日志），**但真实通知链路独立走 NotificationService**
- [ ] 端到端验证：
  - [ ] 场景 A：插入一条 `sla_deadline` 已过期 1 小时的 ACCEPTED 工单 → 手动触发 `SlaEscalationScheduler.scanSlaExpired()` → admin 的站内信列表中可见 SLA 超时通知
  - [ ] 场景 B：提交 → accept → start → complete → reject×3 → `ESCALATED_ADMIN` → admin 站内信可见驳回升级通知
- [ ] `sendToRole` 中同一角色多个用户每人收到独立一条站内信

---

### - [x] Issue #40: 操作日志 AOP 非侵入式切面 + 日志完整性验证

**具体目标：**
创建 `@OrderAction` 注解和 `OrderLogAspect` 切面，用 AOP 替代 Service 方法中手动 `INSERT` 日志的重复代码。切面在方法执行前后各查一次工单状态，仅当状态发生变更时才写入操作日志。技术方案 3.5 节。

**涉及文件：**
- `src/main/java/com/workorder/common/aop/OrderAction.java` [新增注解]
- `src/main/java/com/workorder/common/aop/OrderLogAspect.java` [新增切面]
- `src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java` [修改——移除手动 saveLog 调用，添加 @OrderAction 注解]

**验收标准：**
- [ ] `@OrderAction` 注解：
  - [ ] `@Retention(RetentionPolicy.RUNTIME)` + `@Target(ElementType.METHOD)`
  - [ ] 属性 `String action()` 表示操作类型（如 "ACCEPT"、"REJECT"）
- [ ] `OrderLogAspect`：
  - [ ] `@Aspect` + `@Component`
  - [ ] `@Around("@annotation(orderAction)")` 环绕通知
  - [ ] 从 `joinPoint.getArgs()[0]` 提取 `orderId`（Long 类型）
  - [ ] 方法执行前：`workOrderMapper.selectById(orderId)` 获取 `oldStatus`
  - [ ] 方法执行后：`workOrderMapper.selectById(orderId)` 获取 `newStatus`
  - [ ] `if (!oldStatus.equals(newStatus))` → 写日志（`INSERT INTO t_work_order_log`）
  - [ ] 用 `StpUtil.getLoginIdAsLong()` 获取当前操作人
  - [ ] 日志包含：orderId、orderNo、operatorId、action（来自注解）、oldStatus、newStatus
- [ ] 重构 `WorkOrderServiceImpl`：
  - [ ] `acceptOrder` 添加 `@OrderAction(action = "ACCEPT")`，移除内部手动 saveLog 调用
  - [ ] `startOrder` 添加 `@OrderAction(action = "START")`，移除手动 saveLog
  - [ ] `completeOrder` 添加 `@OrderAction(action = "COMPLETE")`，移除手动 saveLog
  - [ ] `approveOrder` 添加 `@OrderAction(action = "APPROVE")`，移除手动 saveLog
  - [ ] `rejectOrder` 添加 `@OrderAction(action = "REJECT")`，移除手动 saveLog
  - [ ] `assignOrder` 添加 `@OrderAction(action = "ASSIGN")`，移除手动 saveLog
  - [ ] `releaseOrder` 添加 `@OrderAction(action = "RELEASE")`，移除手动 saveLog
  - [ ] `submitOrder` 保留手动日志（因为提交时工单 ID 尚未生成，切面无法在 before 阶段查到工单）
- [ ] **回归测试**：`mvn test` 全部通过，现有日志相关断言不受影响（日志写入方式变了但结果一致）
- [ ] 端到端验证：完整走一遍黄金路径（submit → accept → start → complete → approve）→ `GET /api/orders/{id}/logs` 返回 5 条日志，每条 action 字段正确

---

## 第 11 天：操作日志 Controller 收尾 + 里程碑 2 完整回归

### - [x] Issue #41: 操作日志查询 Controller + 站内信/日志 Knife4j 文档补全

**具体目标：**
修复 `WorkOrderController` 中日志查询接口（确认 Issue #20 已暴露 `GET /api/orders/{id}/logs`），为所有站内信和日志相关 API 补全 Knife4j `@Operation` 注解和 `@Schema` 字段描述。执行里程碑 2 完整回归脚本。

**涉及文件：**
- `src/main/java/com/workorder/controller/WorkOrderController.java` [确认/补全]
- `src/main/java/com/workorder/controller/NotificationController.java` [补全 Knife4j 注解]
- `src/main/java/com/workorder/common/vo/WorkOrderLogVO.java` [确认含 operatorName 字段]

**验收标准：**
- [ ] `GET /api/orders/{id}/logs` 返回 `List<WorkOrderLogVO>`，每个 VO 包含：id、action、oldStatus、newStatus、remark、operatorName（JOIN t_user 查 username）、createdAt，按时间正序
- [ ] `NotificationController` 所有接口有 `@Operation(summary = "...")` 注解
- [ ] 所有 VO/DTO 字段有 `@Schema(description = "...")`（关键字段）
- [ ] Knife4j 文档新增分组 "站内信"，含 3 个接口（列表/未读数/标记已读）
- [ ] **里程碑 2 完整回归**（Knife4j 顺序执行 15 步）：
  1. admin 注册 handler01 + 分配 HANDLER 角色
  2. submitter01 提交工单 → PENDING
  3. handler01 抢单 → ACCEPTED（`grabOrder` 原子 SQL）
  4. handler01 start → IN_PROGRESS
  5. handler01 complete → AWAIT_APPROVAL
  6. submitter01 获取 action-token
  7. submitter01 reject（带 Token, remark="第1次驳回"）→ IN_PROGRESS, rejectCount=1
  8. handler01 complete → submitter01 reject（第2次）→ rejectCount=2
  9. handler01 complete → submitter01 reject（第3次）→ **ESCALATED_ADMIN**
  10. admin 查看站内信 → 可见驳回升级通知（#39 链路）
  11. admin 查看工单操作日志 → 完整时间线（SUBMIT→ACCEPT→START→COMPLETE→REJECT×3，共计 8 条）
  12. 另起工单走黄金路径：submit → accept → start → complete → approve → CLOSED
  13. admin 查看全局统计 → `GET /api/admin/orders/stats?scope=ALL` → 各状态计数正确
  14. admin 查 SLA 配置 → `GET /api/admin/sla/config` → 返回配置列表
  15. `mvn test` 全部通过
- [ ] Regression：Docker 重启后所有数据不丢，系统正常运行

---

## 第 12 天：LLM 智能体分拣接入

### - [x] Issue #42: OrderTriageService — LLM 智能体类型/优先级自动识别 + 失败回退

**具体目标：**
创建 `OrderTriageService`，在工单提交时调用 LLM 接口自动判断工单类型和优先级。LLM 仅返回**建议值**（提交人可手动修改），LLM 调用失败时回退到默认值（type=OTHER, priority=0），不阻断核心提交流程。技术方案第 6 节。

**涉及文件：**
- `src/main/java/com/workorder/service/OrderTriageService.java` [新增接口]
- `src/main/java/com/workorder/service/impl/OrderTriageServiceImpl.java` [新增实现]
- `src/main/java/com/workorder/common/dto/TriageResult.java` [新增 DTO]

**验收标准：**
- [ ] `TriageResult` DTO 包含：`String suggestedType`、`Integer suggestedPriority`
- [ ] `OrderTriageService` 接口定义：`TriageResult triage(String title, String content)`
- [ ] `OrderTriageServiceImpl` 实现：
  - [ ] 构造 Prompt：`"根据以下工单内容，判断工单类型和优先级。类型可选: REPAIR(报修), LEAVE(请假), REIMBURSE(报销), OTHER(其他)。优先级: 0(普通), 1(紧急)。返回JSON: {\"类型\":\"REPAIR\",\"优先级\":1}\n工单标题: {title}\n工单内容: {content}"`
  - [ ] 调用 LLM Client（封装 HTTP 请求到 LLM API endpoint），配置项通过 `application.yml` 读取（`llm.api.url`、`llm.api.key`，均可为空）
  - [ ] 解析 LLM 返回的 JSON，提取 type 和 priority
  - [ ] **失败回退逻辑**（`try-catch` 包裹整个 LLM 调用）：
    - [ ] 网络超时 → `log.warn("LLM调用超时，使用默认值")`，返回 `TriageResult("OTHER", 0)`
    - [ ] 响应解析失败 → `log.warn("LLM响应格式异常，使用默认值")`，返回 `TriageResult("OTHER", 0)`
    - [ ] `llm.api.url` 未配置（空字符串/null） → 直接返回默认值，不打日志（静默降级）
    - [ ] 任何其他异常 → `log.error("LLM triage异常", e)`，返回默认值，**不影响工单提交**
  - [ ] 校验 LLM 返回的 type 在合法枚举值内（REPAIR/LEAVE/REIMBURSE/OTHER），否则视为解析失败走回退
  - [ ] 校验 LLM 返回的 priority 为 0 或 1，否则走回退
- [ ] 单元测试（Mock LLM Client）：
  - [ ] Mock 正常返回 → triage 返回正确的 type 和 priority
  - [ ] Mock 超时异常 → triage 返回默认值，不抛异常
  - [ ] Mock 返回非法 JSON → triage 返回默认值
  - [ ] Mock 返回非法 type 值 → triage 返回默认值
- [ ] **面试记忆点**：LLM 是建议值不是决策值，挂了工单照常提交

---

### - [x] Issue #43: LLM Triage 集成到工单提交流程 + 端到端降级验证

**具体目标：**
将 `OrderTriageService.triage()` 嵌入 `WorkOrderServiceImpl.submitOrder()` 流程：在 Service 层提交逻辑中调用 triage → 使用建议值填充 type/priority（若请求方未显式传入）。验证 LLM 不可用时的优雅降级行为。

**涉及文件：**
- `src/main/java/com/workorder/service/impl/WorkOrderServiceImpl.java` [修改 submitOrder]
- `src/main/java/com/workorder/controller/WorkOrderController.java` [修改 POST /api/orders 的可选参数逻辑]
- `src/main/resources/application.yml` [新增 llm 配置段]

**验收标准：**
- [ ] `SubmitOrderReq` DTO 中 type 和 priority 字段改为**可选**（不再标注 `@NotBlank`）：
  - [ ] 若请求体显式传入 type → 使用请求值（用户手动选择优先）
  - [ ] 若请求体未传 type → 调用 `orderTriageService.triage(title, content)` 获取建议值
  - [ ] priority 同上逻辑
- [ ] `application.yml` 新增 LLM 配置：
  ```yaml
  llm:
    api:
      url: ${LLM_API_URL:}      # 默认为空 → 静默降级
      key: ${LLM_API_KEY:}
      timeout: 5000              # 5 秒超时
  ```
- [ ] `submitOrder` 中 triage 调用包裹在 `try-catch` 内，任何异常不阻断提交
- [ ] **端到端降级验证**：
  - [ ] 场景 A（无 LLM 配置）：`llm.api.url` 为空 → 提交工单时 type 和 priority 使用默认值 → 工单正常创建
  - [ ] 场景 B（LLM 不可达）：配置错误的 LLM URL → 提交工单 → 日志有 warn → 工单正常创建（type=OTHER, priority=0）
  - [ ] 场景 C（用户手动指定）：请求体传入 `"type":"REPAIR","priority":1` → 即使 LLM 可用也使用用户指定值
- [ ] Knife4j 验证：未传 type 提交工单 → 返回的工单 type 为 LLM 建议值或默认值

---

## 第 13 天：500 万造数 + Explain 性能报告

### - [x] Issue #44: 500 万工单造数存储过程编写与执行

**具体目标：**
编写 MySQL 存储过程 `generate_work_orders`，批量插入 500 万条工单到 `t_work_order` 表。数据分布严格对齐技术方案 7.1 节：70% CLOSED / 10% PENDING / 10% ACCEPTED / 5% IN_PROGRESS / 3% AWAIT_APPROVAL / 2% RELEASED。

**涉及文件：**
- `sql/data-generator.sql` [新增——存储过程 + 造数脚本]
- `sql/init.sql` [可选——追加注释引用 data-generator.sql]

**验收标准：**
- [ ] 存储过程 `generate_work_orders(IN total INT)` 完整可用：
  - [ ] 批量提交：每 1000 条 COMMIT 一次（避免事务日志撑爆）
  - [ ] 字段覆盖：order_no、title、content、type、priority、status、submitter_id、assignee_id、reject_count、max_reject、sla_deadline、version、created_at、updated_at
  - [ ] `order_no` 按日期+序号生成（模拟真实格式 `WO-YYYYMMDD-XXXXX`）
  - [ ] `type` 使用 `ELT(1+FLOOR(RAND()*4), 'REPAIR','LEAVE','REIMBURSE','OTHER')` 随机
  - [ ] `priority` 使用 `FLOOR(RAND()*2)` 随机
  - [ ] `status` 按技术方案 7.1 分布比例分配
  - [ ] `submitter_id` 在 1-100 范围随机
  - [ ] `assignee_id`：PENDING/RELEASED 状态为 NULL，其余状态在 1-20 范围随机
  - [ ] `reject_count` 在 0-3 范围随机
  - [ ] `sla_deadline` 在 created_at 基础上 + 2~48 小时随机偏移
  - [ ] `created_at` 在 2025-01-01 ~ 2026-06-01 范围内随机分布
- [ ] 执行 `CALL generate_work_orders(5000000)`：
  - [ ] 执行时间在可接受范围内（预计 10-30 分钟，取决于硬件）
  - [ ] 执行完成后 `SELECT COUNT(*) FROM t_work_order` 返回 5,000,000
- [ ] 数据分布验证 SQL：
  ```sql
  SELECT status, COUNT(*) AS cnt,
         ROUND(COUNT(*) / 5000000 * 100, 2) AS pct
  FROM t_work_order GROUP BY status ORDER BY cnt DESC;
  ```
  - [ ] CLOSED 占比 ≈ 70%（±5% 容忍）
  - [ ] PENDING 占比 ≈ 10%
  - [ ] ACCEPTED 占比 ≈ 10%
- [ ] 同目录下创建 `sql/data-verify.sql` [新增]，包含数据分布验证 + 索引使用情况快速检查 SQL

---

### - [x] Issue #45: Explain 执行计划对比报告 + 截图存档

**具体目标：**
在 500 万数据基础上，对 5 个核心查询执行 `EXPLAIN` 分析，验证索引命中情况。将结果输出为 Markdown 格式报告，包含查询 SQL、Explain 输出、type/key/rows 关键字段解读、优化结论。技术方案 7.2 节。

**涉及文件：**
- `docs/explain-report.md` [新增——Explain 分析报告]
- `docs/screenshots/` [新增目录——Explain 截图存档]
- `sql/explain-queries.sql` [新增——Explain 语句集合]

**验收标准：**
- [ ] `sql/explain-queries.sql` 包含以下 5 个 Explain 语句：
  1. 处理人查询自己的待处理工单：`EXPLAIN SELECT * FROM t_work_order WHERE assignee_id = 5 AND status = 'ACCEPTED'`
  2. 提交人查询自己全部工单：`EXPLAIN SELECT * FROM t_work_order WHERE submitter_id = 10 ORDER BY created_at DESC LIMIT 20`
  3. SLA 超时扫描（模拟定时任务扫表）：`EXPLAIN SELECT id FROM t_work_order WHERE status IN ('PENDING','ACCEPTED','IN_PROGRESS') AND sla_deadline < NOW()`
  4. 待分配池查询（PENDING 工单列表）：`EXPLAIN SELECT * FROM t_work_order WHERE status = 'PENDING' AND assignee_id IS NULL ORDER BY created_at DESC LIMIT 20`
  5. 全局状态统计：`EXPLAIN SELECT status, COUNT(*) FROM t_work_order GROUP BY status`
- [ ] `docs/explain-report.md` 内容结构：
  - [ ] 数据规模说明：5,000,000 条工单，分布比例
  - [ ] 索引清单：`idx_order_no (UNIQUE)`、`idx_status`、`idx_submitter`、`idx_assignee`、`idx_sla (status, sla_deadline)`
  - [ ] 每个查询一节的对比分析：**查询场景 → SQL → Explain 输出 → 关键字段解读（type/key/rows/Extra）→ 是否命中索引 → 优化建议**
  - [ ] 查询 1（assignee_id + status）：预期 `type: ref, key: idx_assignee, rows: ~N`（而非 500 万全表扫描）
  - [ ] 查询 2（submitter_id）：预期 `type: ref, key: idx_submitter`
  - [ ] 查询 3（SLA 超时扫描）：预期 `type: range, key: idx_sla`（联合索引生效）
  - [ ] 查询 4（PENDING 池）：预期 `type: ref, key: idx_status`
  - [ ] 查询 5（GROUP BY status）：预期 `type: index, key: idx_status`（Using index for group-by 或走索引）
  - [ ] 末尾附 **"面试口径"** 一节：总结核心结论（3-5 句话，可直接用于回答面试官）
- [ ] `docs/screenshots/` 目录下存放 5 张 Explain 结果的终端截图或 DBeaver 截图（按查询编号命名：`01-assignee-status.png` ~ `05-group-by-status.png`）
- [ ] **可选加分项**：在执行 `generate_work_orders` 前后分别跑一次 Explain（数据量不同时对比），报告中展示索引在空表和 500 万数据下的执行计划差异

---

## 第 14 天：Knife4j 文档校验 + Docker 环境梳理 + 面试话术归档

### - [ ] Issue #46: Knife4j 接口文档完整性校验 + 全量 API 冒烟测试

**具体目标：**
逐一对 Knife4j 文档页面（`/doc.html`）中所有 REST 接口进行完整性校验：确保分组正确、`@Operation` 描述清晰、请求参数和响应示例完整。执行全量 API 冒烟测试（40+ 个接口），修复文档缺失或接口报错问题。

**涉及文件：**
- 各 Controller 文件（补全 `@Operation` / `@Schema` 注解）
- `src/main/resources/application.yml` [确认 knife4j 配置正确]

**验收标准：**
- [ ] Knife4j 文档页面（`http://localhost:9000/doc.html`）分组完整且无空分组：
  - [ ] "用户管理"：register、login（2 个接口）
  - [ ] "工单管理"：提交、列表、详情、日志、action-token、accept、start、complete、approve、reject、assign（11 个接口）
  - [ ] "站内信"：列表、未读数、标记已读（3 个接口）
  - [ ] "角色管理"：列表、详情、创建、更新、删除、分配权限（6 个接口）
  - [ ] "管理员"：用户列表、用户详情、分配角色、SLA 配置查看、SLA 配置更新、全局统计、部门统计（7 个接口）
  - [ ] 总计约 29 个接口，无遗漏
- [ ] 每个接口在 Knife4j 中有清晰的 `@Operation(summary = "...")` 描述
- [ ] 每个 DTO 的必填字段在 Knife4j 中有 `@Schema(description = "...", required = true)` 标注
- [ ] **全量冒烟测试脚本**（Knife4j 顺序执行，28+ 步）：
  - [ ] admin 登录 → 创建角色 → 分配权限 → 为用户分配角色
  - [ ] handler01 登录 → 抢单 → start → complete
  - [ ] submitter01 登录 → 提交工单 → approve / reject（含 Token 防重）
  - [ ] admin 查看统计 → 管理 SLA 配置 → 查看站内信
  - [ ] 所有接口返回 200（除权限拦截返回 403 的场景外）
- [ ] 记录并修复冒烟测试中发现的所有问题

---

### - [ ] Issue #47: Docker 环境梳理 + docker-compose 最终交付 + 面试 18 问话术归档

**具体目标：**
梳理 Docker 环境一键启动流程，确认 `docker compose up -d` 全链路可用。编写 `DELIVERY.md` 交付文档，归档面试 18 问回答要点（技术方案第 9 节）。

**涉及文件：**
- `docker-compose.yml` [确认/修复]
- `sql/init.sql` [确认全部 9 张表 DDL + 种子数据完整]
- `DELIVERY.md` [新增——交付文档 + 面试话术归档]

**验收标准：**
- [ ] `docker compose down -v && docker compose up -d` 全程无报错：
  - [ ] mysql:8.0 健康检查通过（`docker compose ps` 显示 healthy）
  - [ ] redis:7-alpine 启动正常
  - [ ] rabbitmq:3-management 启动正常（Mock 模式下不依赖，但容器需要 Running）
  - [ ] xxl-job-admin:2.4.0 启动正常
- [ ] MySQL 初始化：`init.sql` 包含全部 9 张表（t_work_order、t_work_order_log、t_user、t_role、t_permission、t_user_role、t_role_permission、t_sla_config、t_notification）+ 种子数据，执行无语法错误
- [ ] Spring Boot 应用启动日志无 ERROR（允许 MQ 连接失败的 WARN，但不要阻塞启动）
- [ ] `DELIVERY.md` 内容结构：
  - [ ] **一、项目概述**：企业工单流转平台，核心功能一句话概括
  - [ ] **二、快速启动**：`docker compose up -d` + `mvn spring-boot:run` + Knife4j 访问地址
  - [ ] **三、技术栈一览**：Spring Boot 3 / MyBatis-Plus / MySQL 8.0 / Redis / Sa-Token / Knife4j / RabbitMQ（Mock）/ XXL-Job
  - [ ] **四、核心亮点（面试展开点）**：
    - [ ] 抢单原子 SQL + 乐观锁（#29）
    - [ ] 状态机 + 状态转移校验（#16, #29-31）
    - [ ] 驳回 Redis Token + Lua 原子防重（#32）
    - [ ] 超时释放双路径（#28）+ SLA 升级通知策略模式（#37, #39）
    - [ ] LLM 智能体分拣 + 失败回退（#42-43）
    - [ ] 500 万造数 + Explain 索引验证（#44-45）
    - [ ] RBAC Sa-Token + 数据过滤（#25-27）
    - [ ] AOP 非侵入式操作日志（#40）
  - [ ] **五、面试 18 问速查表**：将 TECHNICAL-PLAN.md 第 9 节的 18 个问答逐条整理为速查格式（Q + 要点 3-5 句），可直接用于面试前 15 分钟快速复习
  - [ ] **六、已知限制与待扩展**：真实 RabbitMQ 待迁移（RABBITMQ-MIGRATION.md）、附件上传设计预留、前端页面（AI 生成 Element-UI 模板）、移动端适配
- [ ] Docker 重启回归验证：`docker compose down && docker compose up -d` → 应用正常启动 → Knife4j 可访问 → 数据库数据不丢

---

> **里程碑 3（Day 9-14, Issue #36-#47）：站内信策略模式 + LLM 分拣 + 500万造数 + 交付 ✅ 全部待办**

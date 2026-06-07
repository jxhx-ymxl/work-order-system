# 企业工单流转平台 — 工程待办清单

> 拆解粒度：每个 Issue 控制在 1-2 小时工作量。
> 拆解范围：仅第 1 天 ~ 第 4 天（共 21 个 Issue）。

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

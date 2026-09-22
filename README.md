# 企业工单流转平台（高校后勤 / IT 报修）

> 项目定位：**临江理工大学东湖校区后勤与 IT 报修平台**——把"电话 + 微信群 + Excel 台账"的报修流程，替换为有状态、有时限、可追溯的工单闭环。
> 一句话架构：**Spring Boot 3 单体后端 + Vue3 前端 + MySQL/Redis**，异步与调度由 RabbitMQ + XXL-Job 承载（当前为 Mock/`@Scheduled` 占位，改造见 `ASYNC-SCHEDULING-PLAN.md`），全部组件用 Docker Compose 跑在 2C4G 单机。
> 文档权威顺序（引自 `CLAUDE.md`）：`BUSINESS-SCOPE.md`（业务基线）→ `ASYNC-SCHEDULING-PLAN.md`（技术方案）→ `CONTEXT.md`（术语）→ `TECHNICAL-PLAN.md`（原始设计）→ 代码；冲突时以「最近一次明确决策」为准并立即上报。

---

## 一、技术栈

**已定栈（允许清单，不是强制清单）**：Java 17、Spring Boot 3.3.x、MyBatis-Plus 3.5.x、MySQL 8.0、Redis 7、RabbitMQ 3.13、XXL-Job 2.4.x、Sa-Token、Knife4j。

**本阶段明确不引入**：Kafka、RocketMQ、PowerJob、MongoDB、Elasticsearch、Seata、Nacos 或任何配置中心、服务网格。

> 注意：上面是"可用能力清单"，**不是必须接入的东西**。RabbitMQ 只用于事件驱动与延迟触发，XXL-Job 只用于批作业与兜底扫描；**禁止为了"用上中间件"而在抢单、状态流转等强一致路径引入它们**（`CLAUDE.md` §2）。

---

## 二、本地启动

**方式一：Docker Compose（推荐，完整栈）**

详细步骤见 **[deploy/README.md](deploy/README.md)**（含 2C2G/2C4G 内存参数、构建方式、无 Docker 备选方案）。

```bash
cd deploy
docker compose up -d --build          # 默认端口：前端 80 / 后端 9000 / MySQL 3306 / Redis 6379
```

若本机已有 MySQL/Redis 占用默认端口，用本机隔离 override（换端口与容器名、独立数据卷）：

```bash
cd deploy
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --build
# 端口：前端 18080 / 后端 19000 / MySQL 13306 / Redis 16379
```

**方式二：只起依赖，后端本地跑**

```bash
# 1) 起 MySQL + Redis（可只依赖 compose 中的这两个服务）
# 2) 初始化库表与种子数据
mysql -h 127.0.0.1 -P 3306 -u root -p < sql/init.sql
# 3) 启动后端（默认读 application.yml，可用 MYSQL_HOST/REDIS_HOST 等环境变量覆盖）
mvn clean compile && mvn spring-boot:run
```

`sql/init.sql` 使用 `CREATE TABLE` 直接建表（非幂等），重复执行会因表已存在而失败；重建请先清库或使用带 `IF NOT EXISTS` 的变体。已有数据库补权限绑定用 `sql/hotfix-role-permissions.sql`。

---

## 三、演示入口

| 入口 | 地址（默认部署） | 地址（local override） |
| --- | --- | --- |
| 前端 Web | http://localhost/ | http://localhost:18080/ |
| 后端 API | http://localhost:9000/api/ | http://localhost:19000/api/ |
| 接口文档（Knife4j） | http://localhost:9000/doc.html | http://localhost:19000/doc.html |

**演示账号**：超管 `admin`（其余角色需由超管在"用户管理"中创建并分配角色）。

**初始密码：`admin123`**（用户名 `admin`）。

该明文与 `sql/init.sql` 中唯一那条 `INSERT INTO t_user` 的 BCrypt 哈希一一对应，已用 BCrypt 校验通过（收口 5：文件里原先"先插 `123456`、再用 UPDATE 覆盖成 `admin123`"的两段式写法已删除，避免注释与实际值不符）。部署冒烟探针 **P12** 就是"清库导入 `sql/init.sql` 后用该账号密码登录一次"，判断标准是**必须能登录**。

**演示脚本**：见 `BUSINESS-SCOPE.md` §6.1 业务动线（5 分钟，含 AI 异步分类的完整可观测时序）与 §6.2 技术动线（3 分钟，故障注入；需 P1–P5 完成后才可演示）。

---

## 四、已知缺口清单

这一节是 `BUSINESS-SCOPE.md` R6 与 R7 要求的"已知缺口标注"落点。**每条都必须带代价，不允许只写"不做"。**

| 编号 | 缺口 | 现状 | 代价（谁受影响、影响成什么样） |
| --- | --- | --- | --- |
| **N1** | 工单附件 / 照片上传 | **不做（R6 已裁决）** | 报修人**仍需在微信群里补图**；P4 的"分类靠人读"只能依赖文字描述，AI 分类对"只有照片才说得清"的故障（如墙面渗水、设备烧毁）判断力下降。做它的代价是对象存储、上传鉴权、内容类型校验、孤儿文件清理与备份体积翻倍，而目标机器只有一块 2C4G 的盘 |
| **N2** | 短信 / 邮件通道 | **不做（R7 已裁决收窄）** | 只在"外部渠道群机器人 webhook（F5-6）"这一条通道上满足非登录触达；短信与邮件不做，代价是**夜间与现场作业时，没有手机短信兜底**，若群机器人凭据失效则退化为只能登录看站内信 |
| **N2-补充** | 外部渠道 webhook（F5-6） | **尚未实现（C 类，排在 P5 之后）** | 当前非登录触达能力为**零**：师傅在现场作业时不登录系统就看不到任何超时告警（站内信只在登录后可见）。实现后若无可用凭据，必须在本文件把该通道标注为**「已实现未启用」**，不得留下半成品 |
| **G1–G5** | 五处已核实的代码缺口（`RELEASED` 死路、日志越权、三个权限码不生效、接管权限层与服务层不一致、SLA 接单时限界面在骗人） | 详见 `BUSINESS-SCOPE.md` §1.6 与 `INVARIANTS.md` | 每条缺口都有承接功能编号（F1-6 / F2-4 / F3-5 / F4-1 / F5-2）与验收标准，未修复前不要对外宣称这些能力已具备 |
| **I4 / I5** | `sla_deadline` 为 NULL 的静默失效（NULL 与任何值比较均为 unknown，永不进入 SLA 扫描） | 已定稿修复方案（a-2 兜底配置 + type 枚举校验 + 启动自检 `ensureSlaConfigComplete()`），实施在 P0 | 未修复前，漏配的 `type+priority` 组合会让该类工单**永远不告警且无任何报错**。2026-09-23 实测：未完结 NULL 工单 104 行，全部为测试残留（详见 `INVARIANTS.md` §2(c)） |

---

## 五、文档地图

| 文件 | 作用 | 是否权威来源 |
| --- | --- | --- |
| `CLAUDE.md` | 工程纪律、架构不变量、交付标准 | 是（协作规则） |
| `BUSINESS-SCOPE.md` | 业务范围、场景、功能清单、权限矩阵、演示动线 | **是（业务基线）** |
| `INVARIANTS.md` | 必须永远成立的条件 + 探针 SQL + 恢复步骤 | **是（与业务基线配套）** |
| `ASYNC-SCHEDULING-PLAN.md` | 异步与调度改造方案（资源核算、业务点论证、选型、实施顺序 P0–P7） | 是（技术方案） |
| `CONTEXT.md` | 领域术语表 | 是（术语） |
| `TECHNICAL-PLAN.md` | 原始设计文档（含**未实现**的 MQ/XXL-Job 设计，勿当作既成事实） | 参考 |
| `RABBITMQ-MIGRATION.md` | Mock → RabbitMQ 的迁移指南（与当前实现已漂移，见技术方案 §3.4） | 参考 |
| `PROJECT_MAP.md` | 仓库测绘（模块划分、漂移点） | 参考 |
| `ISSUES.md` | 历史任务清单，**已废弃、不作为任务来源** | 否 |
| `docs/INTERVIEW-*.md`、`docs/PERFORMANCE-TUNING.md` | 对外素材与性能调优记录，描述的是改造前的系统 | 否 |

任务来源：`ASYNC-SCHEDULING-PLAN.md` 的阶段化方案（P0–P7），不再使用 `ISSUES.md`。

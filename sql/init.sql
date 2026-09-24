-- ============================================================
-- 企业工单流转平台 — 初始化建表脚本
-- 字符集: utf8mb4  引擎: InnoDB
-- 对应 TECHNICAL-PLAN.md 1.1 & 1.2 节
-- ============================================================

-- ----------------------------
-- 1. 工单表
-- ----------------------------
CREATE TABLE t_work_order (
                              id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键(内部用)',
                              order_no        VARCHAR(22) NOT NULL UNIQUE COMMENT '工单编号: WO-YYYYMMDD-XXXXX',
                              title           VARCHAR(200) NOT NULL COMMENT '工单标题',
                              content         TEXT NOT NULL COMMENT '工单内容',
                              type            VARCHAR(32) NOT NULL COMMENT '工单类型: NETWORK/UTILITY/DORM/OTHER',
                              priority        TINYINT NOT NULL DEFAULT 0 COMMENT '优先级: 0普通 1紧急',
                              status          VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '当前状态',
                              submitter_id    BIGINT NOT NULL COMMENT '提交人ID',
                              assignee_id     BIGINT DEFAULT NULL COMMENT '处理人ID(接单后赋值)',
                              reject_count    INT NOT NULL DEFAULT 0 COMMENT '驳回次数',
                              max_reject      INT NOT NULL DEFAULT 3 COMMENT '最大驳回次数',
                              sla_deadline    DATETIME DEFAULT NULL COMMENT 'SLA截止时间',
                              version         INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
                              created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                              updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                              INDEX idx_order_no (order_no),
                              INDEX idx_status (status),
                              INDEX idx_submitter (submitter_id),
                              INDEX idx_assignee (assignee_id),
                              INDEX idx_sla (status, sla_deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单表';

-- ----------------------------
-- 2. 工单操作日志表
-- ----------------------------
CREATE TABLE t_work_order_log (
                                  id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
                                  order_id        BIGINT NOT NULL COMMENT '关联工单ID',
                                  order_no        VARCHAR(22) NOT NULL COMMENT '冗余工单编号，方便查询',
                                  operator_id     BIGINT NOT NULL COMMENT '操作人ID',
                                  action          VARCHAR(32) NOT NULL COMMENT '操作类型',
                                  old_status      VARCHAR(20) DEFAULT NULL COMMENT '变更前状态',
                                  new_status      VARCHAR(20) NOT NULL COMMENT '变更后状态',
                                  remark          VARCHAR(500) DEFAULT NULL COMMENT '备注',
                                  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                  INDEX idx_order (order_id),
                                  INDEX idx_order_no (order_no),
                                  INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单操作日志表';

-- ============================================================
-- RBAC 权限体系 (TECHNICAL-PLAN.md 1.3 节)
-- ============================================================

-- ----------------------------
-- 3. 用户表
-- ----------------------------
CREATE TABLE t_user (
                        id            BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
                        username      VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
                        password      VARCHAR(255) NOT NULL COMMENT 'BCrypt加密密码',
                        phone         VARCHAR(20) DEFAULT NULL COMMENT '手机号',
                        dept_id       BIGINT DEFAULT NULL COMMENT '所属部门ID',
                        status        TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
                        created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ----------------------------
-- 4. 角色表
-- ----------------------------
CREATE TABLE t_role (
                        id        BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
                        role_code VARCHAR(32) NOT NULL UNIQUE COMMENT '角色编码: SUBMITTER/HANDLER/DEPT_ADMIN/SYS_ADMIN',
                        role_name VARCHAR(50) NOT NULL COMMENT '角色名称',
                        remark    VARCHAR(200) DEFAULT NULL COMMENT '备注'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- ----------------------------
-- 5. 权限表
-- ----------------------------
CREATE TABLE t_permission (
                              id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
                              perm_code   VARCHAR(64) NOT NULL UNIQUE COMMENT '权限码: order:accept, order:stats, system:user:manage',
                              perm_name   VARCHAR(50) NOT NULL COMMENT '权限名称',
                              parent_id   BIGINT DEFAULT 0 COMMENT '父权限ID(支持菜单树)'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表';

-- ----------------------------
-- 6. 用户-角色关联表
-- ----------------------------
CREATE TABLE t_user_role (
                             user_id BIGINT NOT NULL COMMENT '用户ID',
                             role_id BIGINT NOT NULL COMMENT '角色ID',
                             PRIMARY KEY (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- ----------------------------
-- 7. 角色-权限关联表
-- ----------------------------
CREATE TABLE t_role_permission (
                                   role_id       BIGINT NOT NULL COMMENT '角色ID',
                                   permission_id BIGINT NOT NULL COMMENT '权限ID',
                                   PRIMARY KEY (role_id, permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联表';

-- ============================================================
-- 种子数据: 超级管理员 + 基础角色
-- ============================================================

-- 超级管理员用户 (明文密码: admin123, BCrypt 加密存储)
-- 收口 5：历史上此处先插入一条 "123456" 的哈希，随后又有一段位于文件末尾的 UPDATE
-- 把它覆盖成 admin123，造成"注释写着 admin123、实际插入的是 123456、最终又被改回 admin123"
-- 的三重混乱。两处冗余写法均已删除，现仅此一条 INSERT 写入 t_user.password，
-- 哈希与 admin123 一一对应（已用 BCrypt 校验）。
INSERT INTO t_user (id, username, password, phone, status) VALUES
    (1, 'admin', '$2a$10$1s93/XO7m.kI61bcmONyRutCPPMw9hqxd14syjk.8G/82JKi9HVIe', '13800000000', 1);

-- 基础角色
INSERT INTO t_role (id, role_code, role_name, remark) VALUES
    (1, 'SYS_ADMIN', '系统管理员', '拥有全部权限，可管理用户、角色、SLA配置');
INSERT INTO t_role (id, role_code, role_name, remark) VALUES
    (2, 'SUBMITTER', '提交人', '可提交工单、查看自己的工单');

-- admin 绑定 SYS_ADMIN 角色
INSERT INTO t_user_role (user_id, role_id) VALUES (1, 1);



CREATE TABLE t_sla_config (
                              id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
                              type            VARCHAR(32) NOT NULL COMMENT '工单类型: NETWORK/UTILITY/DORM/OTHER',
                              priority        TINYINT NOT NULL COMMENT '优先级: 0普通 1紧急',
                              accept_minutes  INT NOT NULL COMMENT 'N分钟内必须接单',
                              finish_minutes  INT NOT NULL COMMENT 'N分钟内必须处理完成',
                              UNIQUE KEY uk_type_priority (type, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLA配置表';

-- ----------------------------
-- 9. 站内信表
-- ----------------------------
CREATE TABLE t_notification (
                                id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
                                user_id     BIGINT NOT NULL COMMENT '接收人ID',
                                title       VARCHAR(200) NOT NULL COMMENT '通知标题',
                                content     VARCHAR(500) DEFAULT NULL COMMENT '通知内容',
                                ref_type    VARCHAR(20) DEFAULT NULL COMMENT '关联类型: ORDER/SYSTEM',
                                ref_id      BIGINT DEFAULT NULL COMMENT '关联ID(工单ID等)',
                                is_read     TINYINT NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
                                created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT
                                    '创建时间',
                                INDEX idx_user_read (user_id, is_read, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内信表';


-- 二、补齐缺失的 2 个角色（SYS_ADMIN 和 SUBMITTER 已在 init.sql 中）

INSERT INTO t_role (id, role_code, role_name, remark) VALUES
    (3, 'HANDLER', '处理人', '可抢单、处理工单、提交验收');
INSERT INTO t_role (id, role_code, role_name, remark) VALUES
    (4, 'DEPT_ADMIN', '部门主管', '可查看本部门工单、手动分配工单');


-- 三、12 条权限 + 3 条父级菜单（共 14 条，parent_id 建立菜单树）

-- 父级菜单权限
INSERT INTO t_permission (id, perm_code, perm_name, parent_id) VALUES
    (1,  'order:*',           '工单管理菜单',   0);
INSERT INTO t_permission (id, perm_code, perm_name, parent_id) VALUES
    (2,  'order:accept',      '抢单',           1);
INSERT INTO t_permission (id, perm_code, perm_name, parent_id) VALUES
    (3,  'order:start',       '开始处理',        1);
INSERT INTO t_permission (id, perm_code, perm_name, parent_id) VALUES
    (4,  'order:complete',    '提交验收',        1);
INSERT INTO t_permission (id, perm_code, perm_name, parent_id) VALUES
    (5,  'order:approve',     '验收通过',        1);
INSERT INTO t_permission (id, perm_code, perm_name, parent_id) VALUES
    (6,  'order:reject',      '验收驳回',        1);
INSERT INTO t_permission (id, perm_code, perm_name, parent_id) VALUES
    (7,  'order:assign',      '手动分配工单',     1);
INSERT INTO t_permission (id, perm_code, perm_name, parent_id) VALUES
    (8,  'order:stats',       '查看本部门统计',   1);
INSERT INTO t_permission (id, perm_code, perm_name, parent_id) VALUES
    (9,  'order:stats:all',   '查看全局统计',     1);
INSERT INTO t_permission (id, perm_code, perm_name, parent_id) VALUES
    (15, 'order:manage',      '接管/关闭升级工单',  1);

INSERT INTO t_permission (id, perm_code, perm_name, parent_id) VALUES
    (10, 'system:*',          '系统管理菜单',     0);
INSERT INTO t_permission (id, perm_code, perm_name, parent_id) VALUES
    (11, 'system:user:manage','用户管理',         10);
INSERT INTO t_permission (id, perm_code, perm_name, parent_id) VALUES
    (12, 'system:role:manage','角色管理',         10);

INSERT INTO t_permission (id, perm_code, perm_name, parent_id) VALUES
    (13, 'sla:*',             'SLA配置菜单',      0);
INSERT INTO t_permission (id, perm_code, perm_name, parent_id) VALUES
    (14, 'sla:config:manage', 'SLA配置管理',      13);


-- 四、SYS_ADMIN 拥有全部 14 条权限

INSERT INTO t_role_permission (role_id, permission_id) VALUES
                                                           (1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7),
                                                           (1, 8), (1, 9), (1, 10), (1, 11), (1, 12), (1, 13), (1, 14),
                                                           (1, 15);

-- DEPT_ADMIN (role_id=4) 追加: order:manage（接管/关闭升级工单）
INSERT IGNORE INTO t_role_permission (role_id, permission_id) VALUES (4, 15);

-- 四-B：SUBMITTER / HANDLER / DEPT_ADMIN 基础权限分配

-- SUBMITTER (role_id=2): order:reject（驳回自己的工单时需要此权限码）
INSERT IGNORE INTO t_role_permission (role_id, permission_id) VALUES (2, 6);

-- HANDLER (role_id=3): order:accept（抢单需要此权限码）
INSERT IGNORE INTO t_role_permission (role_id, permission_id) VALUES (3, 2);

-- DEPT_ADMIN (role_id=4): order:accept + order:assign + order:stats
INSERT IGNORE INTO t_role_permission (role_id, permission_id) VALUES (4, 2), (4, 7), (4, 8);


-- 五、SLA 默认配置（4 种工单类型 × 2 级优先级 = 8 条）
-- R4 已执行（P0b）：类型集合为 NETWORK/UTILITY/DORM/OTHER，仍 4 类 × 2 优先级 = 8 行。
-- 取值依据：网络故障与水电故障影响面最大（教学/办公全网不可用、停水停电），两档时限都取最短，
--   其中紧急档（priority=1）再减半；宿舍与公区维修属于常规维修，时限放宽一档；
--   OTHER 作为兜底取最长——**OTHER + 普通（480 分钟）必须存在**，它是 WorkOrderServiceImpl
--   在查不到 type+priority 配置时的兜底取值来源（I4 定稿 a-2），缺失会让兜底失效并落 NULL。

INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('NETWORK',    0, 30,  120);
INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('NETWORK',    1, 10,  60);
INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('UTILITY',    0, 30,  120);
INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('UTILITY',    1, 10,  60);
INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('DORM',       0, 60,  240);
INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('DORM',       1, 30,  120);
INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('OTHER',      0, 120, 480);
INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('OTHER',      1, 60,  240);


-- ----------------------------
-- 10. 事件发件箱 t_event_outbox（P1 步骤 2 新增）
-- ----------------------------
-- 用途：跨事务投递的唯一出口。业务事务内 INSERT 一行，独立投递任务再把它发到 MQ。
--   为什么需要它：afterCommit 直发存在双写窗口——commit 成功后、send 之前进程崩溃，
--   消息永久丢失且没有任何记录（Mock 实现下不可见，接真实 MQ 第一天就是线上问题）。
-- 影响行数量级：每张工单每次"接单/指派"产生 1 行；按日均 300–800 单估算，约 1–2.5 万行/月。
--   清理策略：SENT 记录保留 7 天后由归档任务删除（见下方注释），稳态行数 < 10 万。
-- 锁风险：写入是单行 INSERT（无间隙锁竞争）；投递任务按 (status, deliver_at, next_retry_at)
--   扫描并逐行 UPDATE 状态，使用 SELECT ... FOR UPDATE SKIP LOCKED 或状态抢占以避免长事务；
--   该索引同时服务扫描与 UPDATE 的定位，不会造成全表扫描。
CREATE TABLE t_event_outbox (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    event_id          VARCHAR(128) NOT NULL COMMENT '事件唯一键: {aggregate}:{id}:v{version}:{eventType}，同时是消费端幂等键',
    event_type        VARCHAR(64)  NOT NULL COMMENT '事件类型: ORDER_RELEASE_CHECK',
    aggregate_id      BIGINT       NOT NULL COMMENT '聚合根ID(工单ID)',
    aggregate_version INT          NOT NULL COMMENT '事件发生时的聚合版本(工单乐观锁version)，用于区分同一工单的多次合法事件',
    payload           JSON         NULL COMMENT '瘦消息载荷，只带 orderId',
    deliver_at        DATETIME     NOT NULL COMMENT '最早可投递时间 = 事件发生时间 + 该工单 type+priority 的 accept_minutes',
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '投递状态: PENDING未投递 / SENT已投递(收到publisher-confirm ack) / FAILED投递失败待人工介入',
    retry_count       INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    next_retry_at     DATETIME     NULL COMMENT '下次可重试时间(退避用)，NULL表示立即可投递',
    occurred_at       DATETIME     NOT NULL COMMENT '业务事件发生时间(业务侧时钟)',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间(数据库侧时钟)，与 occurred_at 分开以便区分"事件何时发生"与"何时写入"',
    sent_at           DATETIME     NULL COMMENT '实际投递成功时间',
    UNIQUE KEY uk_event_id (event_id),
    INDEX idx_dispatch (status, deliver_at, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件发件箱（outbox）：业务事务内写入，独立任务投递';

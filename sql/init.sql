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
                              type            VARCHAR(32) NOT NULL COMMENT '工单类型: REPAIR/LEAVE/REIMBURSE/OTHER',
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

-- 超级管理员用户 (密码: admin123, BCrypt加密)
INSERT INTO t_user (id, username, password, phone, status) VALUES
    (1, 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '13800000000', 1);

-- 基础角色
INSERT INTO t_role (id, role_code, role_name, remark) VALUES
    (1, 'SYS_ADMIN', '系统管理员', '拥有全部权限，可管理用户、角色、SLA配置');
INSERT INTO t_role (id, role_code, role_name, remark) VALUES
    (2, 'SUBMITTER', '提交人', '可提交工单、查看自己的工单');

-- admin 绑定 SYS_ADMIN 角色
INSERT INTO t_user_role (user_id, role_id) VALUES (1, 1);


UPDATE t_user SET password = '$2a$10$1s93/XO7m.kI61bcmONyRutCPPMw9hqxd14syjk.8G/82JKi9HVIe' WHERE username = 'admin';




CREATE TABLE t_sla_config (
                              id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
                              type            VARCHAR(32) NOT NULL COMMENT '工单类型:
  REPAIR/LEAVE/REIMBURSE/OTHER',
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
                                                           (1, 8), (1, 9), (1, 10), (1, 11), (1, 12), (1, 13), (1, 14);


-- 五、SLA 默认配置（4 种工单类型 × 2 级优先级 = 8 条）

INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('REPAIR',     0, 30,  120);
INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('REPAIR',     1, 10,  60);
INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('LEAVE',      0, 60,  120);
INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('LEAVE',      1, 30,  60);
INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('REIMBURSE',  0, 60,  240);
INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('REIMBURSE',  1, 30,  120);
INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('OTHER',      0, 120, 480);
INSERT INTO t_sla_config (type, priority, accept_minutes, finish_minutes)
VALUES
    ('OTHER',      1, 60,  240);


-- 六、admin 用户密码修正（使用已验证的 BCrypt 哈希）

UPDATE t_user SET password =
                      '$2a$10$1s93/XO7m.kI61bcmONyRutCPPMw9hqxd14syjk.8G/82JKi9HVIe' WHERE
    username = 'admin';
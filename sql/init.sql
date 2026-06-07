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

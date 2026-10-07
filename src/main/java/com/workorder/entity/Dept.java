package com.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门字典（`t_dept`，2026-10-08 部门实体化）。
 *
 * <p><b>模型选择</b>（见 `docs/DECISIONS.md` D109）：用户与工单**继续只存 `dept_id` 这个数字**，
 * 本表只提供"id → 名称 / 启停"；**不做层级**（无 parent_id）、**不做一人多部门**、**不做物理删除**
 * （"删除" = `enabled = 0`，理由是部门被用户/工单引用，物理删会留悬空引用）。
 */
@Data
@TableName("t_dept")
public class Dept {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 部门名称（唯一，≤64）。 */
    private String name;

    /** 1 启用 / 0 停用。 */
    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

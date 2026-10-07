package com.workorder.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门（管理端视图）：含停用项，并带上"该部门还有几个用户"。
 *
 * <p>`userCount` 就是"停用一个仍有用户的部门"的提示来源（A3 要求**不强拦**、但把
 * "该部门仍有 N 个用户"带在响应里——前端据此提示）。
 */
@Data
public class DeptVO {

    private Long id;

    private String name;

    /** 1 启用 / 0 停用 */
    private Integer enabled;

    /** 该部门下的用户数（引用关系；用于停用提示，不作为删除判据——我们不做物理删除） */
    private Long userCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

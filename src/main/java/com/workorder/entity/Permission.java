package com.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_permission")
public class Permission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String permCode;

    private String permName;

    private Long parentId;
}

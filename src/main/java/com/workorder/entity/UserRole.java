package com.workorder.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_user_role")
public class UserRole {

    private Long userId;

    private Long roleId;
}

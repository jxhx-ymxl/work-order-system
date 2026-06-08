package com.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_sla_config")
public class SlaConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String type;

    private Integer priority;

    private Integer acceptMinutes;

    private Integer finishMinutes;
}

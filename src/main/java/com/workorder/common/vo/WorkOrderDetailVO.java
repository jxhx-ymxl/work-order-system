package com.workorder.common.vo;

import lombok.Data;

import java.util.List;

@Data
public class WorkOrderDetailVO {

    private WorkOrderVO order;

    private List<WorkOrderLogVO> logs;
}

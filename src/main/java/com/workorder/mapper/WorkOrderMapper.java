package com.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workorder.entity.WorkOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {

    IPage<WorkOrder> selectPageWithConditions(Page<WorkOrder> page,
                                              @Param("status") String status,
                                              @Param("orderNo") String orderNo,
                                              @Param("submitterId") Long submitterId,
                                              @Param("assigneeId") Long assigneeId);
}

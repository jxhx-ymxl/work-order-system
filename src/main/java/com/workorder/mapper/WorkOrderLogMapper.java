package com.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workorder.common.vo.WorkOrderLogVO;
import com.workorder.entity.WorkOrderLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WorkOrderLogMapper extends BaseMapper<WorkOrderLog> {

    List<WorkOrderLogVO> selectByOrderId(@Param("orderId") Long orderId);
}

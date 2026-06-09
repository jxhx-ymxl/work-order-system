package com.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workorder.entity.WorkOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {

    IPage<WorkOrder> selectPageWithConditions(Page<WorkOrder> page,
                                              @Param("status") String status,
                                              @Param("orderNo") String orderNo,
                                              @Param("submitterId") Long submitterId,
                                              @Param("assigneeId") Long assigneeId);

    /** 原子抢单：单条 SQL 完成查找+更新，返回 affected rows */
    @Update("UPDATE t_work_order SET assignee_id = #{userId}, " +
            "status = 'ACCEPTED', version = version + 1 " +
            "WHERE id = #{orderId} AND assignee_id IS NULL AND status = 'PENDING'")
    int grabOrder(@Param("orderId") Long orderId, @Param("userId") Long userId);

    /** 带乐观锁的状态更新 */
    @Update("UPDATE t_work_order SET status = #{newStatus}, version = version + 1 " +
            "WHERE id = #{orderId} AND status = #{oldStatus} AND version = #{version}")
    int updateStatus(@Param("orderId") Long orderId,
                     @Param("oldStatus") String oldStatus,
                     @Param("newStatus") String newStatus,
                     @Param("version") Integer version);

    /** 驳回专用：同时递增 reject_count + 乐观锁校验 */
    @Update("UPDATE t_work_order SET status = #{newStatus}, " +
            "reject_count = reject_count + 1, version = version + 1 " +
            "WHERE id = #{orderId} AND status = 'AWAIT_APPROVAL' " +
            "AND version = #{version} AND reject_count = #{rejectCount}")
    int updateStatusAndIncrementReject(@Param("orderId") Long orderId,
                                       @Param("newStatus") String newStatus,
                                       @Param("version") Integer version,
                                       @Param("rejectCount") Integer rejectCount);

    /** 超时释放：清除处理人 + 更新状态 */
    @Update("UPDATE t_work_order SET assignee_id = NULL, " +
            "status = 'RELEASED', version = version + 1 " +
            "WHERE id = #{orderId} AND status = 'ACCEPTED'")
    int releaseOrder(@Param("orderId") Long orderId);

    /** 管理员分配：原子指派 */
    @Update("UPDATE t_work_order SET assignee_id = #{assigneeId}, " +
            "status = 'ACCEPTED', version = version + 1 " +
            "WHERE id = #{orderId} AND assignee_id IS NULL AND status = 'PENDING'")
    int assignOrder(@Param("orderId") Long orderId, @Param("assigneeId") Long assigneeId);

    /** Issue #36: SLA 超时扫描 —— 走 idx_sla 联合索引，分批拉取 */
    List<WorkOrder> findSlaExpired(@Param("batchSize") int batchSize);

    /** Issue #40: AOP 切面用 —— 标量查询绕过 MyBatis 一级缓存 */
    @Select("SELECT status FROM t_work_order WHERE id = #{orderId}")
    String getStatusById(@Param("orderId") Long orderId);
}

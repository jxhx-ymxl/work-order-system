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

    /**
     * 管理员接管升级工单：ESCALATED_ADMIN -> IN_PROGRESS，并写入接管人。
     * WHERE 同时带 status + version，靠受影响行数做乐观锁互斥——并发接管只有一方成功。
     */
    @Update("UPDATE t_work_order SET assignee_id = #{operatorId}, " +
            "status = 'IN_PROGRESS', version = version + 1 " +
            "WHERE id = #{orderId} AND status = 'ESCALATED_ADMIN' AND version = #{version}")
    int takeOverEscalated(@Param("orderId") Long orderId,
                          @Param("operatorId") Long operatorId,
                          @Param("version") Integer version);

    /** 系统管理员强制关闭升级工单：ESCALATED_ADMIN -> CLOSED（保留处理人现场，供追溯） */
    @Update("UPDATE t_work_order SET status = 'CLOSED', version = version + 1 " +
            "WHERE id = #{orderId} AND status = 'ESCALATED_ADMIN' AND version = #{version}")
    int closeEscalated(@Param("orderId") Long orderId, @Param("version") Integer version);

    /** Issue #36: SLA 超时扫描 —— 走 idx_sla 联合索引，分批拉取 */
    List<WorkOrder> findSlaExpired(@Param("batchSize") int batchSize);

    /**
     * P1 步骤 5：兜底释放扫描——**时限从 {@code t_sla_config.accept_minutes} 取**（按该工单的 type+priority），
     * 不再硬编码 30 分钟（修 G5/I8）。
     *
     * <p>用 INNER JOIN：查不到配置的工单**不会**进这个结果集（即"不释放"），
     * 它们由 {@link #findAcceptedOrdersWithoutSlaConfig} 单独查出来记 ERROR。
     * 这样"配置缺失"既不会退化成某个默认时限，也不会静默。
     */
    List<WorkOrder> findAcceptTimeoutOrders(@Param("batchSize") int batchSize);

    /**
     * P1 步骤 5：找出"ACCEPTED 但 type+priority 在 {@code t_sla_config} 里没有配置"的工单。
     *
     * <p>这些工单**不会被释放**（不发明默认时限），必须留痕——否则表现成"工单永远挂在那儿"，
     * 与 I4 的静默失效同类。
     */
    List<Long> findAcceptedOrdersWithoutSlaConfig(@Param("batchSize") int batchSize);

    /** Issue #40: AOP 切面用 —— 标量查询绕过 MyBatis 一级缓存 */
    @Select("SELECT status FROM t_work_order WHERE id = #{orderId}")
    String getStatusById(@Param("orderId") Long orderId);
}

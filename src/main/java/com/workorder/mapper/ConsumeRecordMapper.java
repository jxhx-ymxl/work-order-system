package com.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workorder.entity.ConsumeRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消费去重记录表入口。
 *
 * <p>只用 {@code insert} 与 {@code delete}：**不做"先查后写"**——查与写之间有空窗，
 * 两个实例会同时查到"没消费过"。幂等判定一律交给 {@code UNIQUE(event_id, consumer)} 的冲突。
 */
@Mapper
public interface ConsumeRecordMapper extends BaseMapper<ConsumeRecord> {
}

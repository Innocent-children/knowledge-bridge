package com.openclaw.kbbridge.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openclaw.kbbridge.entity.ReviewTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审核任务 Mapper 接口。
 */
@Mapper
public interface ReviewTaskMapper extends BaseMapper<ReviewTaskEntity> {
}

package com.openclaw.kbbridge.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openclaw.kbbridge.entity.QueryLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 查询日志 Mapper 接口。
 */
@Mapper
public interface QueryLogMapper extends BaseMapper<QueryLogEntity> {
}

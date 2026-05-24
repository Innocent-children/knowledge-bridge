package com.openclaw.kbbridge.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 入库任务 Mapper 接口。
 */
@Mapper
public interface IngestTaskMapper extends BaseMapper<IngestTaskEntity> {
}

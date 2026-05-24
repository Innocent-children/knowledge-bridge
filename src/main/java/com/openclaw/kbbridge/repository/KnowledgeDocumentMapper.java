package com.openclaw.kbbridge.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openclaw.kbbridge.entity.KnowledgeDocumentEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识文档 Mapper 接口。
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {
}

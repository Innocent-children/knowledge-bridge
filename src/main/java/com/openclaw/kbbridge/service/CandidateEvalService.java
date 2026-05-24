package com.openclaw.kbbridge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openclaw.kbbridge.dto.ingest.CandidateEvalRequest;
import com.openclaw.kbbridge.dto.ingest.CandidateEvalResponse;
import com.openclaw.kbbridge.dto.ingest.IngestRequest;
import com.openclaw.kbbridge.dto.ingest.IngestResponse;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import com.openclaw.kbbridge.util.HashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 自动候选评估服务。
 * <p>
 * 根据规则判定内容是否值得沉淀进知识库。判定通过后自动创建入库任务（状态 CANDIDATE）。
 * </p>
 * <p>
 * 判定规则：
 * <ol>
 * <li>内容长度 >= 50 字符（过短的内容信息量不足）</li>
 * <li>不是闲聊类内容（不包含纯闲聊关键词）</li>
 * <li>包含知识特征（技术术语、步骤描述、问答结构等）</li>
 * <li>内容不重复（content_hash 去重）</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
public class CandidateEvalService {

    /**
     * 最小内容长度阈值
     */
    private static final int MIN_CONTENT_LENGTH = 50;

    /**
     * 闲聊关键词（命中任一则判定为闲聊，不值得沉淀）
     */
    private static final Set<String> CHITCHAT_PATTERNS = Set.of(
            "你好", "谢谢", "哈哈", "好的", "收到", "OK", "ok",
            "嗯嗯", "了解", "明白", "没问题", "感谢");

    /**
     * 知识特征正则（命中任一则判定为知识型内容）
     */
    private static final List<Pattern> KNOWLEDGE_PATTERNS = List.of(
            Pattern.compile("(?m)^#{1,6}\\s+.+"), // Markdown 标题
            Pattern.compile("(?m)^\\d+\\.\\s+.+"), // 有序列表（步骤）
            Pattern.compile("(?i)(如何|怎么|为什么|什么是|how to|why|what is)"), // 问答特征
            Pattern.compile("(?m)^```"), // 代码块
            Pattern.compile("(?i)(配置|安装|部署|命令|执行|运行|步骤|方法|方案|解决)"), // 技术术语
            Pattern.compile("问题[：:].*回答[：:]", Pattern.DOTALL) // Q&A 结构
    );

    private final IngestTaskMapper ingestTaskMapper;
    private final IngestService ingestService;

    public CandidateEvalService(IngestTaskMapper ingestTaskMapper,
                                IngestService ingestService) {
        this.ingestTaskMapper = ingestTaskMapper;
        this.ingestService = ingestService;
    }

    /**
     * 评估内容是否值得自动候选入库。
     * <p>
     * 判定通过后自动调用 IngestService.createTask() 创建入库任务。
     * </p>
     *
     * @param request 候选评估请求
     * @return 评估结果
     */
    public CandidateEvalResponse evaluate(CandidateEvalRequest request) {
        String content = request.content();
        String requestId = request.requestId();

        // 规则 1：内容长度检查
        if (content == null || content.length() < MIN_CONTENT_LENGTH) {
            log.debug("自动候选判定: 内容过短, requestId={}, length={}",
                    requestId, content != null ? content.length() : 0);
            return new CandidateEvalResponse(requestId, false, "内容过短，不足以沉淀", null, false);
        }

        // 规则 2：闲聊检查
        String trimmed = content.trim();
        for (String pattern : CHITCHAT_PATTERNS) {
            if (trimmed.equals(pattern) || (trimmed.length() < 20 && trimmed.contains(pattern))) {
                log.debug("自动候选判定: 闲聊内容, requestId={}", requestId);
                return new CandidateEvalResponse(requestId, false, "闲聊内容，不需要沉淀", null, false);
            }
        }

        // 规则 3：知识特征检查
        boolean hasKnowledgeFeature = KNOWLEDGE_PATTERNS.stream()
                .anyMatch(p -> p.matcher(content).find());
        if (!hasKnowledgeFeature) {
            log.debug("自动候选判定: 无知识特征, requestId={}", requestId);
            return new CandidateEvalResponse(requestId, false, "未检测到知识特征", null, false);
        }

        // 规则 4：内容去重检查
        String contentHash = HashUtil.sha256(content);
        IngestTaskEntity duplicate = ingestTaskMapper.selectOne(
                new LambdaQueryWrapper<IngestTaskEntity>()
                        .eq(IngestTaskEntity::getContentHash, contentHash)
                        .ne(IngestTaskEntity::getStatus, DocumentStatus.FAILED.name())
                        .last("LIMIT 1"));
        if (duplicate != null) {
            log.debug("自动候选判定: 内容重复, requestId={}, existingTaskId={}",
                    requestId, duplicate.getId());
            return new CandidateEvalResponse(requestId, false, "内容已存在", duplicate.getId(), true);
        }

        // 判定通过，自动创建入库任务
        log.info("自动候选判定通过, 创建入库任务: requestId={}", requestId);
        IngestRequest ingestRequest = new IngestRequest(
                requestId,
                request.userId(),
                request.chatId(),
                request.messageIds(),
                content,
                request.sourceType(),
                request.attachments(),
                false);

        IngestResponse ingestResponse = ingestService.createTask(ingestRequest);

        return new CandidateEvalResponse(
                requestId,
                true,
                "内容值得沉淀，已创建候选任务",
                ingestResponse.taskId(),
                ingestResponse.duplicate());
    }
}

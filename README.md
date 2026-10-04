# Knowledge Bridge

## 单人统一知识库

博客后端和原 OpenClaw 插件共用一个知识库，统一由 knowledge-bridge 管理发布版本并调用 KBVector。插件源码与请求合同保持原样；博客只有管理员登录，没有注册、身份绑定或用户空间。新的文件只写私有 `kb-content` 桶下 `documents/{document_id}/...`，来源 BLOG/OPENCLAW 只作为元数据。保存草稿、导入和生成预览不会自动入库。

统一协议、目录、状态、部署顺序及本地验证边界见 [统一接入说明](docs/unified-knowledge.md)。以下原有说明介绍保留的路由、处理器、控制台及历史任务能力；新入口的写入、版本与重试以统一接入说明为准，历史对象不读取、移动或迁移。

Knowledge Bridge 是 OpenClaw 与 RAGFlow 之间的知识编排服务。它负责把上游消息转成可检索、可审核、可追踪的知识资产，并为查询请求返回结构化证据包；同时提供一个同源部署的 Web 管理台，用于对话、入库、审核、文档治理和运行状态查看。

## 技术栈

后端：

- Java 25 / Spring Boot 4.0.5 / Spring MVC + WebFlux WebClient
- MyBatis-Plus 3.5.16 / MySQL 8.0+
- MinIO Java SDK 9.0.0
- RAGFlow
- OpenAI 兼容 LLM API
- Micrometer + Spring Actuator
- JUnit 5、Mockito、jqwik、MockWebServer

前端：

- React 19 / TypeScript 6 / Vite 8 / Ant Design 6 / React Router 7
- Vitest、React Testing Library、fast-check

## 目录结构

```text
src/main/java/com/openclaw/kbbridge
├── builder/        # EvidencePackBuilder — 证据包构建器
├── client/         # RagflowClient、LlmClient、MinioStorageClient — 外部服务客户端
├── config/         # KbProperties、SecurityConfig、AsyncConfig、MinioConfig、健康检查、SPA 回退、WebMvc
├── controller/     # QueryController、ChatController、IngestController、ReviewController、DocumentController
├── dto/            # 请求与响应 DTO（chat/document/ingest/query/ragflow/review）
├── entity/         # MyBatis-Plus 实体（IngestTaskEntity、KnowledgeDocumentEntity、QueryLogEntity、ReviewTaskEntity）
├── exception/      # BizException、ValidationException、ExternalServiceException、GlobalExceptionHandler
├── model/enums/    # QueryRoute、QueryStatus、DocumentStatus、ReviewStatus、Confidence、KnowledgeType
├── processor/      # KnowledgeProcessor 接口及实现（Markdown、FeishuQa、Attachment）、QualityChecker
├── repository/     # Mapper 接口
├── router/         # QueryRouter、IngestRouter、QueryRouteStrategy 接口及实现（Rule、LLM）
├── security/       # HmacSignatureFilter、RateLimitFilter、SignatureValidator、CachedBodyHttpServletRequest
├── service/        # QueryService、IngestService、IngestAsyncWorker、IngestRetryScheduler、ReviewService、DocumentService、CandidateEvalService
└── util/           # HashUtil、JsonUtil、MarkdownUtil、TimeUtil
```

## 枚举与常量详解

### QueryRoute — 查询路由模式

| 值 | 含义 | 行为 |
|---|---|---|
| `KB_ONLY` | 只查知识库，严格基于检索结果回答 | 检索 RAGFlow + Memory，`allowModelSupplement=false`，instructions 要求严格依据 sources，并在回答末尾附 `## 参考原文` |
| `KB_PLUS_LLM` | 结合知识库回答，知识库优先，LLM 可补充（**默认**） | 检索 RAGFlow + Memory，`allowModelSupplement=true`，instructions 允许补充但需标注，并在回答末尾附 `## 参考原文` |
| `LLM_ONLY` | 完全不查知识库，由 LLM 自由回答 | 返回空证据包，`allowModelSupplement=false`，无 instructions |

路由判定优先级：问题包含 `#kb` → `KB_ONLY`（最高优先级） > `strictKbOnly` 标志 → `KB_ONLY` > 规则匹配 > 默认路由 `KB_PLUS_LLM`。

### 路由规则配置与使用

路由策略由 `KB_QUERY_ROUTE_STRATEGY` 选择：`rule`（默认）/ `llm`。两种策略都共用相同的前置短路（`#kb`、`strictKbOnly`），区别在于中间的命中逻辑和兜底链路。

#### 决策链

`rule` 策略：

```
#kb         ─► KB_ONLY
strictKbOnly ─► KB_ONLY
规则匹配     ─► 规则的 route（pattern 子串命中，或 category 非空且 keywords 任一命中）
都未命中     ─► KB_QUERY_DEFAULT_ROUTE
```

`llm` 策略：

```
#kb          ─► KB_ONLY
strictKbOnly ─► KB_ONLY
LLM 意图分类  ─► 置信度 ≥ KB_QUERY_SCORE_THRESHOLD 时用 LLM 给的 route
              ─► 否则回退到 rule 策略（同上继续判定）
```

#### 规则数组结构

规则配置在 `kb.query.rules`（`application.yml`），每条规则字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `pattern` | String | 子串匹配：`question.contains(pattern)` 命中 |
| `keywords` | List&lt;String&gt; | 关键词列表：`category` 非空时，问题包含任一关键词即命中 |
| `route` | String | 命中后路由到：`KB_ONLY` / `KB_PLUS_LLM` / `LLM_ONLY` |
| `category` | String | 类别标签；当前实现中也是启用 `keywords` 匹配的前提 |

规则按数组顺序遍历，先命中的先生效。`pattern` 命中，或 `category` 非空且 `keywords` 任一命中，即算匹配。

#### 配置示例

```yaml
kb:
  query:
    route-strategy: rule
    default-route: KB_PLUS_LLM
    rules:
      # 闲聊 → 不查知识库
      - category: chitchat
        keywords: ["你好", "谢谢", "哈哈", "晚安"]
        route: LLM_ONLY
      # 合规/制度类 → 仅知识库
      - category: strict
        keywords: ["报销", "合规", "制度", "规定"]
        route: KB_ONLY
      # 技术类 → 知识库 + LLM 补充
      - category: tech
        keywords: ["如何", "怎么", "为什么", "配置"]
        route: KB_PLUS_LLM
      # pattern 子串示例
      - pattern: "[技术]"
        route: KB_PLUS_LLM
```

> 当前 `application.yml` 中 `rules: []`，未配置任何规则时请求会直接落到 `KB_QUERY_DEFAULT_ROUTE`。

#### 请求参数触发不同路由

使用者侧可以通过 `question` 文本和 `flags` 字段控制路由：

| 目标路由 | 做法 | 示例 `question` / `flags` |
|---|---|---|
| `KB_ONLY`（最高优先级） | question 含 `#kb` | `"#kb 报销流程是什么"` |
| `KB_ONLY`（强制） | `flags.strictKbOnly=true` | 任意 question |
| 规则命中的 route | question 命中已配置的 pattern，或命中带 category 的 keywords | 视规则而定 |
| `KB_QUERY_DEFAULT_ROUTE` | 都不命中 | `"随便说点什么"` |

注意：`strictKbOnly` 是 Java `boolean`，未传或传 `null` 时按 `false` 处理，不会强制 KB_ONLY。

#### 常见配方

- **全局只用知识库**：`KB_QUERY_DEFAULT_ROUTE=KB_ONLY` + `rules: []`。所有请求兜底到 `KB_ONLY`，不需要修改客户端。
- **全局知识库优先、LLM 可补充**：`KB_QUERY_DEFAULT_ROUTE=KB_PLUS_LLM` + `rules: []`。
- **闲聊放过、业务走知识库**：`default-route=KB_PLUS_LLM` + 配置 chitchat 规则指向 `LLM_ONLY`。
- **锁死只用知识库（客户端侧）**：每次请求传 `flags.strictKbOnly=true`，优先级高于所有规则和默认路由。

### QueryStatus — 查询状态生命周期

| 值 | 含义 |
|---|---|
| `QUERY_RECEIVED` | 已接收查询请求，初始状态 |
| `ROUTE_LLM_ONLY` | 路由判定为不查知识库 |
| `ROUTE_KB_ONLY` | 路由判定为只查知识库 |
| `ROUTE_KB_PLUS_LLM` | 路由判定为结合知识库回答 |
| `RETRIEVAL_LOW_CONFIDENCE` | 检索结果置信度低（top1 score < 0.6） |
| `ANSWERED` | 查询已完成回答 |
| `ANSWER_FAILED` | 查询回答失败（外部服务异常或不可恢复异常） |

### DocumentStatus — 入库任务/文档状态机

| 值 | 含义 |
|---|---|
| `RECEIVED` | 已接收入库请求，初始状态 |
| `RAW_STORED` | 原始件已保存到 MinIO |
| `PROCESSING` | LLM 知识化重写中 |
| `COMPLETED` | 处理件已保存到 MinIO，入库完成 |
| `FAILED` | 处理失败（任一步骤异常） |
| `DISABLED` | 已禁用，从检索中排除 |

### ReviewStatus — 审核状态

| 值 | 含义 |
|---|---|
| `CANDIDATE` | 候选待审核（入库任务创建时的初始审核状态） |
| `APPROVED` | 审核通过 |
| `REJECTED` | 审核拒绝 |

### Confidence — 检索置信度

| 值 | 判定条件 |
|---|---|
| `HIGH` | top1 score >= 0.8 |
| `MEDIUM` | 0.6 <= top1 score < 0.8 |
| `LOW` | top1 score < 0.6 |

由 `Confidence.fromScore(double score)` 方法计算。

### KnowledgeType — 知识类型

| 值 | 含义 |
|---|---|
| `GUIDE` | 面向完整流程问题的知识形态（教程/长文档重写产物） |
| `QA` | 面向点状知识问题的知识形态（问答对重写产物） |
| `MEMORY_REF` | Memory 引用 |
| `RAW` | 原始内容 |

### sourceType — 入库来源类型（字符串常量）

在 `IngestService.VALID_SOURCE_TYPES` 中定义：

| 值 | 含义 | 路由到的处理器 |
|---|---|---|
| `MARKDOWN` | Markdown 文档 | `MarkdownKnowledgeProcessor` |
| `TUTORIAL` | 教程 | `MarkdownKnowledgeProcessor` |
| `NOTE` | 笔记 | `MarkdownKnowledgeProcessor` |
| `FEISHU_CHAT` | 飞书内容（单轮问答或文档） | 智能判断：问答/聊天 → `FeishuQaProcessor`，长文档/教程 → `MarkdownKnowledgeProcessor` |
| `ATTACHMENT` | 附件 | `AttachmentProcessor`（内部再委托） |

## 完整调用链路

### 1. 查询链路 — `POST /api/v1/query`

OpenClaw 调用的证据包查询接口，需要 HMAC 签名。

```
HTTP 请求
  │
  ├─ RateLimitFilter.doFilterInternal()          // 按客户端 IP 固定窗口限流（60次/分钟）
  │    └─ clientKey(): 优先取 X-Forwarded-For，回退到 remoteAddr
  │
  ├─ HmacSignatureFilter.doFilterInternal()      // HMAC-SHA256 签名验证
  │    ├─ 读取 X-KB-Signature / X-KB-Timestamp / X-KB-RequestId 请求头
  │    ├─ CachedBodyHttpServletRequest: 缓存请求体（限制 1MB）
  │    ├─ SignatureValidator.verify(): 重新计算签名并常量时间比对
  │    │    └─ sign(): requestId + timestamp + SHA256Hex(requestBody) → HMAC-SHA256 → Base64
  │    └─ SignatureValidator.isTimestampValid(): |当前时间 - 请求时间戳| <= 5分钟
  │
  └─ QueryController.query(QueryRequest)
       │
       ├─ 1. 幂等检查
       │    └─ QueryLogMapper.selectOne(requestId)
       │         ├─ 命中且 responseJson 非空 → ObjectMapper.readValue() 反序列化缓存 → 直接返回
       │         └─ 未命中或反序列化失败 → 继续执行
       │
       └─ 2. QueryService.query(QueryRequest)
            │
            ├─ 2.1 创建查询日志
            │    └─ createQueryLog(): 构建 QueryLogEntity(status=QUERY_RECEIVED, route=PENDING)
            │    └─ QueryLogMapper.insert()
            │
            ├─ 2.2 路由判定
            │    └─ QueryRouter.route(request)
            │         └─ activeStrategy.resolve(request)
            │              │
            │              ├─ [strategy=rule] RuleBasedRouteStrategy.resolve()
            │              │    ├─ 优先级1: 问题包含 #kb → KB_ONLY（最高优先级）
            │              │    ├─ 优先级2: flags.strictKbOnly=true → KB_ONLY
            │              │    ├─ 优先级3: 遍历 kb.query.rules 列表
            │              │    │    └─ matchesRule(): question.contains(pattern) 或 category 非空且 question.contains(keyword)
            │              │    └─ 优先级4: 默认路由 kb.query.default-route（默认 KB_PLUS_LLM）
            │              │
            │              └─ [strategy=llm] LlmRouteStrategy.resolve()
            │                   ├─ 优先级1: 问题包含 #kb → KB_ONLY（最高优先级）
            │                   ├─ 优先级2: flags.strictKbOnly=true → KB_ONLY
            │                   ├─ 优先级3: LlmClient.complete(SYSTEM_PROMPT, question, {temperature:0.1})
            │                   │    └─ parseLlmResponse(): 正则提取 JSON → 解析 route + confidence
            │                   │         ├─ confidence >= scoreThreshold → 返回 LLM 判定的路由
            │                   │         └─ confidence < scoreThreshold → 回退到 RuleBasedRouteStrategy
            │                   └─ LLM 调用失败 → 回退到 RuleBasedRouteStrategy
            │
            ├─ 2.3 更新日志状态
            │    └─ routeToStatus(): LLM_ONLY→ROUTE_LLM_ONLY, KB_ONLY→ROUTE_KB_ONLY, KB_PLUS_LLM→ROUTE_KB_PLUS_LLM
            │    └─ KbMetrics.recordQuery(route)
            │
            ├─ 2.4 [route=LLM_ONLY] 直接返回空证据包
            │    └─ buildEmptyResponse(): sources=[], confidence=LOW, allowModelSupplement=false
            │
            ├─ 2.5 [route≠LLM_ONLY] 调用 RAGFlow 检索
            │    ├─ buildRetrievalRequest()
            │    │    ├─ 数据集: kb.query.dataset-ids（非空时使用），否则回退到 kb.ragflow.dataset-id
            │    │    └─ metadata 过滤: kb.query.metadata-filter-enabled=true 时添加 reviewStatus=APPROVED
            │    └─ RagflowClient.retrieval(RetrievalRequest, requestId)
            │         └─ RagflowClientImpl.executeWithRetry("retrieval", ...)
            │              ├─ WebClient.post() → /api/v1/retrieval
            │              ├─ 超时: kb.ragflow.timeout-ms（默认 5s）
            │              ├─ 重试: 最多 kb.ragflow.retry-max-attempts 次（默认 3），间隔 kb.ragflow.retry-delay-ms（默认 1s）
            │              └─ 失败 → ExternalServiceException(serviceName="RAGFlow")
            │
            ├─ 2.6 转换并去重
            │    └─ convertAndDedup(): RetrievalResponse.chunks → EvidenceSource 列表，按 content 字符串去重
            │
            ├─ 2.7 [memoryEnabled=true 且 route=KB_ONLY|KB_PLUS_LLM] Memory 检索
            │    └─ searchMemory()
            │         ├─ RagflowClient.searchMemory(MemorySearchRequest, requestId)
            │         │    └─ WebClient.post() → /api/v1/retrieval/memory
            │         ├─ 转换为 EvidenceSource(dataset="memory", title="memory")
            │         ├─ 合并到主检索结果 → 重新 dedup()
            │         └─ Memory 检索失败仅记录警告，不影响主流程
            │
            ├─ 2.8 构建证据包
            │    └─ EvidencePackBuilder.build(requestId, route, sources)
            │         ├─ Step 1: 按 score 降序排序
            │         ├─ Step 2: maxSources 截断（默认 5 条）
            │         ├─ Step 3: maxContentLength 单条截断（默认 2000 字符，超出加 "[...]"）
            │         ├─ Step 4: maxTotalLength 总长度截断（默认 8000 字符，移除最低分条目）
            │         ├─ Step 5: 计算 RetrievalQuality
            │         │    ├─ hitCount: 最终条数
            │         │    ├─ confidence: Confidence.fromScore(topScore)
            │         │    ├─ truncated: 是否发生过截断
            │         │    └─ originalHitCount: 截断前条数
            │         ├─ Step 6: resolveAllowModelSupplement(): KB_PLUS_LLM → true，其他 → false
            │         └─ Step 7: resolveInstructions()
            │              ├─ KB_ONLY → 严格依据 sources，不补充自身知识，缺失时说明知识库无相关信息，并逐字附 `## 参考原文`
            │              ├─ KB_PLUS_LLM → 优先依据 sources，可标注补充通用说明，并逐字附 `## 参考原文`
            │              └─ LLM_ONLY → []
            │
            ├─ 2.9 低置信度处理
            │    └─ confidence=LOW → 状态更新为 RETRIEVAL_LOW_CONFIDENCE
            │
            ├─ 2.10 保存成功结果
            │    └─ saveSuccessResult(): 状态→ANSWERED，序列化 responseJson，写入 sourceCount/topScore
            │
            └─ 2.11 异常处理
                 ├─ ExternalServiceException → handleDegradation()
                 │    ├─ KB_ONLY → 空 sources + "知识库暂无可靠答案"
                 │    ├─ KB_PLUS_LLM → 空 sources + LOW 置信度 + allowModelSupplement=true
                 │    └─ 状态→ANSWER_FAILED，保存降级响应到 responseJson
                 └─ 其他异常 → 状态→ANSWER_FAILED，抛出异常
```

### 2. 聊天链路 — `POST /api/v1/chat`

管理台聊天接口，不需要 HMAC 签名。服务端完成检索 + LLM 回答，API Key 保留在服务端。

```
HTTP 请求
  │
  ├─ RateLimitFilter.doFilterInternal()          // 限流
  ├─ HmacSignatureFilter.shouldNotFilter()       // /api/v1/chat 在排除列表中，跳过 HMAC
  │
  └─ ChatController.chat(ChatRequest)
       │
       ├─ 1. 生成 requestId = UUID.randomUUID()
       │
       ├─ 2. 构建内部 QueryRequest(requestId, userId="console", question=request.question())
       │    └─ QueryService.query(queryRequest)    // 完整查询链路（同上 2.1-2.10）
       │         └─ 返回 QueryResponse（含 route、sources、retrievalQuality）
       │
       ├─ 3. 构建系统提示词
       │    ├─ [hasSources=true 且 route≠LLM_ONLY]
       │    │    └─ buildEvidenceSystemPrompt(sources)
       │    │         └─ 拼接每条 source: "[title] dataset\ncontent" → 嵌入 Evidence Sources 模板
       │    └─ [hasSources=false 或 route=LLM_ONLY]
       │         └─ buildDefaultSystemPrompt(): 通用助手提示词
       │
       ├─ 4. 调用 LLM 生成回答
       │    └─ LlmClient.complete(systemPrompt, question)
       │         └─ LlmClientImpl.complete()
       │              ├─ buildRequestBody(): {model, messages: [{role:system,...}, {role:user,...}]}
       │              ├─ WebClient.post() → /v1/chat/completions
       │              ├─ 超时: kb.processor.llm-timeout-ms（默认 30s）
       │              ├─ extractContent(): 提取 choices[0].message.content
       │              └─ 失败 → ExternalServiceException(serviceName="LLM")
       │
       └─ 5. 返回 ChatResponse
            ├─ 成功: ChatResponse(answer, route, sources, llmError=false, errorMessage=null)
            ├─ QueryService 失败: HTTP 502 + ChatResponse(answer=null, llmError=false, errorMessage=...)
            └─ LLM 失败: HTTP 200 + ChatResponse(answer=null, route, sources, llmError=true, errorMessage=...)
```

### 3. 手动入库链路 — `POST /api/v1/ingest/manual`

手动入库接口，不需要 HMAC 签名。

```
HTTP 请求
  │
  ├─ RateLimitFilter / HmacSignatureFilter（跳过 HMAC）
  │
  └─ IngestController.ingestManual(IngestRequest)
       │
       └─ IngestService.createTask(IngestRequest)
            │
            ├─ 1. sourceType 校验
            │    └─ VALID_SOURCE_TYPES = {MARKDOWN, TUTORIAL, NOTE, FEISHU_CHAT, ATTACHMENT}
            │    └─ 不在集合中 → 抛出 ValidationException
            │
            ├─ 2. 计算内容哈希
            │    └─ HashUtil.sha256(content): SHA-256 → 十六进制字符串
            │
            ├─ 3. requestId 幂等检查
            │    └─ IngestTaskMapper.selectOne(requestId)
            │         └─ 命中 → 返回 IngestResponse(taskId=existing.id, status=existing.status, duplicate=false)
            │
            ├─ 4. content_hash 去重检查（force=false 时）
            │    └─ IngestTaskMapper.selectOne(contentHash, status≠FAILED)
            │         └─ 命中 → 返回 IngestResponse(taskId=duplicate.id, duplicate=true)
            │    [force=true 时] 跳过去重，查找旧任务以便后续版本管理
            │
            ├─ 5. 创建 IngestTaskEntity
            │    ├─ status = RECEIVED
            │    ├─ reviewStatus = CANDIDATE
            │    ├─ retryCount = 0
            │    ├─ contentHash = SHA-256(content)
            │    └─ IngestTaskMapper.insert()
            │         └─ DuplicateKeyException → 并发幂等处理
            │
            ├─ 6. 触发异步处理（独立 Bean，确保 @Async AOP 代理生效）
            │    └─ IngestAsyncWorker.processAsync(taskId, content, attachments)
            │         │
            │         │  ┌─────────────────────────────────────────────────────────┐
            │         │  │  以下在 @Async("ingestTaskExecutor") 线程池中异步执行     │
            │         │  └─────────────────────────────────────────────────────────┘
            │         │
            │         ├─ Step 1: 保存原始件到 MinIO → RAW_STORED
            │         │    └─ MinioStorageClient.putRawObject(sourceType, content)
            │         │         └─ putObject(rawBucket, "{sourceType}/{yyyy-MM-dd}/{yyyy-MM-dd-HHmmss}-raw.md", content)
            │         │              └─ MinioClient.putObject(): contentType="text/markdown; charset=utf-8"
            │         │
            │         ├─ Step 2: 路由到处理器
            │         │    └─ IngestRouter.route(sourceType)
            │         │         └─ resolveProcessorBeanName()
            │         │              ├─ MARKDOWN / TUTORIAL / NOTE → "markdownKnowledgeProcessor"
            │         │              ├─ FEISHU_CHAT → 按内容智能判断："feishuQaProcessor" 或 "markdownKnowledgeProcessor"
            │         │              ├─ ATTACHMENT → "attachmentProcessor"
            │         │              └─ 未知类型 → 回退到 "feishuQaProcessor"
            │         │
            │         ├─ Step 3: LLM 知识化重写 → PROCESSING
            │         │    └─ processor.process(content, sourceType, context)
            │         │         │
            │         │         ├─ [MarkdownKnowledgeProcessor] Guide + Q&A 双轨重写
            │         │         │    ├─ 加载 classpath:prompts/guide-template.md 和 qa-template.md
            │         │         │    ├─ 用 <content> 标签包裹原始内容（防 prompt injection）
            │         │         │    ├─ LlmClient.complete(guidePromptTemplate, wrappedContent) → guideContent
            │         │         │    ├─ LlmClient.complete(qaPromptTemplate, wrappedContent) → qaContent
            │         │         │    ├─ extractTopic(): MarkdownUtil.extractHeaders() 取第一个标题
            │         │         │    ├─ extractTags(): MarkdownUtil.extractKeywords() 取前 10 个
            │         │         │    └─ 返回 ProcessResult(guideContent, qaContent, processorVersion, topic, tags)
            │         │         │
            │         │         ├─ [FeishuQaProcessor] 仅 Q&A 重写
            │         │         │    ├─ 加载 classpath:prompts/qa-template.md
            │         │         │    ├─ LlmClient.complete(qaPromptTemplate, wrappedContent) → qaContent
            │         │         │    ├─ extractTopic() / extractTags()
            │         │         │    └─ 返回 ProcessResult(null, qaContent, processorVersion, topic, tags)
            │         │         │
            │         │         └─ [AttachmentProcessor] 内容分析后委托
            │         │              └─ resolveDelegate(content)
            │         │                   ├─ 长度 > 500 且包含 Markdown 标题 → MarkdownKnowledgeProcessor
            │         │                   └─ 其他 → FeishuQaProcessor
            │         │
            │         ├─ Step 4: 质量校验
            │         │    └─ QualityChecker.check(rawContent, processResult, sourceType)
            │         │         ├─ checkGuideFormat(): Guide 内容须包含 Markdown 标题和 ---CHUNK--- 分隔符
            │         │         ├─ checkQaFormat(): Q&A 内容须包含 "## Q" 章节、**问题**：和 **回答**：标记
            │         │         ├─ checkCompleteness(): 检查乱码字符、未闭合代码块、空内容
            │         │         ├─ [教程类: MARKDOWN/TUTORIAL/NOTE] checkQaCount():
            │         │         │    └─ Q&A 章节数 >= min-qa-count（默认 3）
            │         │         ├─ 校验失败 → 保存失败件到 MinIO failed/ 目录 → 状态 FAILED
            │         │         └─ 校验通过 → 继续
            │         │
            │         ├─ Step 5: 保存处理件到 MinIO → COMPLETED
            │         │    ├─ [guideContent≠null] MinioStorageClient.putProcessedGuide(guideContent, topic)
            │         │    │    └─ processedBucket: "guide/{yyyy-MM-dd}/{topic}-guide.md"（topic 由 LLM 决定，回退到日期格式）
            │         │    └─ [qaContent≠null] MinioStorageClient.putProcessedQa(qaContent, topic)
            │         │         └─ processedBucket: "qa/{yyyy-MM-dd}/{topic}-qa.md"（topic 由 LLM 决定，回退到日期格式）
            │         │
            │         └─ Step 6: 创建 kb_document 记录
            │              └─ DocumentService.createNewVersion(taskId, knowledgeType, title, null, topic, tagsJson)
            │                   ├─ [qaContent≠null] 创建 QA 类型文档
            │                   │    ├─ getLatestVersion(): 查询同 taskId+knowledgeType 最高版本
            │                   │    ├─ disableOldVersions(): 禁用旧版本文档（status→DISABLED）
            │                   │    └─ 插入新文档: version=max+1, status=COMPLETED, reviewStatus=CANDIDATE
            │                   └─ [guideContent≠null] 创建 GUIDE 类型文档（同上流程）
            │
            └─ 7. 返回 IngestResponse(requestId, taskId, status=RECEIVED, duplicate=false)
```

### 4. 自动候选评估链路 — `POST /api/v1/ingest/candidate`

OpenClaw 转发普通消息时调用，需要 HMAC 签名。由 Knowledge Bridge 判定内容是否值得沉淀。

```
HTTP 请求
  │
  ├─ RateLimitFilter / HmacSignatureFilter（需要 HMAC 验签）
  │
  └─ IngestController.evaluateCandidate(CandidateEvalRequest)
       │
       └─ CandidateEvalService.evaluate(CandidateEvalRequest)
            │
            ├─ 规则 1: 内容长度检查
            │    └─ content.length() < 50 → CandidateEvalResponse(worthy=false, reason="内容过短")
            │
            ├─ 规则 2: 闲聊检查
            │    └─ CHITCHAT_PATTERNS = {"你好","谢谢","哈哈","好的","收到","OK","ok","嗯嗯","了解","明白","没问题","感谢"}
            │    └─ 内容等于闲聊词 或 (长度<20 且包含闲聊词) → CandidateEvalResponse(worthy=false, reason="闲聊内容")
            │
            ├─ 规则 3: 知识特征检查
            │    └─ KNOWLEDGE_PATTERNS（正则列表）:
            │         ├─ Markdown 标题: ^#{1,6}\s+.+
            │         ├─ 有序列表: ^\d+\.\s+.+
            │         ├─ 问答特征: (如何|怎么|为什么|什么是|how to|why|what is)
            │         ├─ 代码块: ^```
            │         ├─ 技术术语: (配置|安装|部署|命令|执行|运行|步骤|方法|方案|解决)
            │         └─ Q&A 结构: 问题[：:].*回答[：:]
            │    └─ 无任何匹配 → CandidateEvalResponse(worthy=false, reason="未检测到知识特征")
            │
            ├─ 规则 4: 内容去重检查
            │    └─ HashUtil.sha256(content) → IngestTaskMapper.selectOne(contentHash, status≠FAILED)
            │    └─ 命中 → CandidateEvalResponse(worthy=false, reason="内容已存在", taskId=existing, duplicate=true)
            │
            └─ 判定通过 → 自动创建入库任务
                 └─ IngestService.createTask(IngestRequest(force=false))
                      └─ （同上手动入库链路 3.1-3.7）
                 └─ CandidateEvalResponse(worthy=true, reason="内容值得沉淀", taskId=..., duplicate=...)
```

### 5. 入库状态查询链路 — `GET /api/v1/ingest/status/{taskId}`

```
HTTP 请求
  │
  └─ IngestController.getStatus(taskId)
       │
       ├─ IngestService.getTask(taskId)
       │    └─ IngestTaskMapper.selectById(taskId)
       │         ├─ null → HTTP 404
       │         └─ 非空 → 继续
       │
       └─ 构建 IngestStatusResponse
            └─ IngestStatusResponse(id, requestId, status, reviewStatus, rawObjectKey,
                 processedGuideKey, processedQaKey, errorMessage, createdAt, updatedAt)
```

### 6. 入库任务列表链路 — `GET /api/v1/ingest/tasks`

```
HTTP 请求
  │
  └─ IngestController.listTasks(page, size, status?, reviewStatus?)
       │
       └─ IngestTaskMapper.selectPage(page, wrapper)
            ├─ [status 非空] WHERE status = ?
            ├─ [reviewStatus 非空] WHERE review_status = ?
            └─ ORDER BY created_at DESC
```

### 7. 审核通过链路 — `POST /api/v1/review/approve`

```
HTTP 请求
  │
  └─ ReviewController.approve(ReviewApproveRequest{taskId, reviewer, comment})
       │
       └─ ReviewService.approve(request)
            │
            ├─ findTaskOrThrow(taskId)
            │    └─ IngestTaskMapper.selectById(taskId)
            │         └─ null → 抛出 BizException("入库任务不存在")
            │
            ├─ validateCandidateStatus(task)
            │    └─ reviewStatus ≠ CANDIDATE → 抛出 BizException("当前审核状态不允许操作")
            │
            ├─ 更新入库任务
            │    ├─ reviewStatus → APPROVED
            │    ├─ status → COMPLETED
            │    └─ IngestTaskMapper.updateById()
            │
            └─ 创建审核记录
                 └─ createReviewRecord(taskId, APPROVED, reviewer, comment)
                      └─ ReviewTaskMapper.insert(ReviewTaskEntity)
```

### 8. 审核拒绝链路 — `POST /api/v1/review/reject`

```
HTTP 请求
  │
  └─ ReviewController.reject(ReviewRejectRequest{taskId, reviewer, comment})
       │
       └─ ReviewService.reject(request)
            │
            ├─ findTaskOrThrow(taskId) → 同上
            ├─ validateCandidateStatus(task) → 同上
            │
            ├─ 更新入库任务
            │    ├─ reviewStatus → REJECTED
            │    └─ IngestTaskMapper.updateById()
            │
            └─ createReviewRecord(taskId, REJECTED, reviewer, comment)
```

### 9. 批量审核链路 — `POST /api/v1/review/batch`

```
HTTP 请求
  │
  └─ ReviewController.batchReview(ReviewBatchRequest{reviewer, items[]})
       │
       └─ ReviewService.batchReview(request)
            │
            └─ 遍历 items:
                 ├─ item.action = "APPROVE"
                 │    └─ approve(ReviewApproveRequest(item.taskId, reviewer, item.comment))
                 │         └─ （同上审核通过链路）
                 ├─ item.action = "REJECT"
                 │    └─ reject(ReviewRejectRequest(item.taskId, reviewer, item.comment))
                 │         └─ （同上审核拒绝链路）
                 └─ 其他 action → BatchResult(success=false, message="不支持的审核动作")
                 │
                 └─ 每条独立 try-catch，单条失败不影响其他
                 └─ 返回 ReviewBatchResponse(results[])
```

### 10. 待审核列表链路 — `GET /api/v1/review/pending`

```
HTTP 请求
  │
  └─ ReviewController.getPendingList(page, size, sourceType?)
       │
       └─ ReviewService.getPendingList(page, size, sourceType)
            │
            ├─ IngestTaskMapper.selectPage(wrapper)
            │    ├─ WHERE review_status = 'CANDIDATE'
            │    ├─ [sourceType 非空] AND source_type = ?
            │    └─ ORDER BY created_at DESC
            │
            └─ 转换为 ReviewListResponse
                 └─ toListResponse(): 不读取 MinIO 内容（避免 N+1），contentPreview=null
```

### 11. 审核详情链路 — `GET /api/v1/review/{taskId}`

```
HTTP 请求
  │
  └─ ReviewController.getDetail(taskId)
       │
       └─ ReviewService.getDetail(taskId)
            │
            ├─ IngestTaskMapper.selectById(taskId)
            │    └─ null → HTTP 404
            │
            ├─ readContentPreview(rawObjectKey)
            │    └─ MinioStorageClient.getObject(rawBucket, rawObjectKey)
            │         └─ 截取前 200 字作为摘要
            │         └─ 读取失败 → null（不影响主流程）
            │
            └─ 返回 ReviewDetailResponse(所有字段 + contentPreview)
```

### 12. 文档列表链路 — `GET /api/v1/documents`

```
HTTP 请求
  │
  └─ DocumentController.listDocuments(page, size, status?, knowledgeType?)
       │
       └─ KnowledgeDocumentMapper.selectPage(wrapper)
            ├─ [status 非空] WHERE status = ?
            ├─ [knowledgeType 非空] AND knowledge_type = ?
            └─ ORDER BY updated_at DESC
```

### 13. 文档详情链路 — `GET /api/v1/document/{documentId}`

```
HTTP 请求
  │
  └─ DocumentController.getDetail(documentId)
       │
       └─ DocumentService.getDetail(documentId)
            │
            ├─ KnowledgeDocumentMapper.selectById(documentId)
            │    └─ null → HTTP 404
            │
            └─ toDetailResponse(): 转换为 DocumentDetailResponse
                 └─ DocumentDetailResponse(id, taskId, knowledgeType, title, topic, tagsJson,
                      reviewStatus, status, datasetName, ragflowDocumentId, metadataJson,
                      version, createdAt, updatedAt)
```

### 14. 文档禁用链路 — `POST /api/v1/document/{documentId}/disable`

```
HTTP 请求
  │
  └─ DocumentController.disable(documentId)
       │
       └─ DocumentService.disable(documentId)
            │
            ├─ findDocumentOrThrow(documentId)
            │    └─ KnowledgeDocumentMapper.selectById(documentId)
            │         └─ null → 抛出 BizException("文档不存在")
            │
            ├─ 更新文档状态 → DISABLED
            │
            ├─ [ragflowDocumentId 非空] 调用 RAGFlow 排除文档
            │    └─ RagflowClient.deleteDocument(datasetId, ragflowDocumentId, requestId)
            │         └─ RagflowClientImpl: WebClient.delete() → /api/v1/datasets/{datasetId}/documents/{documentId}
            │
            └─ KnowledgeDocumentMapper.updateById()
```

### 15. 文档启用链路 — `POST /api/v1/document/{documentId}/enable`

```
HTTP 请求
  │
  └─ DocumentController.enable(documentId)
       │
       └─ DocumentService.enable(documentId)
            │
            ├─ findDocumentOrThrow(documentId)
            │
            ├─ 更新文档状态 → COMPLETED
            │
            ├─ [ragflowDocumentId 非空] 调用 RAGFlow 重新加入检索
            │    └─ RagflowClient.updateDocument(UpdateDocumentRequest, requestId)
            │         └─ RagflowClientImpl: WebClient.put() → /api/v1/datasets/{datasetId}/documents/{documentId}
            │
            └─ KnowledgeDocumentMapper.updateById()
```

### 16. 入库重试与孤儿恢复 — 定时任务

```
IngestRetryScheduler.retryFailedTasks()
  │  触发: @Scheduled(fixedDelay = kb.ingest.retry-delay-ms, 默认 5000ms)
  │
  ├─ 1. 标记孤儿任务
  │    └─ markOrphanedTasksFailed()
  │         ├─ 查询: status=PROCESSING 且 updatedAt < (now - orphanTimeoutMs)
  │         │    └─ orphanTimeoutMs 默认 600000ms（10分钟）
  │         └─ 标记为 FAILED + errorMessage="Task timed out and was marked failed for retry"
  │
  └─ 2. 重试失败任务
       ├─ 查询: status=FAILED 且 (retryCount < maxAttempts 或 retryCount=null)
       │    └─ 且 (processedGuideKey 非空 或 processedQaKey 非空)  // 只重试已有处理件的任务
       │    └─ maxAttempts 默认 3
       │
       └─ retrySingleTask(task, maxAttempts)
            ├─ retryCount >= maxAttempts → 跳过
            └─ 标记为 COMPLETED + retryCount+1 + errorMessage=null
                 └─ 失败 → retryCount+1 + errorMessage="补偿重试失败"
```

## 安全模型

### 过滤器链

所有 `/api/*` 请求依次经过：

1. **RateLimitFilter**（order=0）：按客户端 IP 固定窗口限流
   - 窗口大小：60 秒
   - 默认限制：`kb.security.rate-limit-per-minute`（默认 60 次/分钟）
   - 客户端识别：优先 `X-Forwarded-For` 首个 IP，回退到 `remoteAddr`
   - `/actuator` 路径跳过
   - 超限返回 HTTP 429 + ErrorResponse

2. **HmacSignatureFilter**（order=1）：HMAC-SHA256 签名验证
   - 请求体缓存：`CachedBodyHttpServletRequest`（限制 1MB，超出返回 HTTP 413）
   - 验签顺序：先验证签名、再检查时间戳（避免时间窗口探测）

### HMAC 请求头

| Header | 说明 |
|---|---|
| `X-KB-RequestId` | 请求唯一标识 |
| `X-KB-Timestamp` | Unix 毫秒时间戳 |
| `X-KB-Signature` | Base64 编码的 HMAC-SHA256 签名 |

### 签名计算流程

```
SignatureValidator.sign(requestId, timestamp, requestBody):
  1. bodyHash = SHA-256(requestBody) → 十六进制字符串
  2. signatureContent = requestId + timestamp + bodyHash
  3. hmac = HMAC-SHA256(signatureContent, sharedSecret)
  4. signature = Base64(hmac)
```

验证使用 `MessageDigest.isEqual()` 常量时间比对，防止时序攻击。

时间戳容差：`kb.security.timestamp-tolerance-ms`（默认 300000ms = 5 分钟）。

### HMAC 排除路径

以下路径跳过 HMAC 验签（供浏览器管理台直接调用）：

精确匹配：
- `/api/v1/chat`
- `/api/v1/ingest/tasks`
- `/api/v1/documents`
- `/api/v1/review/pending`
- `/api/v1/review/approve`
- `/api/v1/review/reject`
- `/api/v1/review/batch`

前缀匹配：
- `/api/v1/ingest/status/`
- `/api/v1/ingest/manual`
- `/api/v1/document/`
- `/api/v1/review/`

需要 HMAC 的接口：
- `POST /api/v1/query`（机器到机器）
- `POST /api/v1/ingest/candidate`（机器到机器）

## 外部服务客户端

### RagflowClientImpl

- 基于 Spring WebFlux `WebClient`
- BaseURL：`kb.ragflow.base-url`
- 认证：`Authorization: Bearer {kb.ragflow.api-key}`
- 透传：`X-Request-Id` 请求头
- 超时：`kb.ragflow.timeout-ms`（默认 5000ms）
- 重试：`executeWithRetry()` 最多 `kb.ragflow.retry-max-attempts` 次（默认 3），间隔 `kb.ragflow.retry-delay-ms`（默认 1000ms）
- 异常：统一转换为 `ExternalServiceException(serviceName="RAGFlow")`
- 日志：记录请求与响应内容，`summarize()` 仅还原 Unicode 转义，不截断

API 端点：

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/v1/retrieval` | 知识检索 |
| POST | `/api/v1/retrieval/memory` | Memory 检索 |
| POST | `/api/v1/datasets/{datasetId}/documents` | 创建文档 |
| POST | `/api/v1/datasets` | 创建数据集 |
| DELETE | `/api/v1/datasets/{datasetId}/documents/{documentId}` | 删除文档 |
| PUT | `/api/v1/datasets/{datasetId}/documents/{documentId}` | 更新文档 |

### LlmClientImpl

- 基于 Spring WebFlux `WebClient`
- BaseURL：`kb.processor.llm-base-url`
- 认证：`Authorization: Bearer {kb.processor.llm-api-key}`
- 超时：`kb.processor.llm-timeout-ms`（默认 30000ms）
- 不重试（LLM 调用非幂等）
- 异常：`ExternalServiceException(serviceName="LLM")`
- 请求格式：OpenAI 兼容 `POST /v1/chat/completions`
- 响应解析：`choices[0].message.content`

### MinioStorageClient

- 基于 MinIO Java SDK `MinioClient`
- 端点：`kb.minio.endpoint`
- 桶：
  - 原始件桶：`kb.minio.raw-bucket`（默认 `kb-raw`）
  - 处理件桶：`kb.minio.processed-bucket`（默认 `kb-processed`）
- 路径格式：`{prefix}/{yyyy-MM-dd}/{yyyy-MM-dd-HHmmss}-{suffix}.md`
- 异常：`ExternalServiceException(serviceName="MinIO")`

存储路径：

| 方法 | 桶 | 路径格式 |
|---|---|---|
| `putRawObject()` | raw-bucket | `{sourceType}/{date}/{datetime}-raw.md` |
| `putProcessedGuide()` | processed-bucket | `guide/{date}/{topic}-guide.md`（topic 由 LLM 决定，空时回退到 `{datetime}-guide.md`） |
| `putProcessedQa()` | processed-bucket | `qa/{date}/{topic}-qa.md`（topic 由 LLM 决定，空时回退到 `{datetime}-qa.md`） |
| `putFailedObject()` | processed-bucket | `failed/{date}/{datetime}-failed.md` |

## 超时与重试配置一览

系统中涉及多个超时和重试配置，分布在不同层级。以下是完整汇总：

### 外部服务超时

| 服务 | 配置项 | 默认值 | 说明 |
|------|--------|--------|------|
| RAGFlow 检索 | `RAGFLOW_TIMEOUT_MS` | 5000ms (5s) | 单次 HTTP 请求超时，含检索和 Memory 检索 |
| RAGFlow 重试间隔 | `RAGFLOW_RETRY_DELAY_MS` | 1000ms (1s) | 重试之间的等待时间 |
| RAGFlow 最大重试 | `RAGFLOW_RETRY_MAX_ATTEMPTS` | 3 | 幂等操作（检索、文档管理）的最大尝试次数 |
| LLM API | `LLM_TIMEOUT_MS` | 30000ms (30s) | LLM chat completions 请求超时，不重试 |

### 前端超时

| 场景 | 超时值 | 说明 |
|------|--------|------|
| 聊天请求 (`/api/v1/chat`) | 180s (3min) | 包含 RAGFlow 检索 + LLM 生成的完整链路 |
| 其他 API 请求 | 30s | 入库、审核、文档管理等 |

### 安全相关超时

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `KB_SECURITY_TIMESTAMP_TOLERANCE_MS` | 300000ms (5min) | HMAC 签名时间戳容差，超出视为重放攻击 |

### 入库相关超时

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `INGEST_RETRY_DELAY_MS` | 5000ms (5s) | 入库重试定时任务的执行间隔 |
| `INGEST_ORPHAN_TIMEOUT_MS` | 600000ms (10min) | PROCESSING 状态超过此时间的任务视为孤儿，标记为 FAILED 等待重试 |
| `INGEST_RETRY_MAX_ATTEMPTS` | 3 | 入库任务最大重试次数 |

### 超时链路关系

聊天请求的完整超时链路：

```
前端 fetch 超时 (180s)
  └─ 后端 ChatController
       ├─ RAGFlow 检索 (5s × 3次重试 = 最长 15s)
       └─ LLM 生成 (默认 30s，.env 中可配置更长)
```

建议：`前端超时 > LLM 超时 + RAGFlow 最大重试时间`，确保后端有足够时间完成处理。

## 可观测性

### 健康检查

`GET /actuator/health` 包含两个自定义健康指示器：

- `RagflowHealthIndicator`：检查 RAGFlow 服务可达性
- `MinioHealthIndicator`：检查 MinIO 服务可达性

### Micrometer 指标

`KbMetrics` 通过 Micrometer 采集以下指标：

| 指标名 | 类型 | 说明 |
|---|---|---|
| `kb.query.total` | Counter（按 route 标签） | 查询总数 |
| `kb.query.failed` | Counter | 查询失败数 |
| `kb.ragflow.latency` | Timer（P50/P95/P99） | RAGFlow 调用耗时 |
| `kb.query.hit.count` | DistributionSummary | 每次查询命中数分布 |
| `kb.query.top.score` | DistributionSummary | 每次查询 top1 score 分布 |
| `kb.ingest.total` | Counter | 入库任务总数 |
| `kb.ingest.success` | Counter | 入库成功数 |
| `kb.ingest.failed` | Counter | 入库失败数 |
| `kb.llm.rewrite.total` | Counter | LLM 重写总数 |
| `kb.llm.rewrite.success` | Counter | LLM 重写成功数 |
| `kb.quality.check.total` | Counter | 质量校验总数 |
| `kb.quality.check.passed` | Counter | 质量校验通过数 |
| `kb.evidence.truncated` | Counter | 证据包截断次数 |

### 日志

日志配置见 `src/main/resources/logback-spring.xml`，同时输出到控制台和文件：

- 当前日志：`${LOG_PATH}/${LOG_FILE_NAME}.log`（默认 `./logs/knowledge-bridge.log`）
- 错误日志：`${LOG_PATH}/${LOG_FILE_NAME}-error.log`（仅 ERROR 级别）
- 历史归档：`${LOG_PATH}/archive/${LOG_FILE_NAME}-YYYY-MM-DD.N.log.gz`（按天 + 大小滚动，gzip 压缩）

可调参数（`.env` 或环境变量覆盖）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `LOG_PATH` | `./logs` | 日志目录；容器内为 `/app/logs`，已在 `docker-compose.yml` 挂载到宿主机 `./logs` |
| `LOG_FILE_NAME` | `knowledge-bridge` | 文件名基础 |
| `LOG_FILE_MAX_SIZE` | `50MB` | 单文件滚动阈值 |
| `LOG_FILE_MAX_HISTORY` | `30` | 历史日志保留天数 |
| `LOG_FILE_TOTAL_SIZE_CAP` | `2GB` | 归档总大小上限 |
| `LOGGING_LEVEL_KB_BRIDGE` | `INFO` | 业务包日志级别 |
| `LOGGING_LEVEL_KB_BRIDGE_CLIENT` | `DEBUG` | 外部客户端日志级别 |

Docker 部署下查看日志：

```bash
# 宿主机直接查看当日日志
Get-Content -Wait ./logs/knowledge-bridge.log

# 或走 Docker 日志驱动
docker compose logs -f knowledge-bridge
```

## API 概览

| 方法 | 路径 | 说明 | HMAC |
|---|---|---|---|
| `POST` | `/api/v1/query` | 证据包查询（OpenClaw 调用） | 需要 |
| `POST` | `/api/v1/chat` | 管理台聊天（检索 + LLM 回答） | 不需要 |
| `POST` | `/api/v1/ingest/manual` | 手动入库 | 不需要 |
| `POST` | `/api/v1/ingest/candidate` | 自动候选评估（OpenClaw 调用） | 需要 |
| `GET` | `/api/v1/ingest/status/{taskId}` | 入库任务状态查询 | 不需要 |
| `GET` | `/api/v1/ingest/tasks` | 入库任务分页列表 | 不需要 |
| `GET` | `/api/v1/review/pending` | 待审核列表 | 不需要 |
| `GET` | `/api/v1/review/{taskId}` | 审核详情 | 不需要 |
| `POST` | `/api/v1/review/approve` | 审核通过 | 不需要 |
| `POST` | `/api/v1/review/reject` | 审核拒绝 | 不需要 |
| `POST` | `/api/v1/review/batch` | 批量审核 | 不需要 |
| `GET` | `/api/v1/documents` | 知识文档分页列表 | 不需要 |
| `GET` | `/api/v1/document/{documentId}` | 文档详情 | 不需要 |
| `POST` | `/api/v1/document/{documentId}/enable` | 启用文档 | 不需要 |
| `POST` | `/api/v1/document/{documentId}/disable` | 禁用文档 | 不需要 |
| `GET` | `/actuator/health` | 健康检查（含 RAGFlow、MinIO） | 不需要 |
| `GET` | `/actuator/metrics` | Micrometer 指标 | 不需要 |

## 数据模型

### kb_query_log — 查询日志

| 字段 | 说明 |
|---|---|
| `request_id` | 请求唯一标识（幂等键） |
| `user_id` | 用户 ID |
| `chat_id` | 群聊 ID |
| `session_key` | 会话标识 |
| `question` | 用户问题 |
| `route` | 路由结果（QueryRoute 枚举值） |
| `status` | 查询状态（QueryStatus 枚举值） |
| `response_json` | 序列化的 QueryResponse（用于幂等缓存） |
| `retrieval_quality` | 检索置信度（Confidence 枚举值） |
| `source_count` | 返回的证据条数 |
| `top_score` | top1 检索分数 |
| `ragflow_latency_ms` | RAGFlow 调用耗时（毫秒） |
| `error_message` | 错误信息 |

### kb_ingest_task — 入库任务

| 字段 | 说明 |
|---|---|
| `request_id` | 请求唯一标识（幂等键，唯一索引） |
| `source_channel` | 来源渠道 |
| `source_type` | 来源类型（MARKDOWN/TUTORIAL/NOTE/FEISHU_CHAT/ATTACHMENT） |
| `user_id` | 用户 ID |
| `chat_id` | 群聊 ID |
| `message_ids_json` | 关联消息 ID 列表（JSON 数组） |
| `status` | 入库状态（DocumentStatus 枚举值） |
| `review_status` | 审核状态（ReviewStatus 枚举值） |
| `content_hash` | 内容 SHA-256 哈希（去重键） |
| `raw_object_key` | MinIO 原始件路径 |
| `processed_guide_key` | MinIO Guide 处理件路径 |
| `processed_qa_key` | MinIO Q&A 处理件路径 |
| `processor_version` | 处理器版本 |
| `retry_count` | 重试次数 |
| `error_message` | 错误信息 |

### kb_document — 知识文档

| 字段 | 说明 |
|---|---|
| `task_id` | 关联入库任务 ID |
| `knowledge_type` | 知识类型（KnowledgeType: GUIDE/QA） |
| `title` | 文档标题 |
| `topic` | 主题（从内容中提取） |
| `tags_json` | 标签 JSON 数组 |
| `review_status` | 审核状态 |
| `status` | 文档状态（DocumentStatus 枚举值） |
| `dataset_name` | RAGFlow 数据集 ID |
| `ragflow_document_id` | RAGFlow 文档 ID |
| `metadata_json` | 元数据 JSON |
| `version` | 版本号（同 taskId+knowledgeType 下递增） |

### kb_review_task — 审核记录

| 字段 | 说明 |
|---|---|
| `task_id` | 关联入库任务 ID |
| `review_status` | 审核结果（APPROVED/REJECTED） |
| `reviewer` | 审核人 |
| `comment` | 审核评论 |

## 配置说明

配置入口：`src/main/resources/application.yml` + `KbProperties`（`@ConfigurationProperties(prefix = "kb")`）。

### kb.ragflow.*

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `base-url` | `http://localhost:9380` | RAGFlow 服务地址 |
| `api-key` | — | RAGFlow API Key |
| `dataset-id` | — | 默认数据集 ID |
| `timeout-ms` | `5000` | 请求超时（毫秒） |
| `retry-max-attempts` | `3` | 最大重试次数 |
| `retry-delay-ms` | `1000` | 重试间隔（毫秒） |

### kb.query.*

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `route-strategy` | `rule` | 路由策略：`rule` 或 `llm` |
| `rules` | `[]` | 规则列表（含 pattern/route/category/keywords） |
| `default-route` | `KB_PLUS_LLM` | 无匹配规则时的默认路由 |
| `score-threshold` | `0.2` | LLM 路由置信度阈值 / 检索分数阈值 |
| `max-sources` | `5` | 证据包最大条数 |
| `max-content-length` | `2000` | 单条证据最大字符数 |
| `max-total-length` | `8000` | 证据包总字符数上限 |
| `memory-enabled` | `false` | 是否启用 Memory 检索 |
| `dataset-ids` | `[]` | 查询数据集列表（空时回退到 ragflow.dataset-id） |
| `metadata-filter-enabled` | `false` | 是否只检索 APPROVED 状态文档 |

> **审核与检索的关系：**
> - `metadata-filter-enabled=false`（默认）：检索时不过滤审核状态，所有已被 RAGFlow 索引的文档都可返回，无论审核状态是 `CANDIDATE`、`APPROVED` 还是 `REJECTED`。Knowledge Bridge 入库成功（`COMPLETED`）表示处理件已写入 MinIO；实际能否被查询命中，还取决于 RAGFlow 对 MinIO 处理件的轮询和索引是否完成。
> - `metadata-filter-enabled=true`：检索时只返回 `reviewStatus=APPROVED` 的文档。自动候选入库后文档处于 `CANDIDATE` 状态，必须在管理台审核通过后才会出现在检索结果中。适用于需要人工审核把关的场景。

### kb.security.*

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `shared-secret` | — | HMAC 共享密钥 |
| `timestamp-tolerance-ms` | `300000` | 时间戳容差（5 分钟） |
| `signature-algorithm` | `HmacSHA256` | 签名算法 |
| `rate-limit-per-minute` | `60` | 每分钟限流次数（0 表示不限流） |

### kb.minio.*

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `endpoint` | `http://localhost:9000` | MinIO 服务地址 |
| `access-key` | — | MinIO Access Key |
| `secret-key` | — | MinIO Secret Key |
| `raw-bucket` | `kb-raw` | 原始件桶 |
| `processed-bucket` | `kb-processed` | 处理件桶 |

### kb.processor.*

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `llm-model` | `gpt-4o` | LLM 模型 |
| `llm-base-url` | — | LLM 服务地址 |
| `llm-api-key` | — | LLM API Key |
| `llm-timeout-ms` | `30000` | LLM 请求超时（毫秒） |
| `guide-prompt-template` | `classpath:prompts/guide-template.md` | Guide 重写 prompt 模板路径 |
| `qa-prompt-template` | `classpath:prompts/qa-template.md` | Q&A 重写 prompt 模板路径 |
| `processor-version` | `v1` | 处理器版本标识 |
| `min-qa-count` | `3` | 质量校验：教程类最少 Q&A 数 |

### kb.ingest.*

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `async-pool-size` | `4` | 异步处理线程池大小 |
| `retry-max-attempts` | `3` | 最大重试次数 |
| `retry-delay-ms` | `5000` | 重试扫描间隔（毫秒） |
| `orphan-timeout-ms` | `600000` | 孤儿任务超时（10 分钟） |
| `content-hash-algorithm` | `SHA-256` | 内容哈希算法 |

## 环境变量说明

项目通过 `.env` 文件加载配置（参考 `.env.example`）。以下对需要理解业务含义的变量做详细说明，纯连接类变量（地址、用户名、密码）不再赘述。

### 查询路由

| 变量 | 默认值 | 说明 |
|---|---|---|
| `KB_QUERY_ROUTE_STRATEGY` | `rule` | 路由策略。`rule`：基于关键词/子串规则判定；`llm`：调用 LLM 做意图分类，置信度不足时回退到 rule |
| `KB_QUERY_DEFAULT_ROUTE` | `KB_PLUS_LLM` | 无规则命中时的默认路由。`KB_ONLY` 只查知识库；`KB_PLUS_LLM` 知识库优先、LLM 可补充；`LLM_ONLY` 不查知识库 |
| `KB_QUERY_SCORE_THRESHOLD` | `0.2` | 检索分数阈值。LLM 路由策略中也用作置信度阈值，低于此值回退到规则策略 |
| `KB_QUERY_MAX_SOURCES` | `5` | 证据包最大返回条数，超出按 score 降序截断 |
| `KB_QUERY_MAX_CONTENT_LENGTH` | `2000` | 单条证据最大字符数，超出截断并追加 `[...]` |
| `KB_QUERY_MAX_TOTAL_LENGTH` | `8000` | 证据包总字符数上限，超出时从最低分条目开始移除 |
| `KB_QUERY_MEMORY_ENABLED` | `false` | 是否启用 RAGFlow Memory 检索。开启后会额外调用 `/api/v1/retrieval/memory`，结果合并到主检索 |
| `KB_QUERY_DATASET_IDS` | 空 | 查询时使用的数据集 ID 列表（逗号分隔）。为空时回退到 `RAGFLOW_DATASET_ID` |
| `KB_QUERY_METADATA_FILTER_ENABLED` | `false` | 检索时是否只返回审核通过（`APPROVED`）的文档。`false` 时所有入库成功的文档都可被检索，`true` 时必须在管理台审核通过后才能被检索到 |

### LLM / 知识化重写

| 变量 | 默认值 | 说明 |
|---|---|---|
| `LLM_MODEL` | — | LLM 模型名称，传给 OpenAI 兼容接口的 `model` 字段 |
| `LLM_BASE_URL` | — | LLM API 地址，如 `https://api.moonshot.cn`。接口路径固定为 `/v1/chat/completions` |
| `LLM_TIMEOUT_MS` | `30000` | LLM 调用超时（毫秒）。知识化重写和 LLM 路由判定共用此超时。长文档重写建议调大 |
| `PROCESSOR_VERSION` | `v1` | 处理器版本号，写入入库任务记录。更换 prompt 模板时递增，便于追溯 |
| `PROCESSOR_MIN_QA_COUNT` | `3` | 教程类内容（MARKDOWN/TUTORIAL/NOTE）重写后最少 Q&A 条数，不足则质量校验不通过 |

#### 本地 Markdown 批量重写

项目提供独立本地工具 `com.openclaw.kbbridge.tool.LocalMarkdownRewriteTool`，用于只复用 Markdown Guide + Q&A 重写能力，不启动 Web 服务，不访问数据库、MinIO 或 RAGFlow。

默认行为：

- 输入目录：`docs/个人知识库`
- 输出目录：`docs/个人知识库_rewrite`
- 每篇 `AAA.md` 输出 `AAA_guide.md` 和 `AAA_qa.md`
- 保持原目录层级
- 复制所有 `img` 目录到输出目录
- 校验原 Markdown 图片引用是否在两个输出文件中原样保留
- 默认保持最多 100 篇文档处于处理中；完成一篇补充一篇
- 单篇文档超过 10 分钟未完成会记录为超时，不阻塞其他文档
- 默认不覆盖已有输出文件

运行前确认 `.env` 或环境变量中已配置 `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`。

```bash
# 只扫描，不调用 LLM、不写输出
mvn -q -DskipTests compile exec:java \
  "-Dexec.mainClass=com.openclaw.kbbridge.tool.LocalMarkdownRewriteTool" \
  "-Dexec.args=--dry-run"

# 批量重写 docs/个人知识库
mvn -q -DskipTests compile exec:java "-Dexec.mainClass=com.openclaw.kbbridge.tool.LocalMarkdownRewriteTool" "-Dexec.args=--input docs/个人知识库 --output docs/个人知识库_rewrite"

# 先小批量验证 3 篇
mvn -q -DskipTests compile exec:java "-Dexec.mainClass=com.openclaw.kbbridge.tool.LocalMarkdownRewriteTool" "-Dexec.args=--limit 3"
```

运行时会写两类记录：

- `rewrite-submitted.jsonl`：已提交处理的文档
- `rewrite-report.jsonl`：完成、跳过、失败或超时的最终结果

可选参数：`--overwrite` 覆盖已有输出，`--concurrency <n>` 设置同时处理的文档数（默认 100），`--timeout-ms <ms>` 设置单次 LLM 请求超时，`--document-timeout-ms <ms>` 设置单篇文档总超时（默认 600000），`--poll-interval-ms <ms>` 设置轮询间隔，`--keep-front-matter` 保留 LLM 输出的 YAML front matter。

### 入库

| 变量 | 默认值 | 说明 |
|---|---|---|
| `INGEST_ASYNC_POOL_SIZE` | `4` | 异步入库线程池大小。控制同时处理的入库任务数 |
| `INGEST_RETRY_MAX_ATTEMPTS` | `3` | 失败任务最大重试次数 |
| `INGEST_RETRY_DELAY_MS` | `5000` | 重试调度器扫描间隔（毫秒）。不是两次重试之间的间隔，而是调度器每隔多久扫描一次失败任务 |
| `INGEST_ORPHAN_TIMEOUT_MS` | `600000` | 孤儿任务超时（毫秒，默认 10 分钟）。状态为 `PROCESSING` 但超过此时间未更新的任务会被标记为 `FAILED` 以便重试 |

### 安全

| 变量 | 默认值 | 说明 |
|---|---|---|
| `KB_SHARED_SECRET` | — | HMAC 共享密钥。OpenClaw 与 Knowledge Bridge 必须配置相同的值，用于机器到机器接口（`/api/v1/query` 和 `/api/v1/ingest/candidate`）的签名验证 |
| `KB_SECURITY_TIMESTAMP_TOLERANCE_MS` | `300000` | 时间戳容差（毫秒，默认 5 分钟）。请求时间戳与服务器时间差超过此值则拒绝，防止重放攻击 |
| `KB_SECURITY_RATE_LIMIT_PER_MINUTE` | `60` | 每个客户端 IP 每分钟最大请求数。超限返回 HTTP 429。设为 0 不限流 |

### 服务器

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | `8111` | 应用监听端口 |
| `SERVER_ADDRESS` | `0.0.0.0` | 绑定地址。`0.0.0.0` 允许所有网络接口访问，`127.0.0.1` 仅本机访问。配合防火墙控制公网暴露 |

## 异常处理

### 异常类型

| 异常类 | 场景 | HTTP 状态码 |
|---|---|---|
| `ValidationException` | 参数校验失败（sourceType 不合法等） | 400 |
| `MethodArgumentNotValidException` | Bean Validation 校验失败（@NotBlank 等） | 400 |
| `HttpMessageNotReadableException` | 请求体解析失败（JSON 格式错误等） | 400 |
| `DuplicateContentException` | 内容重复 | 409 |
| `BizException` | 业务异常（任务不存在、审核状态不允许等） | 500 |
| `ExternalServiceException` | 外部服务调用失败（RAGFlow/LLM/MinIO） | 502/500 |

### GlobalExceptionHandler

统一异常处理器，返回标准 `ErrorResponse(requestId, status, error, message, timestamp)` 格式。

## 管理台页面

| 路由 | 页面 | 功能 |
|---|---|---|
| `/dashboard` | Dashboard | Actuator 健康状态与核心指标概览 |
| `/chat` | Chat | 知识库问答与手动入库模式切换 |
| `/ingest` | Ingest | 入库任务列表、状态过滤、详情查看 |
| `/review` | Review | 待审核任务列表、单条与批量审核；当前后端只提供 `CANDIDATE` 待审核列表接口 |
| `/documents` | Documents | 知识文档列表、启用/禁用、详情查看 |

SPA 回退由 `SpaFallbackController` 处理，所有非 API、非静态资源请求转发到 `index.html`。

聊天记录保存在浏览器 `sessionStorage`，关闭标签页后清除。

## 快速开始

### 1. 环境要求

- JDK 25
- Maven 3.9+
- Node.js 20+ 与 npm
- MySQL 8.0+
- MinIO
- RAGFlow
- OpenAI 兼容 LLM 服务

### 2. 初始化数据库

```sql
source docs/schema.sql;
```

`src/main/resources/db/migration/` 下也保留了按表拆分的脚本。当前未引入 Flyway 或 Liquibase，需手动导入。

### 3. 配置环境变量

```bash
cp .env.example .env
```

应用启动时读取项目根目录 `.env`。系统环境变量和命令行参数优先级高于 `.env`。

必填配置：

| 变量 | 说明 |
|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | MySQL 连接 |
| `RAGFLOW_BASE_URL` / `RAGFLOW_API_KEY` / `RAGFLOW_DATASET_ID` | RAGFlow 连接与入库数据集 |
| `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | MinIO 连接 |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | OpenAI 兼容 LLM |
| `KB_SHARED_SECRET` | HMAC 共享密钥 |
| `KB_QUERY_ROUTE_STRATEGY` | `rule` 或 `llm` |
| `KB_QUERY_DATASET_IDS` | 查询数据集列表（空值时回退到 `RAGFLOW_DATASET_ID`） |
| `KB_QUERY_METADATA_FILTER_ENABLED` | 是否只检索 APPROVED 文档 |

### 4. 启动后端

```bash
mvn spring-boot:run -Dfile.encoding=UTF-8
```

默认端口：`8111`。管理台访问：`http://localhost:8111/dashboard`

### 5. 前端开发模式

```bash
cd web
npm install
npm run dev
```

Vite 开发代理应指向后端默认地址 `http://localhost:8111`。

前端构建：

```bash
cd web
npm run build
```

构建产物输出到 `web/dist/`，需同步到 `src/main/resources/static/` 供 Spring Boot 生产服务提供。

## 部署

项目提供两种部署方式：**Docker Compose**（推荐，生产环境）和 **可执行 JAR**（适合已有 JVM 环境或托管平台）。部署前请确保 MySQL、MinIO、RAGFlow、LLM 服务已就绪，并按照「快速开始 → 3. 配置环境变量」准备好 `.env`。

### 方式一：Docker Compose（推荐）

适用于：自管服务器 / VPS / 本地验证。依赖 Docker 20.10+ 与 Docker Compose v2。

**1. 准备环境变量**

```bash
cp .env.example .env
# 编辑 .env，填入 DB / RAGFlow / MinIO / LLM / HMAC 等配置
```

容器内固定 `LOG_PATH=/app/logs`（在 `docker-compose.yml` 中定义），无需在 `.env` 里配置。

**2. 调整 `docker-compose.yml` 中的代理设置**

默认构建阶段使用 `127.0.0.1:7890` 作为 HTTP 代理（用于在无法直连 Maven Central 时加速依赖拉取）。如果你的环境不需要代理，去掉 `build.args` 和 `JAVA_TOOL_OPTIONS` 中的代理参数：

```yaml
    build:
      context: .
      dockerfile: Dockerfile
      # 删除 network: host 和 args
    environment:
      - TZ=Asia/Shanghai
      - LOG_PATH=/app/logs
      - JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai
```

**3. 构建并启动**

```bash
docker compose up -d --build
```

Dockerfile 是多阶段构建：第一阶段用 `maven:3.9-eclipse-temurin-25` 编译打包，第二阶段用 `eclipse-temurin:25-jre` 运行。首次构建会拉取 Maven 依赖，可能耗时 3-10 分钟。

**4. 验证**

```bash
# 查看容器状态（STATUS 列应为 healthy）
docker compose ps

# 查看启动日志
docker compose logs -f knowledge-bridge

# 健康检查
curl http://localhost:8111/actuator/health
```

管理台：`http://<host>:8111/dashboard`（端口由 `.env` 的 `SERVER_PORT` 控制）。

**5. 日常运维**

```bash
# 重启
docker compose restart knowledge-bridge

# 更新代码后重新构建部署
git pull
docker compose up -d --build

# 停止
docker compose down

# 清理未使用的镜像
docker image prune -f
```

**日志位置**：容器内 `/app/logs` 通过 volume 挂载到宿主机 `./logs`，宿主机可直接查看：

```bash
# Windows PowerShell
Get-Content -Wait ./logs/knowledge-bridge.log

# Linux / macOS
tail -f ./logs/knowledge-bridge.log
```

**网络模式说明**：`docker-compose.yml` 使用 `network_mode: host`，容器直接复用宿主机网络栈，便于访问同机部署的 MySQL / MinIO / RAGFlow（可通过 `127.0.0.1` 连接）。如果依赖服务在其他主机或容器网络中，可改成默认 bridge 网络，并把 `.env` 中的 `127.0.0.1` 改成对应主机名或 IP。

### 方式二：可执行 JAR

适用于：已有 JDK 25 环境的服务器，或需要托管到 systemd / supervisor / Kubernetes 的场景。

**1. 打包**

```bash
mvn clean package -DskipTests -Dfile.encoding=UTF-8
```

产物：`target/knowledge-bridge-<version>.jar`。

**2. 准备运行目录**

```bash
mkdir -p /opt/knowledge-bridge/logs
cp target/knowledge-bridge-*.jar /opt/knowledge-bridge/app.jar
cp .env /opt/knowledge-bridge/.env
```

**3. 启动**

```bash
cd /opt/knowledge-bridge
java -Dfile.encoding=UTF-8 \
     -Duser.timezone=Asia/Shanghai \
     --enable-native-access=ALL-UNNAMED \
     -jar app.jar
```

应用会自动读取同目录的 `.env`。日志默认落在 `./logs/`（可通过 `LOG_PATH` 环境变量覆盖到绝对路径，例如 `LOG_PATH=/var/log/knowledge-bridge`）。

**4. systemd 托管（Linux）**

创建 `/etc/systemd/system/knowledge-bridge.service`：

```ini
[Unit]
Description=Knowledge Bridge
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/knowledge-bridge
Environment=LOG_PATH=/var/log/knowledge-bridge
ExecStart=/usr/bin/java -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai --enable-native-access=ALL-UNNAMED -jar app.jar
Restart=on-failure
RestartSec=10
User=knowledge-bridge

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now knowledge-bridge
sudo systemctl status knowledge-bridge
```

### 升级与回滚

- **升级**：保留旧的 `.env` 和 `logs/`，替换 JAR 或重新 `docker compose up -d --build` 即可
- **回滚**：Docker 部署可保留旧镜像 tag（`docker tag knowledge-bridge:latest knowledge-bridge:backup`），JAR 部署可保留上一版本 JAR 文件
- **数据库迁移**：当前未引入 Flyway / Liquibase，如 `docs/schema.sql` 有增量变更，需手动执行差异 SQL

### 部署检查清单

- [ ] `.env` 中的密钥已填写，且不在版本控制内
- [ ] MySQL 已执行 `docs/schema.sql`
- [ ] RAGFlow 已创建数据集，`RAGFLOW_DATASET_ID` 已配置
- [ ] MinIO 的 `kb-raw` 和 `kb-processed` bucket 已预先创建并可访问
- [ ] `/actuator/health` 返回 `UP`，包含 RAGFlow / MinIO 子检查
- [ ] 日志目录可写，宿主机 `./logs/knowledge-bridge.log` 有内容产出
- [ ] `SERVER_ADDRESS` 与防火墙策略一致，避免公网直接暴露管理台

## 测试

后端：

```bash
mvn test -Dfile.encoding=UTF-8
```

前端：

```bash
cd web
npm test
```

覆盖范围：控制器、服务、路由、处理器、异常处理、安全过滤器、健康检查、属性测试以及前端页面和 API client。

## 与规格文档的关系

`.kiro/specs/` 记录了项目演进过程中的规格文档：

- `knowledge-bridge/`：核心服务需求、设计与实施计划
- `web-management-console/`：Web 管理台需求、设计与实施计划
- `defect-fixes-batch/`：已确认缺陷的修复设计与任务列表
- `remove-ragflow-upload/`：移除 RAGFlow 上传的设计与任务列表

README 按当前代码的真实结构和行为整理。需要追溯设计决策或验收标准时，查看 `.kiro/specs/`。

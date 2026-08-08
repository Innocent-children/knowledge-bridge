## Context

现有手工入库请求以非空文本为输入，附件仅作为元数据传入；worker 的原始内容只存在于异步方法参数，原始对象也按文本写入。处理完成后只生成本地文档记录，并未调用 KBVector。文件入库需要在创建异步任务前持久化原件，并将向量索引纳入任务完成语义。

## Goals / Non-Goals

**Goals:**

- 提供流式、幂等、可恢复的单文件入库入口。
- 将受支持文件抽取为文本并复用现有处理器和质量检查。
- 通过 KBVector JSON chunks API 完成向量发布并保存外部标识。
- 提供可诊断的处理阶段和失败信息。

**Non-Goals:**

- 首版不支持 OCR、批量文件、XLSX/PPTX 结构化还原和人工审核编排。
- 不使用 KBVector 的原始文件上传接口或 MinIO 扫描器。
- 不改变现有文字手工入库接口的请求契约。

## Decisions

1. 新增 `/api/v1/ingest/file` multipart 入口。元数据包含 requestId、用户/会话/消息、文件名、MIME 和 force；服务流式读取文件、限制大小、计算 SHA-256，并核验签名元数据中的内容哈希。
2. 原件先写 MinIO，再持久化任务。对象键使用 taskId 和净化后的文件名；任务表保存 rawObjectKey、文件属性和哈希。worker 仅接 taskId，从持久化输入恢复，避免进程重启丢失方法参数。
3. 首版抽取 TXT、Markdown、CSV、JSON、PDF 和 DOCX。文本格式按 UTF-8 严格解码；PDF/DOCX 使用集中式 Java 抽取器。空结果和不支持格式在 PROCESSING 阶段失败。OCR 与复杂 Office 格式延期。
4. 复用 `AttachmentProcessor` 处理已抽取文本。处理结果按现有知识类型生成一个或多个文档，每个结果被切成受限大小的非空 chunk。
5. 用正确的 KBVector DTO 发布 `{title,chunks:[{content,metadata_json}]}`。幂等 key 为 `taskId:knowledgeType:processorVersion`，metadata 包含飞书来源、文件哈希和 MinIO 键。
6. 状态为 `RECEIVED → RAW_STORED → PROCESSING → INDEXING → COMPLETED/FAILED`。只有所有预期 KBVector 文档成功后才 COMPLETED；保存 documentId 和 jobId 供查询及生命周期操作。
7. KBVector create 调用不做非幂等盲重试；在对端幂等契约部署后可按相同 key 安全重放。客户端超时应覆盖同步 embedding 的正常耗时。
8. 启用/禁用分别对齐 KBVector `/enable` 与 `/disable`，DELETE 只表示永久删除，避免接通外部文档后误删。

## Risks / Trade-offs

- [数据库事务无法覆盖 MinIO] → 对象键确定化，任务插入失败时尽力清理，并提供孤儿对象保留策略。
- [大文件导致内存压力] → 上传和 MinIO 写入流式处理；解析阶段限制 30 MB，并避免在 Controller 中持有完整字节数组。
- [PDF/DOCX 抽取质量不稳定] → 记录抽取字符数，空结果失败，原件保留以便诊断。
- [KBVector 同步索引超时] → 使用幂等 key，记录 INDEXING 阶段并允许按同 key 恢复。
- [旧任务缺少文件字段] → 新字段可空，文字入库继续走现有输入路径。

## Migration Plan

1. 增加向后兼容的数据库字段、INDEXING 状态和配置。
2. 部署支持幂等 key 的 KBVector。
3. 部署文件接口和新 worker 路径，验证文字入库回归。
4. 最后启用插件文件命令。回滚时停止文件入口，保留原件和任务记录供人工恢复。

## Open Questions

无。dataset 继续由服务配置选择，首版不允许飞书用户动态指定任意 dataset。

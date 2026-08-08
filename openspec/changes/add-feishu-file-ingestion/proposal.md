## Why

当前手工入库接口只接受非空文本，附件字段只是未消费的元数据；异步任务也没有可恢复的原文件输入，更没有真正调用 KBVector 创建文档。要支持飞书文件入库，需要一条持久、可追踪且以向量索引成功为完成条件的文件处理链路。

## What Changes

- 新增经过认证的 multipart 文件入库接口，流式保存原件并创建幂等任务。
- 持久化文件名、类型、大小、哈希和 MinIO 原件键，worker 仅凭 taskId 恢复处理。
- 抽取首版支持文件的文本，复用现有知识加工和质量检查流程。
- 按 KBVector 的 `title + chunks` 契约提交处理结果，保存外部 document/job 标识。
- 增加 INDEXING 阶段，只有 KBVector 索引成功后才将任务标记为 COMPLETED。
- 对齐 KBVector 创建、启用和禁用契约，并移除创建接口的非幂等盲重试。

## Capabilities

### New Capabilities

- `durable-file-ingestion`: 定义文件接收、原件持久化、文本抽取、任务恢复和状态查询行为。
- `kbvector-document-publishing`: 定义处理结果向 KBVector 发布、幂等标识和完成语义。

### Modified Capabilities

无。

## Impact

- 影响入库 Controller、Service、异步 worker、MinIO 客户端、任务/文档模型和数据库迁移。
- 新增文件抽取依赖与配置，并修改 KBVector 客户端 DTO、超时及重试策略。
- 对外新增 multipart API；现有文字入库 API 保持兼容。

# 在 knowledge-bridge 中开发

bridge 负责内容处理、审核、发布元数据与对象文件，通过 KBVector 发布和检索统一知识。博客持有笔记及 BLOG 发布意图，OpenClaw 按现有接口提交内容；本仓 `web/` 提供管理台。部署、认证配对和数据库初始化见 [README.md](README.md)。

## 找到当前执行路径

后端根包为 `src/main/java/com/openclaw/kbbridge/`。统一知识路径是当前新内容的主流程，旧类名中的 RAGFlow 不代表所有请求仍由 RAGFlow 执行。

| 需求 | 主要入口 |
| --- | --- |
| 博客重写、发布、撤回、状态、检索 | `unified/UnifiedController`、`UnifiedKnowledgeService` |
| 统一任务、SQL、对象路径和 KBVector 调用 | `unified/UnifiedWorker`、`UnifiedRepository`、`UnifiedPaths`、`UnifiedObjectStore`、`UnifiedVectorClient` |
| OpenClaw 手动、候选、文件输入 | `controller/IngestController`、`FileIngestController`、`service/CandidateEvalService`；跟进到 unified 的兼容入口 |
| 审核、文档启停、查询与对话 | `service/ReviewService`、`DocumentService`、`QueryService` 及对应 Controller |
| 内容提取、导读问答、质量检查、查询路由 | `service/FileTextExtractor`、`processor/`、`router/`、[提示词](src/main/resources/prompts/) |
| HMAC、博客令牌与访问例外 | `security/`、`config/SecurityConfig`；文件令牌校验在 `FileIngestController` |
| 历史外部适配与配置 | `client/`、`config/KbProperties`、`DotenvPropertyLoader`、[application.yml](src/main/resources/application.yml) |
| 管理台 | `web/src/api/client.ts`、`web/src/types/`、`web/src/pages/`；打包资源在 `src/main/resources/static/` |

## 修改约束

- 博客的发布序号由 notes-blog 产生；BLOG 文档在管理台只读。改变接口时同步核对博客 `integration/ServiceClient`、`job/IntegrationWorker` 和 KBVector `api/bridge.py`，保持 ID、修订、哈希与状态含义一致。
- 统一文件只放在私有 `kb-content/documents/{documentId}/` 下。`UnifiedPaths` 决定原文、派生稿和发布路径；已登记对象内容固定，同路径重试必须核对相同字节。博客附件属于该文档，但普通资产清理由博客负责。
- 发布准备完成后才启用索引并确认有效版本。保存草稿与预览不能改变有效发布；GUIDE_QA 发布复用已确认的预览，不能重试时重新生成不同内容。检索需根据 bridge 当前有效发布再过滤 KBVector 命中。
- 任务写回、对象写入及版本启用要保留发布序号和租约检查。撤回、删除与迟到任务并发时，删除状态和清理任务必须阻止内容重新可见。等待索引和清理失败应能继续重试。
- OpenClaw 兼容接口保留既有字段和认证语义。HMAC 的签名内容为 `requestId + timestamp + hex(SHA256(UTF-8请求体))`，使用共享密钥做 HMAC-SHA256 后 Base64 编码；timestamp 为 Unix 毫秒字符串。博客独立令牌、文件上传令牌和管理台的 HMAC 例外应分别核对。
- 新内容进入统一 `kb-unified`；历史 RAGFlow 查询只追加显式允许的数据集。不要通过兼容接口或历史结果绕过统一版本的可见性校验。
- 表结构变更新增 [迁移 SQL](src/main/resources/db/migration/)，本仓需手工应用，不能把目录名当作自动迁移已生效的证据。保留已应用版本，说明应用顺序及对已有数据的影响。
- 页面修改以 `web/` 源码为准，构建后同步静态资源再打包；不直接修改压缩后的 JS/CSS。凭据只在运行环境配置，配置点变化同步 `.env.example` 与 README。

## 验证入口

使用 JDK 25。按修改范围执行 `mvn test -Dfile.encoding=UTF-8`，后端打包用 `mvn -DskipTests package -Dfile.encoding=UTF-8`。统一发布与重试重点查看 [unified 测试](src/test/java/com/openclaw/kbbridge/unified/)，认证查看 `security` 测试；旧兼容流程在 `preservation` 等测试目录中有覆盖。

管理台修改运行 `pnpm --dir web test`、`pnpm --dir web lint`、`pnpm --dir web build`。联调前核对开发代理 `8080` 与后端运行端口；部署包使用已有静态资源，Maven 不替你生成新管理台。

测试与 SQL 验证使用替身或隔离合成数据库，不指向业务实例。协议和发布行为改动再按 README 验证真实链路；单元测试通过不能证明外部依赖可用。纯文档检查事实、链接和 `git diff --check`，保留已有 Git 改动并说明实际执行的验证范围。

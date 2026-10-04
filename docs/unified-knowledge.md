# 单人知识库统一接入

## 链路与职责

`OpenClaw → 原 kb-bridge-plugin → knowledge-bridge → KBVector` 和 `博客前端 → 博客后端（自己的 MySQL） → knowledge-bridge → KBVector` 共用一个固定 KBVector 数据集 `kb-unified`。插件无需修改。博客保存笔记、修订、附件和发布待办；bridge 保存逻辑文档、处理任务和真正生效的版本；KBVector 只接收最终文本并建立索引。没有每用户数据集、空间绑定表或 MQ。

统一知识流程始终启用，无需额外配置总开关；独立的任务 worker 配置保持不变。

只有博客管理员登录，没有自助注册。由用户自行配置管理员账号与密码哈希；保留密码哈希、会话、CSRF、登录限流。博客内部使用 `BLOG_BRIDGE_TOKEN`，bridge 到 KBVector 使用独立 `KB_BRIDGE_SERVICE_TOKEN`。原插件使用既有 `KB_SHARED_SECRET` HMAC 和文件 token 行为，查询、手工入库、文件入库、候选审核、状态通知继续可用。两入口均可检索新统一知识；博客将 OPENCLAW 命中展示为可读来源而不是可编辑笔记。

## 一个桶与一套目录

私有桶固定 `kb-content`，没有 `v1` 或入口前缀：

```text
documents/7f377cc0-e43e-445a-b921-91c8930b1c81/
  source/3/source.md
  source/3/original.pdf                 # OpenClaw 文件入库可选
  assets/10e440b3-2264-462d-936d-969cda58f21b/image.png
  derived/3/85a23b55-8a87-494d-b976-b74fcd8a2c68/guide.md
  derived/3/85a23b55-8a87-494d-b976-b74fcd8a2c68/qa.md
  releases/9e5ae1ce-f0fa-4900-9df8-d56673b38613/document.md
```

物理文件是不可改写的某次输出；逻辑文档 ID 在编辑前后不变；检索版本 ID 表示某次确认入库。文件名不承担权限或版本状态。博客写 assets，bridge 写 source、derived、releases；bridge 永久删除时清理整篇目录。博客处理并发晚到附件的精确删除待办。KBVector 不扫描这个桶，也不重复索引原文和派生件。

原文模式将完整原文复制为最终 document.md；重写模式先生成 guide/qa 供预览，再由明确确认把两者合为一个最终 document.md。图片使用已登记的 attachment://UUID 引用，不由 KBVector 网络抓取图片。BLOG/OPENCLAW 只存元数据，不能从请求中任意指定路径或最终输出内容。

## 发布与可靠性

保存草稿或导入只写博客数据库。发布、撤回、删除每次递增操作序号；比当前序号旧的异步结果不生效。新版本先禁用索引并准备完成，再启用并事务切换有效指针；旧版本在新版本成功前仍可查询。切换后异步精确删除旧索引。撤回先清除有效指针，查询再次核验；永久删除还留下逻辑文档墓碑，阻止晚任务恢复。

任务复用既有 kb_ingest_task 表，租约、序号和持久内容处理重复请求与进程中断。生成输出先写入任务再上传，重试复用相同字节。失去租约的旧 worker 直接退出，不能删除新 worker 使用的同一版本。对象写入与永久清理使用同一逻辑文档行锁，避免清理完成后晚写。清理失败持续退避重试；KBVector INDEXING 等待不消耗终止重试预算。KBVector 对删除的版本永久留墓碑，即使先删除后 prepare，也不能复活。

原控制台禁用 OpenClaw 文档即撤回，再启用从已存原文创建新的检索版本。博客来源在控制台只读，发布/撤回由博客操作，避免两套数据库分别增加序号而冲突。手工入库保持 force 和去重；去重只对仍在发布或有效状态的记录成立。候选待审核前不会进入检索。最终文本最大 200 万字符，原文最大 2 MiB。

## 博客内部 HTTP 合同

所有 /api/v1/blog 请求需 `Authorization: Bearer <BLOG_BRIDGE_TOKEN>`，不传 userId/spaceId：

| 接口 | 请求与结果 |
|---|---|
| POST /rewrite | noteId, sourceRevNo, rewriteJobId, sourceSha256, sourceMarkdown, allowedAttachmentIds → guideMd/qaMd、固定 key 与 hash；不入库 |
| POST /documents/{id}/publish | publishSeq, publicationId, sourceRevNo, contentType SOURCE/GUIDE_QA, rewriteJobId 可选, sourceSha256, sourceMarkdown, allowedAttachmentIds → accepted, publishSeq, publicationId, status QUEUED/INDEXING/EFFECTIVE |
| GET /documents/{id}/status | 当前序号、有效 publication/revision、各版本状态/key/hash、cleanupComplete |
| POST /documents/{id}/withdraw | publishSeq, delete → WITHDRAWN/DELETED 和 cleanupComplete；未入库草稿也可清理 |
| POST /query | query, limit（1–50）→有效 items，含 source、documentId、publicationId、revision、content、key、hash、score |

GUIDE_QA 发布必须引用同一文档、原文修订和 SHA 的已完成预览。修改原文后旧预览不能确认。内部请求重试必须保持 operation ID 与原内容一致，变更内容返回 409。

KBVector 专用 API 为 POST /api/v1/bridge/versions、GET /versions/{releaseId}、POST /versions/{releaseId}/enable、DELETE /versions/{releaseId}?documentId=...、POST /query。只使用 documentId/releaseId，没有 spaces/provision。prepare 的 SHA 对完整最终 UTF-8 文本计算；READY 前不能启用。

## 配置与落地顺序

1. 保持历史 MinIO 对象不动，不迁移、不扫描。由部署者准备私有 kb-content 桶并授予相应应用需要的读写权限。使用现有 MySQL/MinIO/KBVector/模型服务，不把这些服务加进博客 Docker Compose。
2. KBVector 执行其新增 managed 标记迁移，配置 KB_BRIDGE_SERVICE_TOKEN，部署专用固定数据集接口。原独立数据集能力保留。
3. bridge 的数据库没有 Flyway。先核对 V1–V5 已应用，再对 bridge 自己的数据库执行一次 V6__unified_knowledge.sql；新装按 V1–V6 顺序。若已有 retry_count 列，删除 V6 中这一项 ADD 后执行其余新增项。没有 SQL 删除/改写历史内容；不要反复执行增量 ALTER。
4. bridge 配置 KB_VECTOR_URL、KB_BRIDGE_SERVICE_TOKEN、BLOG_BRIDGE_TOKEN 与现有 DB/MINIO/LLM 配置。kb.unified.bucket 固定 kb-content；工作队列默认每秒轮询，lease 180 秒，普通集成失败最多 8 次，清理和 INDEXING 等待持续重试。
5. 博客后端应用自己的正向迁移，配置管理员账号/密码哈希、外部 DB/MinIO 和 bridge URL/token。前端同源 /api 转发到后端。HTTPS 使用现有反向代理或挂载用户已有证书，不生成或替换证书。
6. 原插件配置不增加任何身份参数，按原请求合同验收查询、手工入库、文件入库、状态通知。测试新博客发布→两入口查询→修改切换→撤回→永久删除及依赖故障后恢复。

本轮仅修改普通分支，不提交、不部署、不访问现有服务器/数据库/MinIO，也不执行上述迁移。自动测试使用明确的本地替身或隔离合成数据库。实际外部 MinIO、MySQL、Milvus、模型服务及容器/TLS 运行仍需部署环境验收。

## 本轮本地验证记录

- bridge 离线完整 package：现存 51 个测试类、375 项通过；之后发布职责/历史启用的最终定向 package：16 项事务状态与 Servlet HTTP、2 项真实 loopback KBVector HTTP、13 项详情服务测试全部通过。最终 JAR 不含已移除 notes/身份 controller/helper。
- V1–V6 在随机 loopback 端口的独立 MySQL 8.4 空数据库执行通过；V1–V5 插入的合成旧记录在 V6 后保持原值。测试数据库已删除，没有执行现有数据库迁移。
- bridge 控制台 TypeScript + Vite 构建通过，文档与审核页面 22 项测试通过；ESLint 仍有两项原已存在的同步 effect setState 问题。
- 其他三项目各自的部署说明和测试记录以其仓库为准。原插件恢复后与本轮起点内容一致、工作区干净，原 check/build/4 项回归通过。
- Docker daemon 不可用，所以容器镜像构建、真实 Nginx/TLS 和完整浏览器端到端未执行。自动测试不证明现有 MinIO/MySQL/Milvus/LLM 的配置及网络权限已经就绪。

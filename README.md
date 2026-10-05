# knowledge-bridge

本服务负责 Markdown 知识处理：调用 LLM 生成导读问答，保存固定处理结果，将确认的版本交给 KBVector，并管理处理作业的重试、版本启用和索引清理。所有可编辑原文、修订、附件与回收站都由 notes-blog 保存。本服务没有文档编辑数据库或管理页面。

## 当前调用流程

notes-blog 通过带服务令牌的 `/internal/rewrite` 请求预览，通过 `/internal/documents/{id}/publish` 确认入库。预览绑定原文修订及 SHA-256，发布复用已确认的预览，重试不会重新生成另一份内容。改写提交后立即返回作业状态，`PreviewService` 在后台依次流式生成导读和问答，300 毫秒保存一次进度。notes-blog 拉取进度并通过 SSE 展示给网页；完成的导读作为检查点保留，问答失败重试时不重新生成导读。任务通过租约恢复，最多自动尝试三次；最终失败可由网页重试同一个 ID。未完整结束、类型错误、损坏代码块或越界附件引用不能成为可入库结果。发布任务登记到 MySQL 后返回，后台准备 KBVector 索引，准备完成才启用并记录有效版本。旧有效版本在准备期间仍保留。

撤回与删除通过 `/internal/documents/{id}/withdraw` 提交。撤回保留原文和处理文件以供查看；永久删除清理本服务的文件及 KBVector 索引。本服务不删除 notes-blog 的附件桶。

## OpenClaw 插件接口

插件源码与配置方式不变，保留这些实际使用的入口：

| 接口 | 行为 |
| --- | --- |
| `POST /api/v1/ingest/manual` | 转交 notes-blog 保存原文、改写并自动入库 |
| `POST /api/v1/ingest/file` | 只接受 2 MiB 以内的 UTF-8 `.md`、`.markdown`，转为相同原文提交 |
| `POST /api/v1/ingest/candidate` | LLM 判断是否值得保存，合格内容保存原文、改写并自动入库 |
| `GET /api/v1/ingest/status/{taskId}` | 返回 notes-blog 提交记录与入库进度 |
| `POST /api/v1/query` | 查询 notes-blog 过滤后的有效知识，给插件返回 sources 与 instructions；最终回答仍由 OpenClaw 生成 |

查询及候选接口沿用 `X-KB-RequestId`、`X-KB-Timestamp`、`X-KB-Signature`。签名内容是 `requestId + timestamp + hex(SHA256(UTF-8请求体))`，使用 `KB_SHARED_SECRET` 做 HMAC-SHA256 后 Base64 编码；timestamp 为 Unix 毫秒，允许五分钟偏差。Markdown 文件接口用同一密钥作为 `X-KB-File-Token`。手动提交和状态接口沿用当前插件的无签名调用方式。

查询始终保留原问题，可用 2 秒以内的小模型请求生成最多两个等价问题，失败时使用原问题并返回提示。KBVector 负责并行向量召回、BM25、融合及重排，bridge 不重复实现排序。内部查询调用 KBVector，再核对处理服务的有效版本；notes-blog 最后核对文档是否仍在正常列表及有效入库状态，所以回收站内容会立即从网页和插件检索中消失。

`#kb` 或 `flags.strictKbOnly=true` 始终返回 `KB_ONLY`，没有命中时也要求 OpenClaw 说明资料不足，禁止用模型知识补写。普通查询允许标明来源的补充。传给插件的每个来源默认最多 3000 字、总共最多 10000 字，截断会标记；融合分数不作为绝对置信度。入口按来源 IP 分别限制提交与查询频率，后台生成并发默认 2，参数见 `.env.example`。

## 数据与 MinIO

MySQL `kb_bridge` 只有处理文档状态、改写预览记录与发布作业三张表，完整定义在 [db/schema.sql](db/schema.sql)。它们记录固定处理输入及结果，不承担可编辑原文管理。

本服务创建私有桶 `knowledge-releases`，可通过 `RELEASES_BUCKET` 改名。导读问答在 `documents/{id}/derived/{revision}/{rewriteId}/`，入库文件在 `documents/{id}/releases/{publicationId}/document.md`。同一结果路径内容固定。可编辑原文在 notes-blog 的 MySQL，附件在 notes-blog 的私有桶。

## Docker 部署

四个应用独立使用 Docker 部署，MySQL、Milvus、MinIO 由服务器单独提供。所有应用间调用经过 `host.docker.internal`，Compose 的 `host-gateway` 将它映射到 Docker 主机网关。浏览器直接使用 HTTP IP 地址，端口默认如下：

| 应用 | HTTP 端口 |
| --- | --- |
| notes-blog-web | 9301 |
| notes-blog | 8311 |
| knowledge-bridge | 8111 |
| KBVector | 8700 |

准备外部 MySQL、MinIO、KBVector 和 notes-blog，复制并填写 [.env.example](.env.example)，在根目录运行：

```bash
./deploy.sh
```

脚本用临时 MySQL 客户端容器创建 `kb_bridge` 数据库及当前表，然后构建、启动应用并等待健康检查。它不创建外部依赖，也不清空现有数据。独立环境文件可以作为第一个参数传入。健康检查是 `/health`，只检查应用与数据库，实际处理还需 LLM、MinIO 和 KBVector 正常。

`KB_SHARED_SECRET` 必须与已有插件保持一致。应用间令牌 `NOTES_SERVICE_TOKEN`、`BRIDGE_SERVICE_TOKEN`、`VECTOR_SERVICE_TOKEN` 分别在对应两端配对。LLM 配置使用 OpenAI 兼容服务根地址（不含 `/v1`）、模型和 API Key，导读、问答使用标准 Chat Completions 流式输出 Markdown；候选判断和查询改写要求严格 JSON。提示词分别放在 `src/main/resources/prompts/`，可以直接调整。所有配置说明见示例文件。

## 验证

使用 JDK 25，运行 `mvn test package`。真实流程由 notes-blog 与 notes-blog-web 的隔离验证覆盖：手动提交、Markdown 上传、候选自动入库、改写预览、版本启用、查询、撤回、回收站和永久清理。处理进度通过结构化状态接口及容器日志查看：`docker compose logs -f app`。

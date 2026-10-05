# knowledge-bridge：知识处理与发布服务

本服务把博客笔记和 OpenClaw 提交的内容整理成可检索的知识。它保存原文、生成导读与问答、安排审核、管理发布版本，并把最终文本交给 KBVector 建立索引；查询时返回当前有效知识及其来源。仓内 `web/` 是处理任务、审核、文档和对话的管理台。

博客用户先在 `notes-blog-web` 操作，由 `notes-blog` 保存草稿并向这里确认发布或撤回。OpenClaw 通过查询、手动入库、候选评估和文件上传接口提交内容。两个来源的统一知识共用 KBVector 的 `kb-unified` 数据集；bridge 掌握文档来源、有效发布和对象文件，KBVector 掌握分块、向量与索引。主查询可按显式配置的数据集追加 RAGFlow 检索结果。

## 从输入到有效知识

博客可以提交原文发布，或先请求导读问答预览，再确认使用这份预览发布。OpenClaw 文本和文件进入处理任务；候选内容处理完成后等待审核，通过后才发布。发布文件固定保存，KBVector 索引准备就绪后启用版本，bridge 再记录有效发布。准备新发布时，当前有效版本仍可检索。

撤回先取消有效发布，再清理索引；永久删除还清理该文档的对象文件。发布、撤回、清理依靠数据库中的任务、操作序号和租约恢复与重试。博客内容由博客维护发布顺序，管理台只读查看 BLOG 文档；OPENCLAW 文档可在管理台审核及启停。

| 数据 | 保存与使用方式 |
| --- | --- |
| 原始提交、任务、审核、查询日志、逻辑文档和发布元数据 | bridge 的 MySQL 数据库 `kb_bridge` |
| 统一知识文件 | 私有 MinIO 桶 `kb-content` 下的 `documents/{documentId}/`；`source/{rev}/source.md` 保存原文，文件输入另保存 `original.{ext}`；`derived/{rev}/{jobId}/` 保存导读问答；`releases/{publicationId}/document.md` 保存固定发布文本 |
| 博客图片 | notes-blog 写入同一文档下的 `assets/`；bridge 处理和引用这些资产，并在整篇文档永久删除时清理对象范围 |
| 索引与检索 | bridge 通过 KBVector 专用 API 提交最终文本、文档与发布 ID、SHA-256，接收索引身份、状态与命中分块 |
| MinIO 客户端文件存储 | `MINIO_RAW_BUCKET` 与 `MINIO_PROCESSED_BUCKET` 分别配置原始件和处理件的存储桶 |

查询结果包含 BLOG 或 OPENCLAW 来源、文档和发布 ID、源修订、内容、对象路径、哈希及分数。bridge 会核对有效版本；博客端还会核对本地笔记权限和文件内容，随后交给浏览器展示。

## 配好依赖和认证

后端使用 JDK 25、Spring Boot 4、Maven（容器使用 3.9）和 MyBatis Plus。运行需要独立的 MySQL、MinIO 与 KBVector；生成导读问答和管理台对话还需要 OpenAI 兼容的 LLM 服务。Compose 只启动 bridge，不创建依赖服务。

在根目录执行 `cp -n .env.example .env`，按 [.env.example](.env.example) 填写配置。应用启动时从当前目录加载 `.env`，环境变量和命令行参数优先；修改后重启。映射依据是 [application.yml](src/main/resources/application.yml)、`KbProperties` 与 `unified/UnifiedProperties`。

| 配置组 | 要填写的内容 |
| --- | --- |
| `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` | bridge 专用数据库连接；示例库名 `kb_bridge`，字符集使用 `utf8mb4` |
| `MINIO_ENDPOINT`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY` | 指向与博客相同的对象存储；先建立私有 `kb-content` 桶并配置所需访问权限 |
| `KB_UNIFIED_BUCKET` | 固定为 `kb-content`，源码拒绝其他桶名 |
| `KB_VECTOR_URL`、`KB_BRIDGE_SERVICE_TOKEN` | KBVector 根地址与专用 Bearer 凭据；URL 需使用实际运行端口，KBVector 应用默认 `8000` |
| `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL` | 内容处理和对话使用的 OpenAI 兼容服务；提示词在 [prompts/](src/main/resources/prompts/) |
| `KB_UNIFIED_WORKER_ENABLED`、租约及重试参数 | 发布任务调度默认开启；关闭后异步发布不会正常推进 |
| `SERVER_ADDRESS`、`SERVER_PORT` | 默认 `0.0.0.0:8111`；本机开发建议将地址设为 `127.0.0.1` |
| `RAGFLOW_BASE_URL`、`RAGFLOW_API_KEY`、`KB_QUERY_DATASET_IDS` 或 `RAGFLOW_DATASET_ID` | RAGFlow 检索的服务与数据集配置；允许列表和默认数据集均为空时，主查询只使用统一知识 |

三种入口的凭据应分别配对：

- 博客调用 `/api/v1/blog/*`：两端的 `BLOG_BRIDGE_TOKEN` 相同，博客通过 `Authorization: Bearer` 发送。这里接收正文、修订与发布身份，返回预览、任务状态或知识结果。
- bridge 调用 KBVector 的 `/api/v1/bridge/*`：两端的 `KB_BRIDGE_SERVICE_TOKEN` 相同，且必须与 KBVector 普通 `API_KEYS` 中任何令牌不同。这里提交最终发布文本，进行版本准备、启用、删除和查询。
- OpenClaw 的签名接口：调用方与 bridge 共用 `KB_SHARED_SECRET`，发送 `X-KB-RequestId`、`X-KB-Timestamp` 和 `X-KB-Signature`。文件上传使用 `X-KB-File-Token`，匹配 `FILE_INGEST_TOKEN`；该配置留空时匹配 `KB_SHARED_SECRET`。

管理台直接调用的聊天、任务、文档和审核接口在当前实现中跳过 HMAC，文件上传仍有单独令牌校验。应用没有管理台登录保护；部署时必须在网关或网络层限制管理台及相应 API 的访问，不能把博客的服务令牌当作管理台登录保护。

## 初始化数据库并启动

本仓没有启用 Flyway，也没有自动记录 SQL 应用进度。先准备数据库和初始化账号，再按库的状态选择下面一条路径；应用不会自动创建或升级表结构。

- **新空库**：导入 [schema-mysql.sql](src/main/resources/db/init/schema-mysql.sql)，一次建立当前需要的五张表，包含文件输入和统一知识的全部字段及索引。该脚本面向 MySQL 8.4，不包含账号或业务初始数据；导入后无需执行 V1–V7。
- **已有库**：核对表结构与部署记录，只补尚未应用的 V1–V5。统一知识结构使用 [V7__complete_unified_knowledge.sql](src/main/resources/db/migration/V7__complete_unified_knowledge.sql) 补齐，跳过 V6；V7 只添加缺失的 V6 字段、索引和逻辑文档表，兼容已有或尚缺 `retry_count` 的基线，也可继续完成中断的 V6。现有列、索引和行数据保持原样。V1–V6 保留为已应用版本，不再重放 V6；已完整具备统一知识结构的库无需重复操作。

两条路径不能同时执行。新库在仓库根目录导入，连接参数换成已准备的初始化账号；数据库名由连接命令指定：

```bash
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -u kb_bridge_app -p kb_bridge < src/main/resources/db/init/schema-mysql.sql
```

已有库仍缺统一知识结构时，先补完 V1–V5，再在同一个目标库执行兼容升级：

```bash
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -u kb_bridge_app -p kb_bridge < src/main/resources/db/migration/V7__complete_unified_knowledge.sql
```

确认所选初始化或升级步骤成功后，再打包启动：

```bash
mvn -DskipTests package -Dfile.encoding=UTF-8
SERVER_ADDRESS=127.0.0.1 java -Dfile.encoding=UTF-8 -jar target/knowledge-bridge-1.0-SNAPSHOT.jar
```

从仓库根目录启动，应用才能找到本地 `.env`。默认访问 `http://127.0.0.1:8111/` 可打开打包的管理台，`/actuator/health` 提供健康检查。该检查也会访问 RAGFlow；即使主查询没有配置 RAGFlow 数据集，RAGFlow 不可用仍可能让总健康状态为 DOWN。因此还需要实际验证统一发布和查询。

容器启动命令为 `docker compose up -d --build`，日志用 `docker compose logs -f knowledge-bridge` 查看。[docker-compose.yml](docker-compose.yml) 使用 host 网络并挂载 `./logs`，没有接入博客的 `knowledge-apps` 网络。博客和 bridge、bridge 和 KBVector 之间都要配置实际可达地址；不要直接假设其他仓的 Compose 服务名在这里能解析。Dockerfile 的健康检查固定访问 `8111`，修改运行端口时还需同步健康检查。

## 管理台开发

使用支持当前 Vite 8 工具链的 Node.js 和 pnpm，依赖版本由 `web/pnpm-lock.yaml` 锁定；本仓未固定 Node/pnpm 版本。可与笔记前端共用 Node.js 24。运行：

```bash
pnpm --dir web install --frozen-lockfile
pnpm --dir web dev
```

[web/vite.config.ts](web/vite.config.ts) 将 `/api` 和 `/actuator` 代理到 `http://localhost:8080`，与后端默认 `8111` 不同。开发管理台时可用 `SERVER_PORT=8080 SERVER_ADDRESS=127.0.0.1 java -Dfile.encoding=UTF-8 -jar target/knowledge-bridge-1.0-SNAPSHOT.jar` 启动后端；博客若同时连接它，也要使用该端口。页面地址以 Vite 启动输出为准。

Maven 不自动构建管理台。改完页面后，先执行 `pnpm --dir web build`，把 `web/dist/` 内容同步到 `src/main/resources/static/`，再打包后端；这样 Java 包里才包含新的页面。管理台入口和代码修改规则见 [AGENTS.md](AGENTS.md)。

## 最小验证路径

使用隔离数据，在管理台手动提交一篇包含标题和正文的合成 Markdown，查看任务从排队到有效发布，再查询该内容。禁用这篇文档后，应不再返回它。候选入库还应经过“等待审核 → 通过 → 发布”的路径；LLM 和对象存储均需可用。

验证博客对接时，由测试博客创建笔记、原文入库，等待有效后查询，再撤回。这个路径覆盖 `BLOG_BRIDGE_TOKEN`、固定发布文件、KBVector 专用令牌、索引和有效版本过滤，不要求生成导读问答。

自动化入口是 `mvn test -Dfile.encoding=UTF-8`；管理台为 `pnpm --dir web test`、`pnpm --dir web lint`、`pnpm --dir web build`。后端统一发布测试使用 H2 和外部服务替身。构建、源码核对及替身测试均不能代替上述真实依赖验证。

SQL 结构校验可运行 `python3 scripts/test_mysql_schema.py`；若已安装的 `mysql`、`mysqld` 不在 PATH 中，用 `--mysql`、`--mysqld` 指定路径。脚本启动关闭网络监听的临时 MySQL 8.4，使用合成数据验证空库结构、不同 `retry_count` 基线、中断升级、重复执行与数据保留，结束后停止实例并删除临时目录。

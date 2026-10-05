# 在 knowledge-bridge 中开发

本仓只管理处理预览、固定入库文件和索引交付。`OpenClawController` 适配当前插件，原文转交 notes-blog；`PreviewService` 管理流式改写、检查点和租约；`ProcessingService` 管理发布与清理；`LlmService` 调用模型；`ProcessingObjects` 只处理结果桶。

## 保持当前职责

- 本系统按全新数据库和 MinIO 部署，不保留历史协议、桶名或配置适配。完整 SQL、部署和配置见 README。
- 应用间通过 Docker 主机网关使用 HTTP。配置改变同步 `.env.example`、Compose、部署脚本及 README；真实 `.env` 不提交。
- 保存与入库分离，处理绑定确定原文修订。回收站立即停止检索，恢复需重新确认入库。重试、删除和服务重启后不能让旧作业覆盖新操作。
- notes-blog 管理可编辑原文及附件，bridge 管理改写与入库文件，KBVector 管理向量。不要在两个项目重复实现同一业务。
- OpenClaw 文件仅支持 UTF-8 Markdown，不为其他格式预留。插件源码不修改；现有插件正在使用的接口、签名和响应字段需继续有效。
- 开始前检查 Git 状态；测试使用隔离合成数据，保留用户已有改动。

## 验证

使用 JDK 25，运行 `mvn test package`。插件协议与认证保持现有插件可调用，处理改动需验证真实改写、索引和清理。

纯文档核对命令、相对链接和 `git diff --check`。报告区分代码检查、自动化及真实服务验证，未运行的检查明确说明。

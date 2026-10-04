# Knowledge Bridge 管理台

`knowledge-bridge` 的 React 19、TypeScript 6、Vite 8 管理台，使用 Ant Design 6。提供对话、入库任务、候选审核、文档详情和状态监控；BLOG 文档只读，发布及撤回回到博客操作。

后端配置、数据库准备和整体部署见 [根目录 README](../README.md)。

## 开发入口

使用 Node 24 和 pnpm，从仓库根目录执行：

```bash
pnpm --dir web install --frozen-lockfile
pnpm --dir web dev
pnpm --dir web test
pnpm --dir web lint
pnpm --dir web build
```

Vite 当前将 `/api` 和 `/actuator` 代理到 `http://localhost:8080`。联调后端用 `SERVER_PORT=8080 mvn spring-boot:run -Dfile.encoding=UTF-8`；后端独立运行默认端口为 8111。

页面在 `src/pages/`，请求封装在 `src/api/client.ts`，布局和共享组件在 `src/components/`，主题在 `src/theme/`。测试使用 Vitest、React Testing Library 和 fast-check。

## 提供静态页面

构建产物位于 `web/dist/`，复制到后端 `src/main/resources/static/` 后再打包后端。Maven 和 Dockerfile 不会自动构建管理台；具体命令见根目录 README。API 保持同源，服务密钥不能放入前端。

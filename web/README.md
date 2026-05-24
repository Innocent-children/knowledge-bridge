# Knowledge Bridge Web 管理控制台

Knowledge Bridge 的浏览器端管理界面，提供系统监控、知识库对话、入库任务管理、审核工作流和文档管理功能。

## 技术栈

| 类别     | 技术                    | 版本   |
|--------|-----------------------|------|
| 框架     | React                 | 19.x |
| 语言     | TypeScript            | 6.x  |
| 构建工具   | Vite                  | 8.x  |
| UI 组件库 | Ant Design            | 6.x  |
| 路由     | React Router          | 7.x  |
| 测试框架   | Vitest                | 4.x  |
| 组件测试   | React Testing Library | 16.x |
| 属性测试   | fast-check            | 4.x  |

## 项目结构

```
web/
├── index.html                    # 入口 HTML
├── package.json
├── tsconfig.json
├── vite.config.ts                # Vite 配置（含 API 代理）
├── vitest.config.ts              # 测试配置
├── src/
│   ├── main.tsx                  # 应用入口
│   ├── App.tsx                   # 路由 + 主题配置
│   ├── api/
│   │   ├── client.ts             # 统一 API 客户端（fetch 封装）
│   │   └── client.test.ts        # API 客户端测试
│   ├── components/
│   │   ├── AppLayout.tsx         # 侧边栏 + 内容布局
│   │   ├── EmptyState.tsx        # 共享空状态组件
│   │   ├── HealthStatusCard.tsx  # 健康状态卡片
│   │   └── MetricCard.tsx        # 指标卡片
│   ├── pages/
│   │   ├── DashboardPage.tsx     # 仪表盘（系统指标）
│   │   ├── ChatPage.tsx          # 对话界面（查询/入库）
│   │   ├── IngestPage.tsx        # 入库任务列表
│   │   ├── ReviewPage.tsx        # 审核工作流
│   │   └── DocumentsPage.tsx     # 文档管理
│   ├── types/
│   │   └── index.ts              # TypeScript 类型定义
│   ├── theme/
│   │   └── themeConfig.ts        # Ant Design 自定义主题
│   ├── styles/
│   │   └── transitions.css       # 页面过渡动画
│   └── test/
│       └── setup.ts              # 测试环境配置
└── dist/                         # 构建产物（git 忽略）
```

## 页面功能

### Dashboard（仪表盘）

- 展示系统健康状态（UP/DOWN 颜色标识）
- 展示关键指标：查询次数、入库任务数、入库成功率
- 手动刷新按钮
- 异常时显示错误提示和重试按钮

### Chat（对话）

- 双模式切换：Query（知识查询）/ Ingest（内容入库）
- Query 模式：调用 `/api/v1/chat`，展示 LLM 回答和证据来源
- Ingest 模式：调用 `/api/v1/ingest/manual`，展示入库任务 ID
- 对话历史通过 sessionStorage 持久化
- LLM 不可用时降级展示证据来源

### Ingest（入库任务）

- 分页表格展示入库任务列表
- 按状态过滤（RECEIVED、RAW_STORED、PROCESSING 等）
- 点击行查看任务详情（Modal）
- 错误状态下显示重试按钮

### Review（审核）

- Tab 切换：待审核 / 已通过 / 已拒绝；当前后端只提供待审核列表接口，已通过/已拒绝不会单独加载历史数据
- 单条审核：通过 / 拒绝（含原因输入）
- 批量操作：勾选多条后批量通过或拒绝
- 点击行查看审核详情（Drawer）

### Documents（文档管理）

- 分页表格展示知识文档列表
- 启用/禁用开关（乐观更新，失败自动回滚）
- 按状态过滤（COMPLETED、DISABLED、FAILED）
- 点击行查看文档详情（Drawer，含标签和元数据）

## 开发

### 环境要求

- Node.js 20+
- npm 9+

### 安装依赖

```bash
cd web
npm install
```

### 启动开发服务器

```bash
npm run dev
```

开发模式下，Vite 代理应将 `/api/**` 和 `/actuator/**` 转发到 `http://localhost:8111`（Spring Boot 后端）。

### 构建

```bash
npm run build
```

构建产物输出到 `web/dist/`，生产环境由 Spring Boot 从 classpath `/static/` 目录提供服务。

### 运行测试

```bash
# 单次运行
npm test

# 监听模式
npm run test:watch
```

### 代码检查

```bash
npm run lint
```

## API 代理配置

开发模式下的代理规则应与后端默认端口保持一致：

| 路径前缀        | 目标                      |
|-------------|-------------------------|
| `/api`      | `http://localhost:8111` |
| `/actuator` | `http://localhost:8111` |

## 主题定制

自定义主题配置位于 `src/theme/themeConfig.ts`，使用深紫色为主色调：

- 主色：`#6C5CE7`（深紫）
- 成功色：`#00B894`
- 警告色：`#FDCB6E`
- 错误色：`#E17055`
- 侧边栏背景：`#2D1B69`

## 测试策略

- **单元测试**：使用 Vitest + React Testing Library 测试组件渲染和交互
- **属性测试**：使用 fast-check 验证正确性属性
    - Property 2：Chat 会话存储序列化/反序列化一致性
    - Property 6：API 客户端对 4xx/5xx 状态码的错误处理
    - Property 7：指标卡片渲染完整性

## 生产部署

1. 在 `web/` 目录执行 `npm run build`
2. 将 `web/dist/` 内容复制到 Spring Boot 的 `src/main/resources/static/`
3. Spring Boot 会自动提供静态文件服务，SPA 回退控制器处理客户端路由
4. 缓存策略：
    - `index.html` → `Cache-Control: no-cache`
    - `/assets/**`（含 hash 文件名）→ `Cache-Control: max-age=31536000`

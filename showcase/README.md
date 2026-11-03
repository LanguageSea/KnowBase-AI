# 知库 AI 面向面试官展示站（已弃用原型）

> 该目录不再作为交付源，也不应单独启动。正式展示页已并入
> `frontend/src/showcase/`，统一通过 `frontend` 的根路由 `/` 访问。

独立 Vue 3 + TypeScript + Vite 单页项目。展示现有知识库 RAG 系统的真实功能、架构、技术实现、测试结果和工程边界。

## 本地运行

```powershell
cd D:\thesis\KnowBase-AI\showcase
npm install
npm run dev
```

默认打开 `http://127.0.0.1:5173`。

## 生产构建

```powershell
npm run build
npm run preview
```

构建产物位于 `dist/`。

## 自动化测试

先启动开发服务器：

```powershell
npm run dev -- --host 127.0.0.1 --port 4173
```

另开终端运行：

```powershell
npm run test:e2e
```

测试使用本机 Chrome，覆盖：

- 1440、1024、390 三个视口。
- 页面标题、章节数量和控制台错误。
- 水平溢出检查。
- 架构节点详情。
- 技术专题切换。
- 代码复制。
- 移动菜单打开和关闭。
- 桌面、平板、手机截图。

截图输出到 `artifacts/`。

可通过环境变量覆盖 Chrome 与站点地址：

```powershell
$env:CHROME_PATH = "C:\Program Files\Google\Chrome\Application\chrome.exe"
$env:SHOWCASE_URL = "http://127.0.0.1:4173"
npm run test:e2e
```

## 外部链接

复制 `.env.example` 为 `.env`，按需填写：

```env
VITE_SHOWCASE_BASE=/
VITE_DEMO_URL=
VITE_SOURCE_URL=
VITE_README_URL=
VITE_RUNBOOK_URL=
```

空链接不会渲染为空按钮，只显示“待部署”或“待公开”状态。

## 内容维护

项目事实、技术栈、架构节点、技术专题、测试指标和外部链接集中放在：

```text
src/data/content.ts
```

修改指标时必须同时保留：

- 测量日期。
- 样本和环境条件。
- 状态。
- 可回溯的项目文档来源。

RAG 的 `97.50%` 只能表述为固定集 Recall@5，不能写成“系统准确率”。

## Docker

```powershell
docker build -t knowbase-showcase .
docker run --rm -p 8088:80 knowbase-showcase
```

打开 `http://127.0.0.1:8088`。

## 页面结构

1. Hero 与真实模块界面预览。
2. 项目背景与解决策略。
3. 服务架构、边界与技术栈。
4. 身份治理、知识生命周期、RAG、运维能力。
5. 文档索引、RAG 引用、SSE、权限过滤和失败恢复。
6. 自动化测试、固定集评估和已知限制。
7. 工程实践、关键取舍和项目边界。
8. 在线体验、源码与文档入口。

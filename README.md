# 稍后读 · AI 摘要（ReadLater）

粘贴文章链接 → 自动抓取正文 → 本地 Ollama 模型生成摘要 → 搜索回顾。

## 技术栈（TRD v0.1）

- **后端**：Node 22 + Fastify + better-sqlite3（SQLite/WAL）
- **前端**：React 18 + Vite
- **AI**：Ollama（`qwen2.5:3b`，不进容器，走 `host.docker.internal`）
- **部署**：Docker Compose

## 本地开发 5

```bash
# 1. 后端（端口 3000）
cd backend && npm install && npm start

# 2. 前端（端口 5173，已配置代理到 3000）
cd frontend && npm install && npm run dev
```

前置条件：本机已安装并启动 [Ollama](https://ollama.com)，且拉取过 `qwen2.5:3b`（`ollama pull qwen2.5:3b`）。

## 生产构建

```bash
cd frontend && npm run build          # 产出 frontend/dist
cd .. && docker compose up -d --build # 容器内托管 dist，SQLite 数据落在 app-data 卷
```

访问 `http://localhost:3000`。健康检查：`GET /health` 返回 `{status, ollama}`，`ollama:false` 表示模型服务不可达。

## 文档索引（docs/）

文档按域分目录管理，完整导航与规范见 **[docs/README.md](docs/README.md)**。

| 目录 | 内容 |
|---|---|
| `docs/product/` | PRD v1.0 · 需求规格 Spec v0.2 |
| `docs/tech/` | TRD · API 契约 v0.2 · **adr/**（架构决策记录） |
| `docs/quality/` | 自测清单 · 测试记录 |
| `docs/pm/` | 计划与排期 · 技术债务 |
| `docs/reviews/` | 评审记录（按日期归档） |
| `prototype/index.html` | 高保真原型 v0.3（Editorial 方向，UI 先行定稿） |

## 状态机

```
pending →(抓取)→ fetched →(摘要)→ done
   └────────(失败)────────────→ failed →(重试/粘贴正文)→ 重新入队
```

前端对进行中文章每 2 秒轮询详情接口（Spec S2.3）；服务重启时 `fetched` 态自动重入队（TRD 决策1）。

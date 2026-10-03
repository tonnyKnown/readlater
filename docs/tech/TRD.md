# TRD：技术设计文档 —— 「稍后读 · AI 摘要」

> 版本 v0.1（**待技术评审**）｜ 依据 PRD v1.0 + Spec v0.2 ｜ 日期 2026-10-03

## 一、需求概述

MVP 为免登录稍后读工具：粘贴 URL 抓取正文（F1）、本地模型生成 3 句摘要（F2）、列表搜索（F3）。技术核心挑战：①抓取的健壮性与 SSRF 防护（Spec S1.4-S1.9）；②本地模型调用的超时控制与失败降级（S2.3-S2.6）；③抓取与摘要的**异步化**——保存必须 10 秒内返回（S1.2）。

## 二、整体架构

```
浏览器 (React SPA 静态托管)
     │ /api/v1/*
     ▼
Fastify API (Node 20, TypeScript) ──── SQLite (better-sqlite3, 单文件)
     │
     ├─ 抓取 worker：fetch + Readability 提取正文
     └─ 摘要 worker：进程内串行队列 ────► Ollama (宿主机 11434 端口)
```

单人项目、单机部署，**不引入 Redis / 消息队列 / 微服务**——进程内队列够用，这是与项目规模匹配的刻意取舍（见 §六-3）。

## 三、技术选型

| 层 | 选型 | 理由 | 备选 |
|---|---|---|---|
| 前端 | React 18 + Vite + TS | 生态成熟、构建秒级 | Vue 3 |
| UI | Tailwind CSS | 免维护样式系统，列表页开发快 | AntD（偏后台，过重） |
| 后端 | Node 20 + Fastify + TS | 原生 fetch 方便抓取；性能好；前后端同语言 | NestJS（重）、Express（老） |
| 数据库 | SQLite (better-sqlite3) | 单文件零运维、同步 API 简单；量级（千条文章）完全够 | PostgreSQL（多用户时再换） |
| 正文提取 | @mozilla/readability + cheerio | 火狐阅读模式的提取算法，效果好 | trafilatura（Python，跨语言麻烦） |
| AI | Ollama + qwen2.5:3b | 本地免费；3b 起步内存友好（8G 机器可跑），摘要任务 3b 足够 | qwen2.5:7b（效果更好，内存紧张时换） |
| 部署 | Docker Compose（api + nginx/web） | 一条命令起停；Ollama 复用宿主机已有安装，不进容器 | 全容器化（含 ollama 镜像，镜像 2G+ 不值得） |

## 四、数据模型

### article 表

| 字段 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK AUTOINCREMENT | |
| url | TEXT NOT NULL UNIQUE | 原文链接，唯一索引防重复（S1.7） |
| title | TEXT | 页面标题 |
| content | TEXT | 提取的正文（或手动粘贴的正文） |
| image_url | TEXT NULL | 主配图 |
| summary | TEXT NULL | 摘要，未生成时为 NULL |
| status | TEXT NOT NULL | `pending` / `fetched` / `done` / `failed` |
| fail_reason | TEXT NULL | 失败原因（摘要/抓取），用于重试与日志 |
| created_at | TEXT NOT NULL | ISO 8601 |

索引：`url` 唯一索引；`created_at` 普通索引（S3.1 排序）。
搜索（S3.2）：MVP 用 `title LIKE ? OR content LIKE ?`，25~500 条量级 <5ms，不引入 FTS5；**数据过万再升级**，记入技术债。

### request_log 表（支撑 PRD 成功率度量，Spec N3）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK | |
| type | TEXT | `fetch` / `summary` |
| article_id | INTEGER | 关联文章 |
| ok | INTEGER | 1 成功 0 失败 |
| duration_ms | INTEGER | 耗时 |
| reason | TEXT NULL | 失败原因 |

## 五、核心流程

### 5.1 保存（异步，满足 S1.2 的 10 秒约束）

```
POST /articles
  → 校验 URL（§5.3 SSRF 防护）
  → INSERT article(status=pending)，立即返回 200 {id}     ← 毫秒级
后台 fetch worker（串行）
  → fetch 页面（8s AbortController 超时，S1.9）
  → Readability 提取 → 成功: status=fetched，入摘要队列
                      → 失败: status=failed + fail_reason（S1.8）
```

### 5.2 摘要（进程内串行队列）

- 队列实现：数组 + `while` 循环的异步消费者，**串行执行**——本地 Ollama 并发能力有限，串行可预测性最好
- 任务：取 content 前 6000 字（S2.5）→ 固定 prompt → 30s 超时（S2.3）→ 成功写 summary + status=done
- 失败路径：超时/连接拒绝/返回空 → status=failed（S2.4 可重试；S2.6 不影响文章可用）
- **重启恢复**：服务启动时扫描所有 `pending`/`fetched` 状态的记录重新入队——进程内队列重启会丢任务，靠启动扫描补齐（弥补无 MQ 的代价）

Prompt 固定模板：

```
你是一名阅读助手。用不超过 3 句中文概括以下文章的核心内容，
只输出概括本身，不要任何前缀。
<article>
{content 前 6000 字}
</article>
```

### 5.3 SSRF 防护（Spec S1.5 v0.2）

校验顺序（任一命中即拒绝）：
1. 协议白名单：仅 http/https
2. 主机名黑名单：`localhost` 及其变体、以 `.local` 结尾
3. IP 字面量归一化后比对内网段：含八进制/十六进制/**十进制整数**写法（`2130706433` → 127.0.0.1）、`0.0.0.0`、IPv6 回环 `::1` 与 `fd00::/8`
4. **DNS 解析后二次校验**：域名先解析出 IP，IP 命中内网段再拒绝（防 DNS 重绑定）
5. 抓取时禁用重定向到非白名单目标（`redirect: 'follow'` 后校验最终 URL）

## 六、关键决策记录（评审重点）

| # | 决策 | 理由 | 放弃的方案 |
|---|---|---|---|
| 1 | 摘要 worker 串行 | Ollama 本地推理并发≈1，串行最可控 | 并行 + 信号量（复杂度不值） |
| 2 | Ollama 走宿主机（host.docker.internal） | 复用已装环境，免 2G 镜像 | ollama 容器化 |
| 3 | 进程内队列 + 启动扫描恢复 | 规模不需要 MQ；用启动扫描弥补重启丢任务 | Redis Queue |
| 4 | SQLite + LIKE 搜索 | 千条量级毫秒级响应 | FTS5 / ES（过设计） |
| 5 | 模型选 3b 而非 7b | 摘要任务对参数量不敏感，内存减半 | — |

## 七、部署方案

| 项 | 方案 |
|---|---|
| 容器 | docker-compose：`api`（Fastify）+ `web`（nginx 托管 React 静态文件并反代 /api） |
| 数据卷 | SQLite 文件挂载到宿主机 `./data/` |
| 模型 | 宿主机 Ollama，容器经 `host.docker.internal:11434` 访问 |
| 健康检查 | `GET /health`，compose 配 healthcheck |
| 回滚 | 镜像带版本 tag，`docker compose` 一键切回上一版 |

## 八、安全

- SSRF：§5.3 五层校验
- 限流：Fastify 插件 `@fastify/rate-limit`，同 IP 60 次/分钟（Spec N1，超限 42901）
- CORS：白名单仅自身域名（N2）
- 注入：better-sqlite3 全程参数化查询
- 数据：无用户系统、无敏感数据（免登录评审决议）

## 九、监控与日志

- 应用日志走 stdout（docker logs 可查），关键路径：抓取耗时、摘要耗时、失败原因
- `request_log` 表是成功率度量的数据源（评审记录决议 #2）
- `GET /health` 供 compose healthcheck 与上线后探活

## 十、技术债与风险

| 风险/债务 | 应对 | 偿还时机 |
|---|---|---|
| 进程内队列无持久化 | 启动扫描重入队 | 数据量/频率上来后换 BullMQ |
| LIKE 搜索性能 | 量级小无感 | 文章过万换 FTS5 |
| 单文件 SQLite 备份 | 上线清单加入"发版前拷贝 data 目录" | 多用户时换 PG |
| qwen2.5:3b 摘要质量 | 抽样验收兜底 | 不达标升 7b |

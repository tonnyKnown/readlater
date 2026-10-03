# API 契约 v0.2：「稍后读 · AI 摘要」

> 基础路径 `/api/v1` ｜ 依据 Spec v0.2 + 高保真原型 v0.2 ｜ 前后端并行开发以本文档为准
> v0.2 变更：新增 `reading_minutes`（原型 v0.2 详情/列表展示阅读时长所需，后端按正文长度估算：中文 400 字/分钟）。教训记录：该字段本应在 UI 定稿后一次写对，v0.1 漏掉即"API 先于 UI"顺序错误的返工成本。

## 一、通用约定

| 项 | 约定 |
|---|---|
| 格式 | 请求/响应均 JSON，UTF-8 |
| 成功 | `{ "code": 0, "data": ... }` |
| 错误 | `{ "code": 40001, "message": "URL 不合法" }` |
| 分页 | `?page=1&size=20`，响应含 `total` |
| 时间 | ISO 8601，如 `2026-10-03T23:54:00+08:00` |
| 限流 | 同 IP 60 次/分钟（Spec N1） |

## 二、错误码

| code | HTTP | 含义 |
|---|---|---|
| 0 | 200 | 成功 |
| 40001 | 400 | 参数错误（URL 不合法 / 正文为空等，Spec S1.4-S1.6） |
| 40401 | 404 | 文章不存在 |
| 42901 | 429 | 触发限流 |
| 50001 | 500 | 服务内部错误 |

## 三、状态机（前端轮询依据）

```
pending ──抓取成功──► fetched ──摘要成功──► done
   │                    │                     
   └──────┬─────────────┴── 抓取/摘要失败 ──► failed ──重试──► pending
          └─ 手动粘贴正文(PUT /content) ──► fetched
```

## 四、接口明细

### 1. 保存文章 `POST /articles`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| url | string | 是 | 合法 URL（Spec S1.4-S1.6） |

响应：`{ "code": 0, "data": { "id": 42, "status": "pending", "duplicate": false } }`
重复 URL（S1.7）：`duplicate: true` 且返回已有 id。

### 2. 文章列表 `GET /articles?page=1&size=20&keyword=`

响应 data：`{ "total": 87, "items": [ { "id", "title", "summary", "status", "created_at", "image_url", "reading_minutes" } ] }`
列表项**不含 content**（省流量）；keyword 匹配标题+正文（S3.2）。

### 3. 文章详情 `GET /articles/{id}`

响应 data：完整字段（含 content、fail_reason、reading_minutes）。前端据 `status` 展示：pending=抓取中、fetched=摘要中、failed=显示失败原因+重试/粘贴正文入口。

### 4. 手动粘贴正文 `PUT /articles/{id}/content`（Spec S1.8）

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| content | string | 是 | 非空、≤100 万字符 |

仅 status=failed 时可调用。成功后 status → fetched，进入摘要队列。

### 5. 重试摘要 `POST /articles/{id}/retry`（Spec S2.4）

仅 status=failed 时可调用。成功后 status → pending，重新入队。

### 6. 删除文章 `DELETE /articles/{id}`（Spec S3.5）

物理删除。前端负责确认弹窗。

### 7. 健康检查 `GET /health`（Spec N5）

不经 `/api/v1` 前缀，返回 `{ "status": "ok" }`；同时探测 Ollama 可达性，返回 `{ "status": "ok", "ollama": true|false }`。

## 五、前端消费约定

- 保存后跳转到列表/详情，**每 2 秒轮询详情接口**直至 status ∈ {done, failed}（MVP 不引入 WebSocket，技术债记录在 TRD §十）
- failed 态 UI：显示 fail_reason + 「重试」「手动粘贴正文」两个动作（对应接口 4、5）

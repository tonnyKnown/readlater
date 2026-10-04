# Changelog

本项目所有显著变更都记录在本文件。
格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added
- PRD v1.0（三功能 MVP：保存 / AI 摘要 / 搜索）及评审记录归档
- 需求规格 Spec v0.2：EARS 句式覆盖 F1/F2/F3 全部边界，含 SSRF 防护细则（协议白名单、内网段、0.0.0.0、IPv6、十进制 IP、DNS 解析后二次校验）
- TRD v0.1：Fastify + SQLite(WAL) + 串行摘要 worker + Docker Compose 部署方案
- API 契约 v0.2：`/api/v1/articles` 全套接口、错误码规范、文章状态机（pending → fetched → done / failed）
- 高保真原型 v0.2：列表页 / 详情完成态 / 详情失败降级三视图，四态徽章
- 高保真原型 v0.3（Editorial 方向，替换 v0.2 成为正式原型）：编辑风排版（衬线标题+正文、栅格列表、细分隔线）、设计 token 升三层架构、深浅双主题、新增「摘要生成中」视图补齐 loading 态、状态标改图标+文字双编码、支持 `?view=&theme=` URL 直达；Spec 行为未改动。v0.2 归档至 `prototype/archive/`
- 后端 MVP：SSRF 五层校验、抓取（8s 超时）、串行摘要 worker（重启自动重入队）、限流 60 次/分/IP、CORS 白名单
- 前端 MVP：React + Vite，按原型 token 实现，2 秒轮询驱动状态徽章、搜索空态、失败降级（重试 / 粘贴正文）
- `GET /health` 附带 Ollama 可达性探测
- 文章去重（同 URL 直接返回已有记录）
- Dockerfile + docker-compose.yml（Ollama 留宿主机，走 `host.docker.internal`）

### Fixed
- BUG-1：deepseek-r1 长文摘要 30s 超时 → 摘要输入截 3000 字、`num_predict: 500`、超时时限可配置（`SUMMARY_TIMEOUT_MS`）、HTML 实体解码、超时提示中文化
- worker 漏 import `requeueStuck` 导致启动即崩
- 推理模型输出 `<think>` 思考块污染摘要 → 已剥离
- 静态托管目录计算错误（`backend/frontend/dist`）导致生产模式首页 404

### Security
- SSRF 五层防护（协议 → 主机名 → IP 归一化 → DNS 解析后 → 重定向后校验）

## [0.1.0] - 计划中

首个公开版本：功能全量验证通过 + 预发部署 + 灰度上线后打 tag。

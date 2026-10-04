# 迁移计划：Node.js 后端 → Spring Boot + MyBatis-Plus + MySQL

## Context
现有后端（`backend/`）为 Node.js + Fastify + better-sqlite3，约 400 行代码。用户已将本机 MySQL 8.0.46 就绪，且要求按企业 Java 规范重写后端：
- 强制 XML SQL（MyBatis），禁止注解 SQL 与 Java 拼接 SQL
- Controller → Service → Dao 三层架构
- Entity/DTO/VO 拆分，状态用枚举
- 前端 React（`frontend/`）零改动，API 契约保持不变

## Target
在 `backend-java/` 下新建完整 Maven 工程，功能 1:1 复刻 Node 后端。原 `backend/` 保留不动。

## 技术选型
- Spring Boot 3.5.x（父 POM 管版本），`maven.compiler.release=17`
- MyBatis-Plus 3.5.x + MySQL Connector/J 8.4
- JDK 25.0.2 运行，编译级别 Java 17
- Maven Wrapper（本机无全局 Maven）

## 工程结构（`backend-java/`）
```
backend-java/
├─ pom.xml
├─ mvnw / mvnw.cmd / .mvn/wrapper/
└─ src/main/
   ├─ java/com/readlater/
   │  ├─ ReadlaterApplication.java      @SpringBootApplication, @MapperScan, @EnableScheduling
   │  ├─ controller/
   │  │  ├─ ArticleController.java      /api/v1/articles 6 个端点
   │  │  └─ HealthController.java       /health 探测 Ollama
   │  ├─ service/
   │  │  ├─ ArticleService.java         去重、状态机、校验编排
   │  │  ├─ FetchService.java           SSRF 五层校验 + 抓取 + HTML 提取
   │  │  ├─ SummaryService.java         调 Ollama + think 块剥离
   │  │  └─ SummaryWorker.java          @Scheduled(fixedDelay=1000) 串行 worker
   │  ├─ dao/
   │  │  └─ ArticleMapper.java          仅声明自定义方法，继承 BaseMapper
   │  ├─ entity/    Article.java
   │  ├─ dto/       ArticleCreateReq, ContentUpdateReq, ArticleQueryReq
   │  ├─ vo/        ArticleVO, ArticleListItemVO, PageVO
   │  ├─ enums/     ArticleStatus, ErrorCode
   │  ├─ config/    MybatisPlusConfig, RestTemplateConfig, WebConfig, RateLimitInterceptor
   │  ├─ common/    Result, BizException, GlobalExceptionHandler
   │  └─ runner/    StartupRequeue.java  ApplicationRunner，启动时 fetched→pending
   └─ resources/
      ├─ application.yml
      ├─ schema.sql
      └─ mapper/ArticleMapper.xml
```

## 关键实现要点

### 1. MySQL Schema
```sql
CREATE TABLE articles (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  url             VARCHAR(2048) NOT NULL,
  url_hash        CHAR(64) NOT NULL,           -- SHA-256 hex，唯一索引
  title           VARCHAR(600) NOT NULL DEFAULT '',
  content         MEDIUMTEXT NOT NULL,
  summary         TEXT NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'pending',
  fail_reason     TEXT NOT NULL,
  image_url       VARCHAR(1000) NOT NULL DEFAULT '',
  reading_minutes INT NOT NULL DEFAULT 0,
  created_at      DATETIME(3) NOT NULL,
  updated_at      DATETIME(3) NOT NULL,
  UNIQUE KEY uk_url_hash (url_hash),
  KEY idx_status (status),
  KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```
- 时间存 UTC `DATETIME(3)`，实体 `LocalDateTime`，VO `OffsetDateTime`，Jackson 序列化自动输出 ISO 8601 带 `Z`
- `url` 超长，用 `url_hash` 做唯一键；service 层先 `selectByUrlHash` 查重，DB 唯一索引兜底并发

### 2. XML SQL 清单（`mapper/ArticleMapper.xml`，自定义查询全部走这里）
- `selectByUrlHash` — 去重查询
- `selectNextPending` — `WHERE status='pending' ORDER BY id ASC LIMIT 1`
- `updateFetchSuccess` — 抓取后更新 title/image/content/reading_minutes/status
- `updateSummary` — 摘要完成后更新 summary/status
- `updateStatusById` — 通用状态更新（`<set>` + `<if>`）
- `selectPage` — 列表 + keyword LIKE（`<where>` + `<if>`）
- `requeueStuck` — 启动时 `UPDATE … WHERE status='fetched'`

### 3. SSRF 五层校验（`FetchService`）
协议白名单 → localhost 黑名单 → IP 字面量归一化（含十进制整数、IPv6） → `InetAddress.getAllByName` DNS 后逐 IP 校验 → 每次重定向后重新校验。手动重定向（`HttpClient`，`followRedirects=NEVER`），最多 5 次，8s 超时。

### 4. Worker（`SummaryWorker`）
`@Scheduled(fixedDelay=1000, initialDelay=2000)` + `AtomicBoolean running` 防重入。每 tick 取一条 pending：抓取 → fetched → 调 Ollama → think 块剥离（regex `/<think>[\s\S]*?(<\/think>|$)/i`）→ 空则抛错 → done。任何异常均落 `failed`。

### 5. 限流 & CORS & 响应
- 限流：`RateLimitInterceptor` 仅拦截 `/api/**`，`ConcurrentHashMap<String, AtomicInteger>` + `@Scheduled(fixedRate=60000)` 每分钟清
- CORS：`WebConfig.addCorsMappings`，源读取 `app.cors-origins`
- 统一响应：`Result<T>` + `@RestControllerAdvice`（`BizException` 按 `ErrorCode` 映射）
- 静态资源：非 `/api/**` 的 404 fallback 到 `frontend/dist/index.html`

### 6. application.yml 配置映射
| Node env | application.yml key |
|---|---|
| PORT | `server.port` |
| OLLAMA_URL | `app.ollama-url` |
| OLLAMA_MODEL | `app.ollama-model` |
| SUMMARY_TIMEOUT_MS | `app.summary-timeout-ms` |
| CORS_ORIGINS | `app.cors-origins` |
| DIST_PATH | `app.dist-path` |

## 验证方案
1. MySQL 建库 `readlater`；IntelliJ 打开 `backend-java/`，配置密码后运行
2. curl 逐接口验证（post/get list/get detail/put content/retry/delete/health）
3. SSRF 用例：`127.0.0.1`、`2130706433`、`::1`、10.x、172.16-31、192.168、169.254 → 均 40001
4. 限流：61 次/分 → 42901
5. 完整链路：提交真实 URL → 轮询详情 → `pending→fetched→done`，summary 无 think 残留

## 实施顺序（建议逐段交付，每段可独立验证）
1. **0.5h** 骨架：Maven Wrapper + pom.xml + application.yml + 启动类 + schema.sql
2. **1h**   数据层：Entity + Mapper 接口 + XML（7 条 SQL）+ 枚举/TypeHandler
3. **1h**   通用层：Result / BizException / ErrorCode / GlobalExceptionHandler / 限流 / CORS
4. **2h**   FetchService：SSRF + HttpClient + HTML 提取（最复杂，需要 JUnit 覆盖）
5. **1h**   SummaryService + SummaryWorker + StartupRequeue
6. **1h**   Controller + DTO/VO + 静态资源托管
7. **1h**   联调 + curl 全套验证 + bug 修复

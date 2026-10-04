-- ReadLater MySQL 建库建表脚本
-- 对应原 SQLite 版 backend/src/db.js 的 articles 表
-- 差异说明：
--   1. url 最长 2048 字符无法整体建唯一索引（utf8mb4 超 3072 字节上限），
--      故增加 url_hash 列存 SHA-256 hex，唯一约束落在 url_hash 上
--   2. 时间字段用 DATETIME(3) 存 UTC，VO 序列化输出 ISO 8601 带 Z（对齐原契约）
--   3. TEXT 类型列不能设 DEFAULT ''，空串由应用层写入保证

CREATE DATABASE IF NOT EXISTS readlater DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE readlater;

CREATE TABLE IF NOT EXISTS articles (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  url             VARCHAR(2048) NOT NULL COMMENT '文章原始链接',
  url_hash        CHAR(64) NOT NULL COMMENT 'url 的 SHA-256 hex，去重与唯一索引用',
  title           VARCHAR(600) NOT NULL DEFAULT '' COMMENT '页面 title 标签',
  content         MEDIUMTEXT NOT NULL COMMENT '提取的正文纯文本',
  summary         TEXT NOT NULL COMMENT 'AI 摘要',
  status          VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending|fetched|done|failed',
  fail_reason     TEXT NOT NULL COMMENT '失败原因',
  image_url       VARCHAR(1000) NOT NULL DEFAULT '' COMMENT 'og:image 主配图',
  reading_minutes INT NOT NULL DEFAULT 0 COMMENT '估算阅读时长（正文长度/400）',
  created_at      DATETIME(3) NOT NULL COMMENT '创建时间 UTC',
  updated_at      DATETIME(3) NOT NULL COMMENT '更新时间 UTC',
  UNIQUE KEY uk_url_hash (url_hash),
  KEY idx_articles_status (status),
  KEY idx_articles_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='稍后读文章表';

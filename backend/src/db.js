// 数据库层：SQLite，表结构依据 TRD §四 数据模型
import Database from 'better-sqlite3';
import { fileURLToPath } from 'url';
import path from 'path';

const dbPath = process.env.DB_PATH || path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'data.db');
export const db = new Database(dbPath);
db.pragma('journal_mode = WAL');

db.exec(`
CREATE TABLE IF NOT EXISTS articles (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  url             TEXT NOT NULL UNIQUE,
  title           TEXT NOT NULL DEFAULT '',
  content         TEXT NOT NULL DEFAULT '',
  summary         TEXT NOT NULL DEFAULT '',
  status          TEXT NOT NULL DEFAULT 'pending',   -- pending|fetched|done|failed (API v0.2 §三)
  fail_reason     TEXT NOT NULL DEFAULT '',
  image_url       TEXT NOT NULL DEFAULT '',
  reading_minutes INTEGER NOT NULL DEFAULT 0,        -- API v0.2 新增：正文长度/400字每分钟
  created_at      TEXT NOT NULL,
  updated_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_articles_status ON articles(status);
`);

const now = () => new Date().toISOString();
const cols = 'id, url, title, content, summary, status, fail_reason, image_url, reading_minutes, created_at, updated_at';

export const getArticle = (id) => db.prepare(`SELECT ${cols} FROM articles WHERE id = ?`).get(id);
export const findByUrl = (url) => db.prepare('SELECT id FROM articles WHERE url = ?').get(url);
export const insertArticle = (url) =>
  db.prepare('INSERT INTO articles (url, status, created_at, updated_at) VALUES (?, ?, ?, ?)').run(url, 'pending', now(), now()).lastInsertRowid;
export const update = (id, fields) => {
  const keys = Object.keys(fields);
  db.prepare(`UPDATE articles SET ${keys.map((k) => `${k} = ?`).join(', ')}, updated_at = ? WHERE id = ?`)
    .run(...keys.map((k) => fields[k]), now(), id);
};
export const remove = (id) => db.prepare('DELETE FROM articles WHERE id = ?').run(id);
export const nextPending = () =>
  db.prepare(`SELECT ${cols} FROM articles WHERE status = 'pending' ORDER BY id ASC LIMIT 1`).get();
export const listArticles = ({ offset, size, keyword }) => {
  const where = keyword ? 'WHERE (title LIKE ? OR summary LIKE ? OR content LIKE ?)' : '';
  const like = `%${keyword}%`;
  const total = db.prepare(`SELECT COUNT(*) AS c FROM articles ${where}`).get(...(keyword ? [like, like, like] : [])).c;
  const items = db
    .prepare(
      `SELECT id, url, title, summary, status, image_url, reading_minutes, created_at FROM articles ${where}
       ORDER BY created_at DESC LIMIT ? OFFSET ?`
    )
    .all(...(keyword ? [like, like, like] : []), size, offset);
  return { total, items };
};
export const requeueStuck = () =>
  db.prepare(`UPDATE articles SET status = 'pending' WHERE status = 'fetched'`).run(); // 启动时重入队（TRD 决策1）

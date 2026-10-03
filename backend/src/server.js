// HTTP 层：路由严格按 docs/03-API.md v0.2 实现
import Fastify from 'fastify';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';
import { getArticle, findByUrl, insertArticle, update, remove, listArticles } from './db.js';
import { validateUrl, calcReadingMinutes } from './fetch.js';
import { startWorker } from './worker.js';

const app = Fastify({ bodyLimit: 2 * 1024 * 1024 });
const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));

// ── 限流：60 次/分/IP（PRD 评审决议3 / Spec N1） ──
const hits = new Map();
setInterval(() => hits.clear(), 60_000).unref();
app.addHook('onRequest', async (req, reply) => {
  if (!req.url.startsWith('/api/')) return;
  const key = req.ip;
  const n = (hits.get(key) || 0) + 1;
  hits.set(key, n);
  if (n > 60) return reply.code(429).send({ code: 42901, message: '请求过于频繁，请稍后再试' });
});

// ── CORS 白名单（PRD 评审决议3） ──
const ALLOWED = (process.env.CORS_ORIGINS || 'http://localhost:5173,http://127.0.0.1:5173').split(',');
app.addHook('onRequest', async (req, reply) => {
  const origin = req.headers.origin;
  if (origin && ALLOWED.includes(origin)) {
    reply.header('Access-Control-Allow-Origin', origin);
    reply.header('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS');
    reply.header('Access-Control-Allow-Headers', 'Content-Type');
  }
  if (req.method === 'OPTIONS') return reply.code(204).send();
});
app.setErrorHandler((err, req, reply) => {
  const code = err.code || (err.statusCode === 404 ? 40401 : 50001);
  reply.code(err.statusCode && err.statusCode !== 500 ? err.statusCode : 400).send({ code, message: err.message || '服务内部错误' });
});
const ok = (reply, data) => reply.send({ code: 0, data });
const mustExist = (id) => {
  const a = getArticle(Number(id));
  if (!a) throw Object.assign(new Error('文章不存在'), { statusCode: 404, code: 40401 });
  return a;
};

// 1. 保存文章
app.post('/api/v1/articles', async (req, reply) => {
  const { url } = req.body || {};
  const v = validateUrl(String(url || ''));
  if (!v.ok) return reply.code(400).send({ code: v.code, message: v.message });
  const dup = findByUrl(v.url.href);
  if (dup) return ok(reply, { id: dup.id, status: getArticle(dup.id).status, duplicate: true });
  const id = insertArticle(v.url.href);
  return ok(reply, { id, status: 'pending', duplicate: false });
});

// 2. 文章列表
app.get('/api/v1/articles', async (req, reply) => {
  const page = Math.max(1, Number(req.query.page) || 1);
  const size = Math.min(50, Math.max(1, Number(req.query.size) || 20));
  const keyword = String(req.query.keyword || '').trim();
  return ok(reply, listArticles({ offset: (page - 1) * size, size, keyword }));
});

// 3. 文章详情（前端 2 秒轮询此接口驱动状态机）
app.get('/api/v1/articles/:id', async (req, reply) => ok(reply, mustExist(req.params.id)));

// 4. 手动粘贴正文（Spec S1.8 降级）
app.put('/api/v1/articles/:id/content', async (req, reply) => {
  const a = mustExist(req.params.id);
  if (a.status !== 'failed') return reply.code(400).send({ code: 40001, message: '仅失败状态可手动粘贴正文' });
  const content = String(req.body?.content || '').trim();
  if (!content || content.length > 1_000_000) return reply.code(400).send({ code: 40001, message: '正文为空或超过 100 万字符' });
  update(a.id, { content, reading_minutes: calcReadingMinutes(content), status: 'fetched', fail_reason: '' });
  return ok(reply, { id: a.id, status: 'fetched' });
});

// 5. 重试（Spec S2.4）
app.post('/api/v1/articles/:id/retry', async (req, reply) => {
  const a = mustExist(req.params.id);
  if (a.status !== 'failed') return reply.code(400).send({ code: 40001, message: '仅失败状态可重试' });
  update(a.id, { status: 'pending', fail_reason: '' });
  return ok(reply, { id: a.id, status: 'pending' });
});

// 6. 删除
app.delete('/api/v1/articles/:id', async (req, reply) => {
  mustExist(req.params.id);
  remove(Number(req.params.id));
  return ok(reply, { deleted: true });
});

// 7. 健康检查（含 Ollama 可达性，上线后区分"服务挂"与"模型挂"）
app.get('/health', async () => {
  let ollama = false;
  try {
    ollama = (await fetch(`${process.env.OLLAMA_URL || 'http://127.0.0.1:11434'}/api/tags`, { signal: AbortSignal.timeout(1500) })).ok;
  } catch { /* unreachable */ }
  return { status: 'ok', ollama };
});

// 生产模式：托管前端构建产物（容器内用 DIST_PATH 指定）
// server.js 位于 <root>/backend/src/，故项目根 = 上两级
const dist = process.env.DIST_PATH || path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'frontend', 'dist');
if (fs.existsSync(dist)) {
  app.setNotFoundHandler((req, reply) => {
    if (req.url.startsWith('/api/')) return reply.code(404).send({ code: 40401, message: '接口不存在' });
    return reply.type('text/html').send(fs.readFileSync(path.join(dist, 'index.html')));
  });
  await app.register((await import('@fastify/static')).default, { root: dist });
}

const port = Number(process.env.PORT) || 3000;
await app.listen({ port, host: '0.0.0.0' });
console.log(`[server] http://localhost:${port} 已启动`);
startWorker();

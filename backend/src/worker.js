// 摘要 worker：串行队列（TRD 决策1），本地 Ollama 生成摘要
import { nextPending, update, getArticle, requeueStuck } from './db.js';
import { fetchWithGuard, extractHtml, calcReadingMinutes } from './fetch.js';

const OLLAMA_URL = process.env.OLLAMA_URL || 'http://127.0.0.1:11434';
const MODEL = process.env.OLLAMA_MODEL || 'qwen2.5:3b';
const FETCH_TIMEOUT = 8000;   // Spec S1.9
const SUMMARY_TIMEOUT = 30000; // Spec S2.3

let running = false;

async function summarize(text) {
  const res = await fetch(`${OLLAMA_URL}/api/generate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    signal: AbortSignal.timeout(SUMMARY_TIMEOUT),
    body: JSON.stringify({
      model: MODEL,
      prompt: `请用不超过 120 字总结以下文章的核心要点，直接输出总结，不要客套话：\n\n${text.slice(0, 8000)}`,
      stream: false,
    }),
  });
  if (!res.ok) throw new Error(`Ollama 返回 HTTP ${res.status}`);
  // deepseek-r1 等推理模型会输出 <think> 块，剥离后再入库
  return (await res.json()).response.replace(/<think>[\s\S]*?<\/think>/g, '').trim();
}

async function processOne() {
  const article = nextPending();
  if (!article) return false;
  try {
    const { text } = await fetchWithGuard(article.url);
    const { title, image, body } = extractHtml(text);
    if (!body || body.length < 30) throw Object.assign(new Error('未提取到有效正文，可在详情页手动粘贴'), { code: 40002 });
    update(article.id, { title, image_url: image, content: body, reading_minutes: calcReadingMinutes(body), status: 'fetched' });
    try {
      const summary = await summarize(body);
      update(article.id, { summary, status: 'done' });
    } catch (e) {
      update(article.id, { status: 'failed', fail_reason: `摘要生成失败：${e.message}` });
    }
  } catch (e) {
    update(article.id, { status: 'failed', fail_reason: e.message || '抓取失败' });
  }
  return true;
}

export function startWorker(intervalMs = 1000) {
  requeueStuck(); // 重启丢任务补偿（TRD 决策1）
  setInterval(async () => {
    if (running) return;
    running = true;
    try {
      await processOne();
    } finally {
      running = false;
    }
  }, intervalMs);
  console.log(`[worker] 摘要队列已启动（模型 ${MODEL} @ ${OLLAMA_URL}）`);
}

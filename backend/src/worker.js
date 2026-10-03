// 摘要 worker：串行队列（TRD 决策1），本地 Ollama 生成摘要
import { nextPending, update, getArticle, requeueStuck } from './db.js';
import { fetchWithGuard, extractHtml, calcReadingMinutes } from './fetch.js';

const OLLAMA_URL = process.env.OLLAMA_URL || 'http://127.0.0.1:11434';
const MODEL = process.env.OLLAMA_MODEL || 'qwen2.5:3b';
const FETCH_TIMEOUT = 8000;   // Spec S1.9
// Spec S2.3 = 30s（按 qwen2.5:3b 定）；当前跑 deepseek-r1:7b 推理模型需放宽，用环境变量覆盖
const SUMMARY_TIMEOUT = Number(process.env.SUMMARY_TIMEOUT_MS) || 30000;

let running = false;

async function summarize(text) {
  const res = await fetch(`${OLLAMA_URL}/api/generate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    signal: AbortSignal.timeout(SUMMARY_TIMEOUT),
    body: JSON.stringify({
      model: MODEL,
      prompt: `请用不超过 120 字总结以下文章的核心要点，直接输出总结，不要客套话：\n\n${text.slice(0, 3000)}`,
      stream: false,
      options: { num_predict: 500 }, // 上限防失控（推理模型 think 块可能很长）
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
      const reason = /timeout|aborted/i.test(e.message) ? `摘要生成超时（模型 ${MODEL} 未在限时内完成，长文可稍后重试）` : `摘要生成失败：${e.message}`;
      update(article.id, { status: 'failed', fail_reason: reason });
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

// API 客户端：严格对应 docs/03-API.md v0.2
const base = '/api/v1';
async function req(path, options = {}) {
  const res = await fetch(base + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  const json = await res.json().catch(() => ({ code: 50001, message: '响应解析失败' }));
  if (json.code !== 0) throw new Error(json.message || `请求失败(${json.code})`);
  return json.data;
}

export const saveArticle = (url) => req('/articles', { method: 'POST', body: { url } });
export const listArticles = (keyword = '', page = 1) =>
  req(`/articles?page=${page}&size=20${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`);
export const getArticle = (id) => req(`/articles/${id}`);
export const putContent = (id, content) => req(`/articles/${id}/content`, { method: 'PUT', body: { content } });
export const retryArticle = (id) => req(`/articles/${id}/retry`, { method: 'POST' });
export const deleteArticle = (id) => req(`/articles/${id}`, { method: 'DELETE' });

export const fmtDate = (iso) => (iso || '').slice(0, 10);
export const siteOf = (url) => {
  try { return new URL(url).hostname.replace(/^www\./, ''); } catch { return ''; }
};

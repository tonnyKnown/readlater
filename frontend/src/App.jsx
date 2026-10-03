import React, { useEffect, useRef, useState } from 'react';
import { saveArticle, listArticles, getArticle, putContent, retryArticle, deleteArticle, fmtDate, siteOf } from './api.js';

const BADGE = {
  pending: { cls: 'b-pending', text: '· 排队等待' },
  fetched: { cls: 'b-fetched', text: '⏳ 摘要生成中' },
  done: { cls: 'b-done', text: '✓ 摘要完成' },
  failed: { cls: 'b-failed', text: '✗ 抓取失败' },
};
const Badge = ({ status }) => {
  const b = BADGE[status] || BADGE.pending;
  return <span className={`badge ${b.cls}`}>{b.text}</span>;
};

function ListPage({ onOpen }) {
  const [url, setUrl] = useState('');
  const [keyword, setKeyword] = useState('');
  const [items, setItems] = useState(null);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  const load = (kw) =>
    listArticles(kw)
      .then((d) => { setItems(d.items); setTotal(d.total); })
      .catch((e) => setError(e.message));

  useEffect(() => { load(''); }, []);
  // 列表自动刷新：有进行中任务时每 3 秒拉一次（对应详情页 2 秒轮询的列表侧简化版）
  useEffect(() => {
    if (!items?.some((a) => a.status === 'pending' || a.status === 'fetched')) return;
    const t = setInterval(() => load(keyword), 3000);
    return () => clearInterval(t);
  }, [items, keyword]);

  const save = async () => {
    setError('');
    if (!url.trim()) return;
    setSaving(true);
    try {
      await saveArticle(url.trim());
      setUrl('');
      await load(keyword);
    } catch (e) {
      setError(e.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <div className="save-box">
        <input
          placeholder="粘贴文章链接，如 https://example.com/post/123"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && save()}
        />
        <button className="btn" onClick={save} disabled={saving || !url.trim()}>
          {saving ? '保存中…' : '保存并摘要'}
        </button>
      </div>
      {error && <div className="msg-error">{error}</div>}
      <div className="hint">支持 http/https 公网链接 · 反爬文章可保存后手动粘贴正文</div>

      <div className="search-row">
        <input
          placeholder="🔍 搜索标题与摘要…"
          value={keyword}
          onChange={(e) => { setKeyword(e.target.value); load(e.target.value); }}
        />
      </div>

      {items === null ? (
        <div className="empty"><div className="big">⏳</div>加载中…</div>
      ) : items.length === 0 ? (
        <div className="empty">
          <div className="big">🔍</div>
          {keyword ? <>没有匹配的文章<br /><span style={{ fontSize: 13 }}>换个关键词试试，或粘贴一个新链接开始</span></> : <>还没有文章<br /><span style={{ fontSize: 13 }}>在上方粘贴一篇文章链接开始</span></>}
        </div>
      ) : (
        items.map((a) => (
          <div className="card" key={a.id} onClick={() => onOpen(a.id)}>
            <div className="card-top">
              <h3>{a.title || siteOf(a.url) || '(未命名)'}</h3>
              <Badge status={a.status} />
            </div>
            <p>{a.summary || '摘要尚未生成…'}</p>
            <div className="meta">
              {fmtDate(a.created_at)} · {siteOf(a.url)}
              {a.status === 'done' && a.reading_minutes ? ` · 阅读约 ${a.reading_minutes} 分钟` : ''}
            </div>
          </div>
        ))
      )}
      {total > 0 && <div className="hint">共 {total} 篇</div>}
    </>
  );
}

function DetailPage({ id, onBack }) {
  const [a, setA] = useState(null);
  const [pasteOpen, setPasteOpen] = useState(false);
  const [pasteText, setPasteText] = useState('');
  const [error, setError] = useState('');
  const timer = useRef(null);

  const load = () =>
    getArticle(id)
      .then(setA)
      .catch((e) => setError(e.message));

  useEffect(() => { load(); }, [id]);
  // Spec：每 2 秒轮询详情接口直至 status ∈ {done, failed}
  useEffect(() => {
    if (a && (a.status === 'pending' || a.status === 'fetched')) {
      timer.current = setInterval(load, 2000);
    }
    return () => clearInterval(timer.current);
  }, [a?.status, id]);

  if (error) return <div className="msg-error">{error}</div>;
  if (!a) return <div className="empty"><div className="big">⏳</div>加载中…</div>;

  const retry = () => retryArticle(a.id).then(load).catch((e) => setError(e.message));
  const paste = () =>
    putContent(a.id, pasteText)
      .then(() => { setPasteOpen(false); setPasteText(''); load(); })
      .catch((e) => setError(e.message));
  const del = () => { if (confirm('确定删除这篇文章吗？（不可恢复）')) deleteArticle(a.id).then(onBack).catch((e) => setError(e.message)); };

  return (
    <>
      <span className="back" onClick={onBack}>← 返回列表</span>
      <div className="article">
        <h2>{a.title || '(未命名)'}</h2>
        <div className="src">
          {a.url} · 保存于 {fmtDate(a.created_at)}
          {a.status === 'done' && a.reading_minutes ? ` · 阅读约 ${a.reading_minutes} 分钟` : ''}
        </div>

        {a.status === 'failed' && (
          <div className="fail-box">
            <b>摘要生成失败</b>：{a.fail_reason || '未知原因'}
            <div className="row">
              <button className="btn" onClick={retry}>重试抓取</button>
              <button className="btn btn-ghost" onClick={() => setPasteOpen(!pasteOpen)}>粘贴正文</button>
              <button className="btn btn-ghost" onClick={del}>删除</button>
            </div>
            {pasteOpen && (
              <div>
                <textarea placeholder="粘贴文章正文（仅失败状态可粘贴，粘贴后自动重新生成摘要）" value={pasteText} onChange={(e) => setPasteText(e.target.value)} />
                <button className="btn" onClick={paste} disabled={!pasteText.trim()}>提交正文并生成摘要</button>
              </div>
            )}
          </div>
        )}

        {(a.status === 'done' || a.summary) && (
          <div className="ai-block">
            <div className="label">AI 摘要 · 本地模型生成</div>
            <div className="summary">{a.summary}</div>
          </div>
        )}
        {(a.status === 'pending' || a.status === 'fetched') && (
          <div className="ai-block">
            <div className="label">{a.status === 'pending' ? '排队等待抓取' : '正文已抓取，正在生成摘要'}</div>
            <div className="ai-skeleton" style={{ width: '95%' }} />
            <div className="ai-skeleton" style={{ width: '88%' }} />
            <div className="ai-skeleton" style={{ width: '60%' }} />
          </div>
        )}

        {a.content ? (
          <div className="content"><p>{a.content.length > 8000 ? a.content.slice(0, 8000) + ' …（内容过长已截断）' : a.content}</p></div>
        ) : (
          a.status !== 'failed' && <div className="content" style={{ color: 'var(--c-text-2)' }}><p>正文抓取中…</p></div>
        )}
      </div>
    </>
  );
}

export default function App() {
  const [view, setView] = useState({ page: 'list' });
  return (
    <div className="wrap">
      <header>
        <div className="logo">读</div>
        <div>
          <h1>稍后读</h1>
          <div className="sub">ReadLater · 保存链接，本地 AI 生成摘要</div>
        </div>
      </header>
      {view.page === 'list'
        ? <ListPage onOpen={(id) => { setView({ page: 'detail', id }); scrollTo(0, 0); }} />
        : <DetailPage id={view.id} onBack={() => setView({ page: 'list' })} />}
    </div>
  );
}

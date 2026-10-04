import React, { useEffect, useRef, useState } from 'react';
import { saveArticle, listArticles, getArticle, putContent, retryArticle, deleteArticle, fmtDate, siteOf } from './api.js';

/* ── 线条图标（与高保真原型 v0.3 同一组 SVG path） ─────────── */
const Svg = ({ children, dash }) => (
  <svg className="ic" viewBox="0 0 24 24" aria-hidden="true" strokeDasharray={dash}>
    {children}
  </svg>
);
const IcInfo = () => <Svg><circle cx="12" cy="12" r="9" /><path d="M12 16v-4M12 8h.01" /></Svg>;
const IcSearch = () => <Svg><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></Svg>;
const IcAlert = () => <Svg><path d="M12 7v6M12 17h.01" /><circle cx="12" cy="12" r="9" /></Svg>;
const IcCheck = () => <Svg><path d="m5 12.5 4.5 4.5L19 7.5" /></Svg>;
const IcPending = () => <Svg><circle cx="12" cy="12" r="8" /><circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none" /></Svg>;
const IcFetched = () => <Svg dash="14 8"><circle cx="12" cy="12" r="8" /></Svg>;
const IcBack = () => <Svg><path d="M19 12H5M11 18l-6-6 6-6" /></Svg>;
const IcLink = () => <Svg><path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7" /><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7" /></Svg>;
const IcSparkle = () => <Svg><path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9L12 3Z" /><path d="M18.5 16.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7.7-1.8Z" /></Svg>;
const IcClock = () => <Svg><circle cx="12" cy="12" r="9" /><path d="M12 8v4l2.5 2.5" /></Svg>;
const IcRefresh = () => <Svg><path d="M20 11a8 8 0 1 0-2.3 5.7" /><path d="M20 4v7h-7" /></Svg>;
const IcSun = () => <Svg><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></Svg>;
const IcMoon = () => <Svg><path d="M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5Z" /></Svg>;

/* 状态机四态：图标 + 文字双编码 */
const STATUS = {
  pending: { cls: 's-pending', text: '排队等待', Icon: IcPending },
  fetched: { cls: 's-fetched', text: '摘要生成中', Icon: IcFetched },
  done:    { cls: 's-done',    text: '摘要完成', Icon: IcCheck },
  failed:  { cls: 's-failed',  text: '抓取失败', Icon: IcAlert },
};
const StatusPill = ({ status }) => {
  const s = STATUS[status] || STATUS.pending;
  return (
    <span className={`status ${s.cls}`}>
      <s.Icon />
      {s.text}
    </span>
  );
};

/* ── 时间工具 ───────────────────────────────────────────── */
const parseTime = (iso) => {
  if (!iso) return null;
  const d = new Date(iso.length === 19 ? iso.replace(' ', 'T') : iso);
  return Number.isNaN(d.getTime()) ? null : d;
};
const pad2 = (n) => String(n).padStart(2, '0');
const mdDate = (iso) => {
  const d = parseTime(iso);
  return d ? `${d.getMonth() + 1}月${d.getDate()}日` : '—';
};
const hmTime = (iso) => {
  const d = parseTime(iso);
  return d ? `${pad2(d.getHours())}:${pad2(d.getMinutes())}` : '';
};
const fmtDateTime = (iso) => `${fmtDate(iso)} ${hmTime(iso)}`.trim();
const linkText = (url) => {
  try {
    const u = new URL(url);
    return (u.hostname.replace(/^www\./, '') + u.pathname).replace(/\/$/, '');
  } catch {
    return url;
  }
};

/* ── 主题：默认跟随系统，点击后锁定并记忆 ─────────────────── */
const THEME_KEY = 'readlater-theme';
function useTheme() {
  const [dark, setDark] = useState(() => {
    const saved = localStorage.getItem(THEME_KEY);
    if (saved === 'dark' || saved === 'light') return saved === 'dark';
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  });

  useEffect(() => {
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  }, [dark]);

  useEffect(() => {
    if (localStorage.getItem(THEME_KEY)) return undefined;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = (e) => setDark(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  const toggle = () => {
    setDark((d) => {
      localStorage.setItem(THEME_KEY, d ? 'light' : 'dark');
      return !d;
    });
  };
  return { dark, toggle };
}

/* ── 列表页 ─────────────────────────────────────────────── */
function ListPage({ onOpen }) {
  const [url, setUrl] = useState('');
  const [invalid, setInvalid] = useState(false);
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

  // 搜索防抖 300ms
  useEffect(() => {
    const t = setTimeout(() => load(keyword.trim()), 300);
    return () => clearTimeout(t);
  }, [keyword]);

  // 有进行中任务时每 3 秒自动刷新
  useEffect(() => {
    if (!items?.some((a) => a.status === 'pending' || a.status === 'fetched')) return undefined;
    const t = setInterval(() => load(keyword.trim()), 3000);
    return () => clearInterval(t);
  }, [items, keyword]);

  const save = (e) => {
    e.preventDefault();
    setError('');
    const val = url.trim();
    if (!/^https?:\/\/\S+\.\S+/.test(val)) {
      setInvalid(true);
      return;
    }
    setSaving(true);
    saveArticle(val)
      .then(() => { setUrl(''); setInvalid(false); return load(keyword.trim()); })
      .catch((err) => setError(err.message))
      .finally(() => setSaving(false));
  };

  const dekOf = (a) => {
    if (a.summary) return a.summary;
    if (a.status === 'pending') return '链接已保存，排队等待抓取正文。';
    if (a.status === 'fetched') return '正文已抓取，正在等待本地模型生成摘要。';
    if (a.status === 'failed') return a.fail_reason || '抓取失败，可进入详情页重试或手动粘贴正文。';
    return '摘要尚未生成…';
  };

  return (
    <section aria-label="收藏列表">
      <form className="composer" onSubmit={save} noValidate>
        <div className="composer-row">
          <label className="sr-only" htmlFor="save-input">文章链接</label>
          <input
            className="field"
            id="save-input"
            type="url"
            inputMode="url"
            placeholder="粘贴文章链接，如 https://example.com/post/123"
            value={url}
            aria-invalid={invalid}
            onChange={(e) => { setUrl(e.target.value); setInvalid(false); }}
          />
          <button className="btn" type="submit" disabled={saving} data-loading={saving}>
            {saving ? (<><span className="spin" aria-hidden="true" />已入队…</>) : '保存并摘要'}
          </button>
        </div>
        {error && (
          <div className="form-error" role="alert">
            <IcAlert />
            <span>{error}</span>
          </div>
        )}
      </form>

      <p className="hint">
        <IcInfo />
        <span>支持 http / https 公网链接。反爬站点可先保存，再进入详情页手动粘贴正文。</span>
      </p>

      <div className="toolbar">
        <div className="search">
          <IcSearch />
          <label className="sr-only" htmlFor="search-input">搜索标题与摘要</label>
          <input
            className="field"
            id="search-input"
            type="search"
            placeholder="搜索标题与摘要…"
            autoComplete="off"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
        </div>
        <span className="count" role="status" aria-live="polite">
          {keyword.trim() ? `命中 ${total} 篇` : `${total} 篇`}
        </span>
      </div>

      {items === null ? (
        <div className="list-loading"><span className="spin" aria-hidden="true" />加载中…</div>
      ) : items.length === 0 ? (
        <div className="empty">
          <div className="empty-ic"><IcSearch /></div>
          <h2>{keyword.trim() ? '没有匹配的文章' : '还没有文章'}</h2>
          <p>{keyword.trim() ? '换个关键词试试，或粘贴一个新链接开始收藏。' : '在上方粘贴一篇文章链接开始收藏。'}</p>
        </div>
      ) : (
        <ul className="entries">
          {items.map((a) => (
            <li className="entry" key={a.id} data-status={a.status}>
              <button type="button" className="entry-link" onClick={() => onOpen(a.id)}>
                <div className="meta-rail">
                  <span className="rail-strong">{mdDate(a.created_at)}</span>
                  <span>{hmTime(a.created_at)}</span>
                  <span>{a.status === 'done' && a.reading_minutes ? `${a.reading_minutes} 分钟` : '— 分钟'}</span>
                </div>
                <div className="entry-body">
                  <div className="entry-head">
                    <h3 className="entry-title">{a.title || siteOf(a.url) || '(未命名)'}</h3>
                    <StatusPill status={a.status} />
                  </div>
                  <p className="entry-dek">{dekOf(a)}</p>
                  <div className="entry-foot">{siteOf(a.url)}</div>
                </div>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/* ── 详情页（完成 / 生成中 / 失败 三态） ───────────────────── */
function DetailPage({ id, onBack }) {
  const [a, setA] = useState(null);
  const [error, setError] = useState('');
  const [pasteText, setPasteText] = useState('');
  const [busy, setBusy] = useState(''); // '' | 'retry' | 'paste'
  const timer = useRef(null);

  const load = () =>
    getArticle(id)
      .then(setA)
      .catch((e) => setError(e.message));

  useEffect(() => { load(); }, [id]);

  // Spec：每 2 秒轮询详情直至 status ∈ {done, failed}
  useEffect(() => {
    if (a && (a.status === 'pending' || a.status === 'fetched')) {
      timer.current = setInterval(load, 2000);
    }
    return () => clearInterval(timer.current);
  }, [a?.status, id]);

  if (error) {
    return (
      <>
        <button type="button" className="back" onClick={onBack}><IcBack />返回列表</button>
        <div className="form-error" role="alert"><IcAlert /><span>{error}</span></div>
      </>
    );
  }
  if (!a) {
    return (
      <>
        <button type="button" className="back" onClick={onBack}><IcBack />返回列表</button>
        <div className="list-loading"><span className="spin" aria-hidden="true" />加载中…</div>
      </>
    );
  }

  const processing = a.status === 'pending' || a.status === 'fetched';
  const canPaste = pasteText.trim().length >= 200;

  const doRetry = () => {
    setError('');
    setBusy('retry');
    retryArticle(a.id).then(load).catch((e) => setError(e.message)).finally(() => setBusy(''));
  };
  const doPaste = () => {
    if (!canPaste) return;
    setError('');
    setBusy('paste');
    putContent(a.id, pasteText.trim())
      .then(() => setPasteText(''))
      .then(load)
      .catch((e) => setError(e.message))
      .finally(() => setBusy(''));
  };
  const doDelete = () => {
    if (!window.confirm('确定删除这篇文章吗？（不可恢复）')) return;
    deleteArticle(a.id).then(onBack).catch((e) => setError(e.message));
  };

  const eyebrowSuffix =
    a.status === 'done' && a.reading_minutes ? `阅读约 ${a.reading_minutes} 分钟`
    : a.status === 'fetched' ? '正文已抓取'
    : a.status === 'pending' ? '排队等待抓取'
    : '';

  return (
    <section aria-label="文章详情">
      <button type="button" className="back" onClick={onBack}><IcBack />返回列表</button>

      <article>
        <div className="eyebrow">
          收藏于 {fmtDateTime(a.created_at)}{eyebrowSuffix ? ` · ${eyebrowSuffix}` : ''}
        </div>
        <h1 className="article-title">{a.title || siteOf(a.url) || '(未命名)'}</h1>
        <div className="byline">
          <IcLink />
          <a href={a.url} target="_blank" rel="noopener noreferrer">{linkText(a.url)}</a>
        </div>

        {error && (
          <div className="form-error" role="alert" style={{ marginBlockEnd: 'var(--sp-6)' }}>
            <IcAlert /><span>{error}</span>
          </div>
        )}

        {a.status === 'failed' && (
          <div className="alert" role="alert">
            <div className="alert-head"><IcAlert />摘要生成失败</div>
            <p>{a.fail_reason || '目标站点拒绝抓取，正文为空。你可以重试抓取，或手动粘贴正文后重新生成摘要。'}</p>

            <label className="sr-only" htmlFor="paste">粘贴正文</label>
            <textarea
              className="paste-field"
              id="paste"
              placeholder="在此粘贴正文…（至少 200 字才会重新生成摘要）"
              value={pasteText}
              onChange={(e) => setPasteText(e.target.value)}
            />

            <div className="alert-actions">
              <button type="button" className="btn" onClick={doRetry} disabled={busy === 'retry'} data-loading={busy === 'retry'}>
                {busy === 'retry' ? <><span className="spin" aria-hidden="true" />重试中…</> : (<><IcRefresh />重试抓取</>)}
              </button>
              <button type="button" className="btn btn-ghost" onClick={doPaste} disabled={!canPaste || !!busy} data-loading={busy === 'paste'}>
                {busy === 'paste' ? <><span className="spin" aria-hidden="true" />提交中…</> : '粘贴正文并生成摘要'}
              </button>
              <button type="button" className="btn btn-ghost" onClick={doDelete} disabled={!!busy}>删除</button>
            </div>
          </div>
        )}

        {(a.status === 'done' || (a.summary && !processing)) && (
          <section className="ai-block" aria-label="AI 摘要">
            <div className="ai-head">
              <IcSparkle />
              <span className="eyebrow">AI 摘要 · 本地模型生成</span>
            </div>
            <p className="ai-summary">{a.summary || '摘要尚未生成…'}</p>
          </section>
        )}

        {processing && (
          <section className="ai-block" aria-label="AI 摘要生成中" aria-busy="true">
            <div className="ai-head">
              <IcFetched />
              <span className="eyebrow">{a.status === 'pending' ? '排队等待抓取' : '正在生成摘要'}</span>
            </div>
            <div className="skeleton" aria-hidden="true">
              <div className="sk-line" /><div className="sk-line" />
              <div className="sk-line" /><div className="sk-line" /><div className="sk-line" />
            </div>
            <p className="sk-note" role="status" aria-live="polite">
              <IcClock />
              <span>
                {a.status === 'pending'
                  ? '链接已入队，等待抓取正文。'
                  : '本地模型推理中，预期 30 秒内完成。超时将自动转入失败态。'}
              </span>
            </p>
          </section>
        )}

        <div className="prose prose-body">
          {a.content ? (
            a.content.length > 8000
              ? `${a.content.slice(0, 8000)} …（内容过长已截断）`
              : a.content
          ) : (
            <span className="faded">
              {a.status === 'failed'
                ? '正文区域当前为空。粘贴正文后，摘要流程将从“手动内容”入口重新入队。'
                : '正文抓取中…'}
            </span>
          )}
        </div>
      </article>
    </section>
  );
}

/* ── 应用骨架：报头 + 列表/详情视图 ───────────────────────── */
export default function App() {
  const { dark, toggle } = useTheme();
  const [view, setView] = useState({ page: 'list' });

  const openArticle = (id) => { setView({ page: 'detail', id }); window.scrollTo(0, 0); };
  const backToList = () => { setView({ page: 'list' }); window.scrollTo(0, 0); };

  return (
    <div className="shell">
      <a className="skip" href="#main">跳到主内容</a>

      <header className="masthead">
        <div className="brand">
          <div className="brand-mark" aria-hidden="true">读</div>
          <div>
            <div className="brand-name">稍后读</div>
            <div className="brand-tag">ReadLater · 本地 AI 摘要</div>
          </div>
        </div>
        <div className="mast-actions">
          <button
            type="button"
            className="icon-btn"
            onClick={toggle}
            aria-label={dark ? '切换到浅色模式' : '切换到深色模式'}
          >
            {dark ? <IcSun /> : <IcMoon />}
          </button>
        </div>
      </header>

      <main id="main">
        {view.page === 'list'
          ? <ListPage onOpen={openArticle} />
          : <DetailPage id={view.id} onBack={backToList} />}
      </main>
    </div>
  );
}

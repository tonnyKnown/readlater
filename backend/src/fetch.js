// SSRF 五层校验（Spec S1.5 v0.2）：协议 → 主机名 → IP 归一化 → DNS 解析后 → 重定向后
import net from 'net';
import dns from 'dns/promises';

export function validateUrl(raw) {
  let u;
  try {
    u = new URL(raw);
  } catch {
    return { ok: false, code: 40001, message: 'URL 无法解析' };
  }
  // 第 1 层：协议白名单
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return { ok: false, code: 40001, message: '仅支持 http/https' };
  // 第 2 层：主机名黑名单
  const host = u.hostname.toLowerCase();
  if (host === 'localhost' || host.endsWith('.localhost')) return { ok: false, code: 40001, message: '禁止访问内网地址' };
  // 第 3 层：IP 字面量归一化（含 0.0.0.0、十进制 IP、IPv6 回环）
  const ip = normalizeIpLiteral(host);
  if (ip && isPrivateIp(ip)) return { ok: false, code: 40001, message: '禁止访问内网地址' };
  return { ok: true, url: u };
}

function normalizeIpLiteral(host) {
  if (net.isIPv4(host)) return host;
  if (net.isIPv6(host.replace(/^\[|\]$/g, ''))) return host.replace(/^\[|\]$/g, '');
  if (/^\d+$/.test(host)) {
    // 十进制写法：2130706433 = 127.0.0.1
    const n = Number(host);
    return n <= 0xffffffff ? [24, 16, 8, 0].map((s) => (n >>> s) & 255).join('.') : null;
  }
  return null;
}

function isPrivateIp(ip) {
  if (net.isIPv6(ip)) {
    const v6 = ip.toLowerCase();
    return v6 === '::1' || v6 === '::' || v6.startsWith('fc') || v6.startsWith('fd') || v6.startsWith('fe80');
  }
  const [a, b] = ip.split('.').map(Number);
  return (
    a === 127 || a === 10 || a === 0 ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 169 && b === 254)
  );
}

// 第 4 层：DNS 解析后校验（防 DNS 重绑定）
export async function assertPublicHost(u) {
  const host = u.hostname.replace(/^\[|\]$/g, '');
  const addrs = await dns.lookup(host, { all: true });
  if (!addrs.length) throw Object.assign(new Error('域名无法解析'), { code: 40001 });
  for (const { address } of addrs) {
    if (isPrivateIp(address)) throw Object.assign(new Error('禁止访问内网地址'), { code: 40001 });
  }
}

export async function fetchWithGuard(raw, { timeoutMs = 8000, maxRedirects = 5 } = {}) {
  let current = raw;
  for (let i = 0; i <= maxRedirects; i++) {
    const v = validateUrl(current); // 第 5 层：每次重定向后重新校验
    if (!v.ok) throw Object.assign(new Error(v.message), { code: v.code });
    const res = await fetch(v.url, { redirect: 'manual', signal: AbortSignal.timeout(timeoutMs), headers: { 'User-Agent': 'Mozilla/5.0 (compatible; ReadLater/0.1)' } });
    if ([301, 302, 303, 307, 308].includes(res.status)) {
      current = new URL(res.headers.get('location'), v.url).href;
      continue;
    }
    if (!res.ok) throw Object.assign(new Error(`目标站点返回 HTTP ${res.status}`), { code: 40002 });
    return { url: v.url.href, text: await res.text() };
  }
  throw Object.assign(new Error('重定向次数过多'), { code: 40002 });
}

// 极简正文提取：title 标签 + 去脚本去标签（MVP 级别，不上 readability）
export function extractHtml(html) {
  const title = (html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1] || '').trim().slice(0, 200);
  const image = (html.match(/<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']/i)?.[1] || '').slice(0, 500);
  const body = html
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<style[\s\S]*?<\/style>/gi, '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return { title, image, body: body.slice(0, 1000000) };
}

export const calcReadingMinutes = (content) => Math.max(1, Math.round(content.length / 400));

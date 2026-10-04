package com.readlater.service;

import com.readlater.common.BizException;
import com.readlater.enums.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抓取服务：SSRF 五层校验 + 手动重定向抓取 + HTML 正文提取
 * <p>1:1 移植 Node 版 backend/src/fetch.js，Spec S1.4-S1.9</p>
 */
@Service
public class FetchService {

    private final long fetchTimeoutMs;
    private final int maxRedirects;
    private final HttpClient httpClient;

    private static final String USER_AGENT = "Mozilla/5.0 (compatible; ReadLater/0.1)";

    public FetchService(@Value("${app.fetch-timeout-ms}") long fetchTimeoutMs,
                        @Value("${app.fetch-max-redirects}") int maxRedirects) {
        this.fetchTimeoutMs = fetchTimeoutMs;
        this.maxRedirects = maxRedirects;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(fetchTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER) // 手动重定向，每层都要重校验
                .build();
    }

    // ───────────────────────── SSRF 五层校验（Spec S1.5）─────────────────────────

    /**
     * 第 1-3 层：协议白名单 → 主机名黑名单 → IP 字面量归一化（含十进制 IP、IPv6 回环）
     */
    public void validateUrl(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "URL 无法解析");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅支持 http/https");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "URL 无法解析");
        }
        String hostLower = host.toLowerCase();
        if (hostLower.equals("localhost") || hostLower.endsWith(".localhost")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "禁止访问内网地址");
        }
        InetAddress ip = normalizeIpLiteral(hostLower);
        if (ip != null && isPrivateIp(ip)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "禁止访问内网地址");
        }
    }

    /**
     * 第 4 层：DNS 解析后校验（防 DNS 重绑定），需在每次发起连接前调用
     */
    public void assertPublicHost(URI uri) {
        String host = uri.getHost();
        if (host == null) throw new BizException(ErrorCode.PARAM_ERROR, "URL 无法解析");
        String bare = stripBrackets(host);
        try {
            InetAddress[] addrs = InetAddress.getAllByName(bare);
            if (addrs.length == 0) throw new BizException(ErrorCode.PARAM_ERROR, "域名无法解析");
            for (InetAddress addr : addrs) {
                if (isPrivateIp(addr)) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "禁止访问内网地址");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "域名无法解析");
        }
    }

    private String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /**
     * IP 字面量归一化：标准点分十进制、IPv6、十进制整数（如 2130706433 = 127.0.0.1）
     */
    private InetAddress normalizeIpLiteral(String host) {
        String bare = stripBrackets(host);
        try {
            if (bare.matches("^\\d+$")) {
                long n = Long.parseLong(bare);
                if (n <= 0xFFFFFFFFL) {
                    return InetAddress.getByAddress(new byte[]{
                            (byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n});
                }
                return null;
            }
            if (bare.contains(".") || bare.contains(":")) {
                return InetAddress.getByName(bare);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isPrivateIp(InetAddress addr) {
        if (addr instanceof Inet4Address) {
            byte[] b = addr.getAddress();
            int a = b[0] & 0xFF, c = b[1] & 0xFF;
            return a == 127 || a == 10 || a == 0
                    || (a == 172 && c >= 16 && c <= 31)
                    || (a == 192 && c == 168)
                    || (a == 169 && c == 254);
        }
        if (addr instanceof Inet6Address) {
            String v6 = addr.getHostAddress().toLowerCase();
            return v6.equals("0:0:0:0:0:0:0:1") || v6.equals("::1")
                    || v6.equals("0:0:0:0:0:0:0:0") || v6.equals("::")
                    || v6.startsWith("fc") || v6.startsWith("fd") || v6.startsWith("fe80");
        }
        return false;
    }

    // ───────────────────────── 抓取（手动重定向，每次重定向后重新校验）─────────────────────────

    public record FetchResult(String finalUrl, String html) {}

    public FetchResult fetchWithGuard(String rawUrl) {
        String current = rawUrl;
        for (int i = 0; i <= maxRedirects; i++) {
            validateUrl(current); // 第 1-3 层
            URI uri = toUri(current);
            assertPublicHost(uri); // 第 4 层：DNS 解析后校验
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(fetchTimeoutMs))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> resp;
            try {
                resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new BizException(ErrorCode.FETCH_ERROR, "抓取失败：" + e.getMessage());
            }
            int status = resp.statusCode();
            if (status >= 300 && status < 400) {
                String location = resp.headers().firstValue("location").orElse(null);
                if (location == null) throw new BizException(ErrorCode.FETCH_ERROR, "目标站点返回 HTTP " + status);
                current = uri.resolve(location).toString();
                continue; // 第 5 层：重定向后重新校验
            }
            if (status != 200) {
                throw new BizException(ErrorCode.FETCH_ERROR, "目标站点返回 HTTP " + status);
            }
            return new FetchResult(current, resp.body());
        }
        throw new BizException(ErrorCode.FETCH_ERROR, "重定向次数过多");
    }

    private URI toUri(String url) {
        try {
            return new URI(url);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "URL 无法解析");
        }
    }

    // ───────────────────────── HTML 正文提取（极简版，对齐 Node extractHtml）─────────────────────────

    public record ExtractResult(String title, String imageUrl, String body) {}

    private static final Pattern TITLE_PATTERN = Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_IMAGE_PATTERN = Pattern.compile("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("<script[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_PATTERN = Pattern.compile("<style[\\s\\S]*?</style>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    public ExtractResult extractHtml(String html) {
        String title = "";
        Matcher m = TITLE_PATTERN.matcher(html);
        if (m.find()) title = m.group(1).trim();
        title = decodeEntities(title);
        if (title.length() > 200) title = title.substring(0, 200);

        String image = "";
        m = OG_IMAGE_PATTERN.matcher(html);
        if (m.find()) image = m.group(1);
        if (image.length() > 500) image = image.substring(0, 500);

        String body = html;
        body = SCRIPT_PATTERN.matcher(body).replaceAll(" ");
        body = STYLE_PATTERN.matcher(body).replaceAll(" ");
        body = TAG_PATTERN.matcher(body).replaceAll(" ");
        body = decodeEntities(body);
        body = WHITESPACE_PATTERN.matcher(body).replaceAll(" ").trim();
        if (body.length() > 1_000_000) body = body.substring(0, 1_000_000);

        return new ExtractResult(title, image, body);
    }

    /**
     * 实体解码：命名实体 + 十进制/十六进制数字实体（对齐 Node decodeEntities 的 String.fromCodePoint）
     */
    private static final Pattern HEX_ENTITY_PATTERN = Pattern.compile("&#x([0-9a-fA-F]+);");
    private static final Pattern DEC_ENTITY_PATTERN = Pattern.compile("&#(\\d+);");

    private static String decodeEntities(String s) {
        s = replaceCodePoint(HEX_ENTITY_PATTERN, s, 16);
        s = replaceCodePoint(DEC_ENTITY_PATTERN, s, 10);
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&hellip;", "…")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»")
                .replace("&ldquo;", "“")
                .replace("&rdquo;", "”");
    }

    private static String replaceCodePoint(Pattern pattern, String s, int radix) {
        Matcher m = pattern.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement;
            try {
                int cp = Integer.parseInt(m.group(1), radix);
                replacement = Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : m.group(0);
            } catch (NumberFormatException e) {
                replacement = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static int calcReadingMinutes(String content) {
        return Math.max(1, Math.round(content.length() / 400f));
    }
}

package com.readlater.config;

import com.readlater.common.BizException;
import com.readlater.enums.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 限流拦截器：同一 IP 每分钟最多 rate-limit-per-minute 次 /api/** 请求（Spec N1）
 * <p>内存 Map + 每分钟定时清空，与 Node 版行为一致</p>
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final int limitPerMinute;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    public RateLimitInterceptor(@Value("${app.rate-limit-per-minute}") int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        int n = hits.computeIfAbsent(request.getRemoteAddr(), k -> new AtomicInteger()).incrementAndGet();
        if (n > limitPerMinute) {
            throw new BizException(ErrorCode.RATE_LIMIT);
        }
        return true;
    }

    @Scheduled(fixedRate = 60_000)
    public void reset() {
        hits.clear();
    }
}

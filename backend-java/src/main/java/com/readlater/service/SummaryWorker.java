package com.readlater.service;

import com.readlater.common.BizException;
import com.readlater.entity.Article;
import com.readlater.enums.ArticleStatus;
import com.readlater.dao.ArticleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 摘要 Worker：进程内串行队列，每 1s 取一条 pending 处理
 * <p>1:1 移植 Node 版 backend/src/worker.js 的 processOne()/startWorker()</p>
 */
@Service
public class SummaryWorker {

    private static final Logger log = LoggerFactory.getLogger(SummaryWorker.class);

    private final ArticleMapper articleMapper;
    private final FetchService fetchService;
    private final SummaryService summaryService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SummaryWorker(ArticleMapper articleMapper, FetchService fetchService, SummaryService summaryService) {
        this.articleMapper = articleMapper;
        this.fetchService = fetchService;
        this.summaryService = summaryService;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 2000)
    public void tick() {
        if (!running.compareAndSet(false, true)) return; // 串行：上一次未完成则跳过
        try {
            processOne();
        } finally {
            running.set(false);
        }
    }

    private void processOne() {
        Article article = articleMapper.selectNextPending();
        if (article == null) return;
        log.info("[worker] 开始处理文章 id={} url={}", article.getId(), article.getUrl());
        try {
            String body;
            if (ArticleStatus.FETCHED.getValue().equals(article.getStatus())
                    && article.getContent() != null && !article.getContent().isEmpty()) {
                // fetched 且已持有正文（手动粘贴降级 / 重启恢复）：跳过抓取，直接摘要
                body = article.getContent();
            } else {
                FetchService.FetchResult fr = fetchService.fetchWithGuard(article.getUrl());
                FetchService.ExtractResult ex = fetchService.extractHtml(fr.html());
                if (ex.body() == null || ex.body().length() < 30) {
                    throw new BizException(com.readlater.enums.ErrorCode.FETCH_ERROR, "未提取到有效正文，可在详情页手动粘贴");
                }
                articleMapper.updateFetchSuccess(article.getId(), ex.title(), ex.imageUrl(),
                        ex.body(), FetchService.calcReadingMinutes(ex.body()));
                body = ex.body();
            }
            try {
                String summary = summaryService.summarize(body);
                articleMapper.updateSummary(article.getId(), summary);
                log.info("[worker] 文章 id={} 摘要完成", article.getId());
            } catch (Exception e) {
                String reason = isTimeout(e)
                        ? "摘要生成超时（模型 " + summaryService.getModel() + " 未在限时内完成，长文可稍后重试）"
                        : "摘要生成失败：" + e.getMessage();
                articleMapper.updateStatusById(article.getId(), ArticleStatus.FAILED.getValue(), reason);
                log.warn("[worker] 文章 id={} 摘要失败：{}", article.getId(), reason);
            }
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : "抓取失败";
            articleMapper.updateStatusById(article.getId(), ArticleStatus.FAILED.getValue(), reason);
            log.warn("[worker] 文章 id={} 抓取失败：{}", article.getId(), reason);
        }
    }

    private boolean isTimeout(Exception e) {
        String msg = String.valueOf(e.getMessage()).toLowerCase();
        return msg.contains("timeout") || msg.contains("timed out") || msg.contains("aborted");
    }
}

package com.readlater.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.readlater.common.BizException;
import com.readlater.dao.ArticleMapper;
import com.readlater.entity.Article;
import com.readlater.enums.ArticleStatus;
import com.readlater.enums.ErrorCode;
import com.readlater.vo.ArticleListItemVO;
import com.readlater.vo.PageVO;
import com.readlater.vo.SaveResultVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 文章业务编排：去重、状态机流转、参数校验
 * <p>1:1 移植 Node 版 backend/src/server.js 的路由内业务逻辑</p>
 */
@Service
public class ArticleService {

    private final ArticleMapper articleMapper;
    private final FetchService fetchService;

    public ArticleService(ArticleMapper articleMapper, FetchService fetchService) {
        this.articleMapper = articleMapper;
        this.fetchService = fetchService;
    }

    /** 保存文章：URL 校验 → 查重（S1.7）→ 入库 pending 立即返回 */
    public SaveResultVO save(String rawUrl) {
        fetchService.validateUrl(rawUrl);
        String urlHash = sha256Hex(rawUrl);
        Article dup = articleMapper.selectByUrlHash(urlHash);
        if (dup != null) {
            return new SaveResultVO(dup.getId(), dup.getStatus(), true);
        }
        Article article = new Article();
        article.setUrl(rawUrl);
        article.setUrlHash(urlHash);
        article.setTitle("");
        article.setContent("");
        article.setSummary("");
        article.setStatus(ArticleStatus.PENDING.getValue());
        article.setFailReason("");
        article.setImageUrl("");
        article.setReadingMinutes(0);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        article.setCreatedAt(now);
        article.setUpdatedAt(now);
        try {
            articleMapper.insert(article);
        } catch (DuplicateKeyException e) {
            // 并发兜底：唯一索引冲突时回退到查询已有记录
            Article existing = articleMapper.selectByUrlHash(urlHash);
            if (existing != null) {
                return new SaveResultVO(existing.getId(), existing.getStatus(), true);
            }
            throw e;
        }
        return new SaveResultVO(article.getId(), article.getStatus(), false);
    }

    /** 列表：page≥1、size 1-50 默认 20；keyword 匹配标题/摘要/正文（S3.2） */
    public PageVO<ArticleListItemVO> list(long page, long size, String keyword) {
        page = Math.max(1, page);
        size = Math.min(50, Math.max(1, size));
        String kw = keyword == null ? "" : keyword.trim();
        IPage<Article> result = articleMapper.selectPage(new Page<>(page, size), kw);
        return new PageVO<>(result.getTotal(),
                result.getRecords().stream().map(ArticleListItemVO::from).toList());
    }

    /** 详情：不存在抛 40401 */
    public Article mustExist(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return article;
    }

    /** 手动粘贴正文（S1.8）：仅 failed 态，成功后 → fetched 进入摘要队列 */
    public void putContent(Long id, String content) {
        Article article = mustExist(id);
        if (!ArticleStatus.FAILED.getValue().equals(article.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅失败状态可手动粘贴正文");
        }
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty() || trimmed.length() > 1_000_000) {
            throw new BizException(ErrorCode.PARAM_ERROR, "正文为空或超过 100 万字符");
        }
        articleMapper.updateContent(id, trimmed, FetchService.calcReadingMinutes(trimmed));
    }

    /** 重试（S2.4）：仅 failed 态，→ pending 重新入队 */
    public void retry(Long id) {
        Article article = mustExist(id);
        if (!ArticleStatus.FAILED.getValue().equals(article.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅失败状态可重试");
        }
        articleMapper.updateStatusById(id, ArticleStatus.PENDING.getValue(), "");
    }

    /** 物理删除（S3.5） */
    public void delete(Long id) {
        mustExist(id);
        articleMapper.deleteById(id);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

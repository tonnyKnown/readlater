package com.readlater.vo;

import com.readlater.entity.Article;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 详情出参：完整字段（含 content、fail_reason，API 契约 §四.3）
 */
public class ArticleVO {

    private final Long id;
    private final String url;
    private final String title;
    private final String content;
    private final String summary;
    private final String status;
    private final String failReason;
    private final String imageUrl;
    private final Integer readingMinutes;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    private ArticleVO(Article a) {
        this.id = a.getId();
        this.url = a.getUrl();
        this.title = a.getTitle();
        this.content = a.getContent();
        this.summary = a.getSummary();
        this.status = a.getStatus();
        this.failReason = a.getFailReason();
        this.imageUrl = a.getImageUrl();
        this.readingMinutes = a.getReadingMinutes();
        this.createdAt = a.getCreatedAt() == null ? null : a.getCreatedAt().atOffset(ZoneOffset.UTC);
        this.updatedAt = a.getUpdatedAt() == null ? null : a.getUpdatedAt().atOffset(ZoneOffset.UTC);
    }

    public static ArticleVO from(Article a) {
        return new ArticleVO(a);
    }

    public Long getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getSummary() {
        return summary;
    }

    public String getStatus() {
        return status;
    }

    public String getFailReason() {
        return failReason;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public Integer getReadingMinutes() {
        return readingMinutes;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

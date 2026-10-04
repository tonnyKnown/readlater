package com.readlater.vo;

import com.readlater.entity.Article;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 列表项出参：不含 content（省流量，API 契约 §四.2）
 */
public class ArticleListItemVO {

    private final Long id;
    private final String url;
    private final String title;
    private final String summary;
    private final String status;
    private final String imageUrl;
    private final Integer readingMinutes;
    private final OffsetDateTime createdAt;

    private ArticleListItemVO(Article a) {
        this.id = a.getId();
        this.url = a.getUrl();
        this.title = a.getTitle();
        this.summary = a.getSummary();
        this.status = a.getStatus();
        this.imageUrl = a.getImageUrl();
        this.readingMinutes = a.getReadingMinutes();
        this.createdAt = a.getCreatedAt() == null ? null : a.getCreatedAt().atOffset(ZoneOffset.UTC);
    }

    public static ArticleListItemVO from(Article a) {
        return new ArticleListItemVO(a);
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

    public String getSummary() {
        return summary;
    }

    public String getStatus() {
        return status;
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
}

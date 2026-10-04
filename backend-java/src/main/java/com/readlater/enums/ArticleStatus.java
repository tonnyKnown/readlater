package com.readlater.enums;

/**
 * 文章处理状态
 * <p>对应 Node 版 backend/src/db.js 第 17 行：pending|fetched|done|failed</p>
 */
public enum ArticleStatus {
    PENDING("pending"),
    FETCHED("fetched"),
    DONE("done"),
    FAILED("failed");

    private final String value;

    ArticleStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ArticleStatus fromValue(String value) {
        if (value == null) return null;
        for (ArticleStatus s : values()) {
            if (s.value.equals(value)) return s;
        }
        return null;
    }
}

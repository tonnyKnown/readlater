package com.readlater.vo;

import java.util.List;

public class PageVO<T> {

    private final Long total;
    private final List<T> items;

    public PageVO(Long total, List<T> items) {
        this.total = total;
        this.items = items;
    }

    public Long getTotal() {
        return total;
    }

    public List<T> getItems() {
        return items;
    }
}

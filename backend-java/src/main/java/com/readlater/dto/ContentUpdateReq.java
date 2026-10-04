package com.readlater.dto;

import jakarta.validation.constraints.NotBlank;

public class ContentUpdateReq {

    @NotBlank(message = "正文不能为空")
    private String content;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}

package com.readlater.vo;

public class SaveResultVO {

    private final Long id;
    private final String status;
    private final Boolean duplicate;

    public SaveResultVO(Long id, String status, Boolean duplicate) {
        this.id = id;
        this.status = status;
        this.duplicate = duplicate;
    }

    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public Boolean getDuplicate() {
        return duplicate;
    }
}

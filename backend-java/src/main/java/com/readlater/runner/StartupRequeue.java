package com.readlater.runner;

import com.readlater.dao.ArticleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动重入队：把上次退出时卡在 fetched 状态的文章重置回 pending（TRD 决策1）
 */
@Component
public class StartupRequeue implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRequeue.class);

    private final ArticleMapper articleMapper;

    public StartupRequeue(ArticleMapper articleMapper) {
        this.articleMapper = articleMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        int n = articleMapper.requeueStuck();
        if (n > 0) {
            log.info("[startup] 重入队 {} 篇 fetched 状态文章", n);
        }
    }
}

package com.readlater.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.readlater.entity.Article;
import org.apache.ibatis.annotations.Param;

/**
 * 文章数据访问层
 * <p>BaseMapper 提供常用 CRUD；自定义查询（含条件判断）全部在 XML 中定义</p>
 */
public interface ArticleMapper extends BaseMapper<Article> {

    Article selectByUrlHash(@Param("urlHash") String urlHash);

    Article selectNextPending();

    int updateFetchSuccess(@Param("id") Long id,
                           @Param("title") String title,
                           @Param("imageUrl") String imageUrl,
                           @Param("content") String content,
                           @Param("readingMinutes") Integer readingMinutes);

    int updateSummary(@Param("id") Long id, @Param("summary") String summary);

    int updateStatusById(@Param("id") Long id,
                         @Param("status") String status,
                         @Param("failReason") String failReason);

    int updateContent(@Param("id") Long id,
                      @Param("content") String content,
                      @Param("readingMinutes") Integer readingMinutes);

    IPage<Article> selectPage(Page<Article> page, @Param("keyword") String keyword);

    int requeueStuck();
}

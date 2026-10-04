package com.readlater.controller;

import com.readlater.common.Result;
import com.readlater.dto.ArticleCreateReq;
import com.readlater.dto.ContentUpdateReq;
import com.readlater.service.ArticleService;
import com.readlater.vo.ArticleListItemVO;
import com.readlater.vo.ArticleVO;
import com.readlater.vo.PageVO;
import com.readlater.vo.SaveResultVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 文章接口：路径与响应结构严格遵循 docs/tech/API契约.md v0.2
 */
@RestController
@RequestMapping("/api/v1/articles")
public class ArticleController {

    private final ArticleService articleService;

    public ArticleController(ArticleService articleService) {
        this.articleService = articleService;
    }

    /** 1. 保存文章 */
    @PostMapping
    public Result<SaveResultVO> save(@Valid @RequestBody ArticleCreateReq req) {
        return Result.ok(articleService.save(req.getUrl().trim()));
    }

    /** 2. 文章列表（分页 + 关键词搜索） */
    @GetMapping
    public Result<PageVO<ArticleListItemVO>> list(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long size,
                                                  @RequestParam(defaultValue = "") String keyword) {
        return Result.ok(articleService.list(page, size, keyword));
    }

    /** 3. 文章详情（前端 2 秒轮询此接口驱动状态机） */
    @GetMapping("/{id}")
    public Result<ArticleVO> detail(@PathVariable Long id) {
        return Result.ok(ArticleVO.from(articleService.mustExist(id)));
    }

    /** 4. 手动粘贴正文（Spec S1.8 降级） */
    @PutMapping("/{id}/content")
    public Result<Map<String, Object>> putContent(@PathVariable Long id,
                                                  @Valid @RequestBody ContentUpdateReq req) {
        articleService.putContent(id, req.getContent());
        return Result.ok(Map.of("id", id, "status", "fetched"));
    }

    /** 5. 重试（Spec S2.4） */
    @PostMapping("/{id}/retry")
    public Result<Map<String, Object>> retry(@PathVariable Long id) {
        articleService.retry(id);
        return Result.ok(Map.of("id", id, "status", "pending"));
    }

    /** 6. 删除（Spec S3.5 物理删除） */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable Long id) {
        articleService.delete(id);
        return Result.ok(Map.of("deleted", true));
    }
}

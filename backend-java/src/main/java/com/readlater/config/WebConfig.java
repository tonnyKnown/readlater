package com.readlater.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Web 层配置：CORS 白名单、限流拦截器注册、SPA 静态资源托管（生产模式）
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private final RateLimitInterceptor rateLimitInterceptor;
    private final String[] corsOrigins;
    private final String distPath;

    public WebConfig(RateLimitInterceptor rateLimitInterceptor,
                     @Value("${app.cors-origins}") String corsOrigins,
                     @Value("${app.dist-path}") String distPath) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.corsOrigins = corsOrigins.split(",");
        this.distPath = resolveDistPath(distPath);
    }

    /**
     * 解析前端构建目录：兼容不同的启动工作目录。
     * 例如配置为 ../frontend/dist 时：
     * - 从 backend-java 目录启动（mvn spring-boot:run / java -jar）→ userDir/../frontend/dist
     * - 从项目根目录启动（IDEA 模块解析失败时的默认工作目录）→ userDir/frontend/dist
     */
    private static String resolveDistPath(String configured) {
        Path configuredPath = Paths.get(configured);
        if (configuredPath.isAbsolute()) {
            return configuredPath.toString();
        }
        Path userDir = Paths.get("").toAbsolutePath();
        List<Path> candidates = new ArrayList<>(2);
        candidates.add(userDir.resolve(configured));
        if (configured.startsWith("../") || configured.startsWith("..\\")) {
            candidates.add(userDir.resolve(configured.substring(3)));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate.resolve("index.html"))) {
                log.info("前端静态资源目录解析为: {}", candidate);
                return candidate.toAbsolutePath().toString();
            }
        }
        log.warn("未找到前端构建目录（缺少 index.html），候选路径: {}，请检查 app.dist-path 配置（当前值: {}）",
                candidates, configured);
        return configuredPath.toString();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(corsOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**");
    }

    /** 根路径转发到 index.html（本前端无 URL 路由，仅 / 需要；forward 后由静态资源链返回文件） */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /**
     * 生产模式托管 frontend/dist：存在的静态文件直接返回，
     * 非 /api/** 的其余路径 fallback 到 index.html（对齐 Node 版 setNotFoundHandler）
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String locationUri = Paths.get(distPath).toUri().toString();
        if (!locationUri.endsWith("/")) {
            locationUri = locationUri + "/";
        }
        registry.addResourceHandler("/**")
                .addResourceLocations(locationUri)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        if (resourcePath.startsWith("api/")) {
                            return null; // 交给 NoResourceFoundException → 40401
                        }
                        Resource requested = location.createRelative(resourcePath);
                        if (!resourcePath.isEmpty() && requested.exists() && requested.isReadable()) {
                            return requested; // 实际静态文件（js/css/图标）
                        }
                        // 根路径与 SPA 路由 fallback 到 index.html（对齐 Node 版 setNotFoundHandler）
                        Resource index = location.createRelative("index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}

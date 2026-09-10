package com.parkenergyplatform.config;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
                    if ("OPTIONS".equalsIgnoreCase(SaHolder.getRequest().getMethod())) {
                        return;
                    }
                    SaRouter.match("/api/**")
                            .notMatch("/api/platform/auth/login")
                            .notMatch("/api/platform/edge/config")
                            .notMatch("/api/platform/edge/config/**")
                            .check(r -> StpUtil.checkLogin());
                }))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/platform/auth/login",
                        "/api/platform/edge/config",
                        "/api/platform/edge/config/**"
                );
    }
}

package com.workorder.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/login",
                        "/api/users/register",
                        // 部门下拉（2026-10-08）：注册页要在**注册之前**选部门，那时没有会话。
                        // 只暴露 enabled=1 的 id + 名称；取舍见 docs/DECISIONS.md D109。
                        "/api/depts"
                );
    }
}

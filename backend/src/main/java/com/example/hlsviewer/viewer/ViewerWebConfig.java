package com.example.hlsviewer.viewer;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ViewerWebConfig implements WebMvcConfigurer {
  private final ViewerAuthInterceptor viewerAuthInterceptor;

  public ViewerWebConfig(ViewerAuthInterceptor viewerAuthInterceptor) {
    this.viewerAuthInterceptor = viewerAuthInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(viewerAuthInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns(
            "/api/config",
            "/api/health",
            "/api/nice/**",
            "/api/admin/**",
            "/api/auth/**"
        );
  }
}


package com.daumjari.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.daumjari.demo.DemoExpiryInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final DemoExpiryInterceptor demoExpiryInterceptor;

	public WebConfig(DemoExpiryInterceptor demoExpiryInterceptor) {
		this.demoExpiryInterceptor = demoExpiryInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// 새 체험 시작·로그인·로그아웃·내 정보는 만료 후에도 쓸 수 있어야 다시 체험할 수 있다
		registry.addInterceptor(demoExpiryInterceptor)
			.addPathPatterns("/api/**")
			.excludePathPatterns("/api/demo/start", "/api/auth/**");
	}

}

package com.daumjari.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.daumjari.demo.DemoExpiryInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final DemoExpiryInterceptor demoExpiryInterceptor;

	public WebConfig(DemoExpiryInterceptor demoExpiryInterceptor) {
		this.demoExpiryInterceptor = demoExpiryInterceptor;
	}

	/** Spring Security 보다 먼저: 토큰 없는 직접 호출은 인증·CSRF 판단 전에 403. */
	@Bean
	FilterRegistrationBean<ProxyTokenFilter> proxyTokenFilter(
			@Value("${daumjari.proxy.shared-secret:}") String sharedSecret) {
		FilterRegistrationBean<ProxyTokenFilter> registration = new FilterRegistrationBean<>(
				new ProxyTokenFilter(sharedSecret));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
		return registration;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// 새 체험 시작·로그인·로그아웃·내 정보는 만료 후에도 쓸 수 있어야 다시 체험할 수 있다
		registry.addInterceptor(demoExpiryInterceptor)
			.addPathPatterns("/api/**")
			.excludePathPatterns("/api/demo/start", "/api/auth/**");
	}

}

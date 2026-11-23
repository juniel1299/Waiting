package com.daumjari.config;

import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import com.daumjari.account.AccountRepository;
import com.daumjari.auth.WithdrawnAccountFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 서버 세션 인증. 세션 쿠키(JSESSIONID) + 쿠키 기반 CSRF(XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로).
 * 권한은 세션의 SessionUser 역할로만 판단하고 요청 본문의 사용자 ID·역할은 쓰지 않는다.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokenRepository,
			SecurityContextRepository securityContextRepository, AccountRepository accounts) throws Exception {
		http
			// 탈퇴한 계정의 남은 세션은 권한 판단 전에 인증을 비워 401 로 끝낸다
			.addFilterBefore(new WithdrawnAccountFilter(accounts), AuthorizationFilter.class)
			.csrf(csrf -> csrf
				.csrfTokenRepository(csrfTokenRepository)
				.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
			.securityContext(context -> context.securityContextRepository(securityContextRepository))
			.authorizeHttpRequests(auth -> auth
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/signup").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/demo/start").permitAll()
				// 서비스 공지는 비회원도 읽는다. 글 상세는 회원 게시판 글이면 서비스가 로그인 여부를 다시 확인한다
				.requestMatchers(HttpMethod.GET, "/api/boards/notice/posts", "/api/posts/*", "/api/posts/*/comments").permitAll()
				.requestMatchers("/api/operator/**").hasRole("OPERATOR")
				.requestMatchers("/api/sessions/*/reservations", "/api/sessions/*/waitlist", "/api/reservations/**", "/api/me/reservations",
						"/api/demo/sample-cancel")
				.hasRole("PARTICIPANT")
				.anyRequest().authenticated())
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint((request, response, e) -> writeError(response, HttpStatus.UNAUTHORIZED,
						"UNAUTHENTICATED", "로그인이 필요합니다."))
				.accessDeniedHandler((request, response, e) -> {
					if (e instanceof CsrfException) {
						writeError(response, HttpStatus.FORBIDDEN, "CSRF_INVALID", "보안 토큰이 없거나 만료되었습니다.");
						return;
					}
					writeError(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다.");
				}))
			.logout(logout -> logout
				.logoutUrl("/api/auth/logout")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));
		return http.build();
	}

	@Bean
	CsrfTokenRepository csrfTokenRepository(@Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
		// 프런트(JS)가 쿠키 값을 읽어 헤더로 보내야 하므로 HttpOnly 해제
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		// HTTPS 배포에서는 세션 쿠키와 같이 Secure (SESSION_COOKIE_SECURE=true)
		repository.setCookieCustomizer(cookie -> cookie.secure(secure));
		return repository;
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
		return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(),
				new CsrfAuthenticationStrategy(csrfTokenRepository)));
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
			PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		return new ProviderManager(provider);
	}

	// 필터 단계 응답이라 고정 문자열만 쓴다 (사용자 입력 미포함)
	private static void writeError(HttpServletResponse response, HttpStatus status, String code, String message)
			throws IOException {
		response.setStatus(status.value());
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
	}

}

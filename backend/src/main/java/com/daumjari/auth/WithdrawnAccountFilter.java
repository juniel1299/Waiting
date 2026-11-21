package com.daumjari.auth;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.daumjari.account.AccountRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 탈퇴한 계정의 남은 세션(다른 기기·브라우저)을 끊는다. 세션 저장소(메모리·JDBC)와 무관하게 요청마다 계정 상태를 확인해,
 * 탈퇴했으면 세션을 무효화하고 인증을 비운다. 그러면 보호된 API 는 기존 진입점이 401 UNAUTHENTICATED 로 답한다.
 * 데모 방문자는 탈퇴할 수 없어 확인하지 않는다 (만료는 DemoExpiryInterceptor 가 410 으로 처리).
 * SecurityConfig 에서만 등록한다 (빈으로 만들면 서블릿 필터로도 한 번 더 걸린다).
 */
public class WithdrawnAccountFilter extends OncePerRequestFilter {

	private final AccountRepository accounts;

	public WithdrawnAccountFilter(AccountRepository accounts) {
		this.accounts = accounts;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof SessionUser user && !user.isDemo()
				&& accounts.existsByIdAndDeletedAtIsNotNull(user.id())) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				session.invalidate();
			}
			SecurityContextHolder.clearContext();
		}
		chain.doFilter(request, response);
	}

}

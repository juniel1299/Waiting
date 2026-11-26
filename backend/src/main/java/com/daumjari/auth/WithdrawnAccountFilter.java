package com.daumjari.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 요청마다 계정 상태를 다시 읽는다. 세션 저장소(메모리·JDBC)와 무관하게:
 * - 탈퇴·이용 정지면 세션을 무효화하고 인증을 비운다(보호된 API 는 기존 진입점이 401).
 * - 서비스 역할(운영자 여부·개발자 역할)이 세션에 담긴 것과 다르면 이번 요청의 인증을 지금 역할로 바꾼다.
 *   그래서 역할 부여·회수가 다시 로그인하지 않아도 같은 세션의 다음 요청부터 반영된다.
 * 데모 방문자는 탈퇴·역할 변경이 없어 확인하지 않는다 (만료는 DemoExpiryInterceptor 가 410 으로 처리).
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
		if (auth != null && auth.getPrincipal() instanceof SessionUser user && !user.isDemo()) {
			AccountRepository.AccessState state = accounts.accessState(user.id()).orElse(null);
			if (state == null || state.getBlocked()) {
				HttpSession session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
				SecurityContextHolder.clearContext();
			}
			else {
				refreshRoles(auth, user, state);
			}
		}
		chain.doFilter(request, response);
	}

	private static void refreshRoles(Authentication auth, SessionUser user, AccountRepository.AccessState state) {
		SessionUser current = new SessionUser(user.id(), user.loginId(), user.displayName(), Role.valueOf(state.getRole()),
				user.demoTrialId());
		List<GrantedAuthority> authorities = AccountUserDetails.authoritiesOf(current, state.getDeveloper());
		if (current.equals(user) && authorities.equals(List.copyOf(auth.getAuthorities()))) {
			return;
		}
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(current, null, authorities));
		SecurityContextHolder.setContext(context);
	}

}

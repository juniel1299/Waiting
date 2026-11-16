package com.daumjari.auth;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 인증된 사용자로 서버 세션을 연다. 비밀번호 로그인과 데모 체험 시작이 같이 쓴다. */
@Component
public class SessionLogin {

	private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

	private final SecurityContextRepository securityContextRepository;

	private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

	public SessionLogin(SessionAuthenticationStrategy sessionAuthenticationStrategy,
			SecurityContextRepository securityContextRepository) {
		this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
		this.securityContextRepository = securityContextRepository;
	}

	public void establish(SessionUser user, HttpServletRequest request, HttpServletResponse response) {
		Authentication sessionAuth = UsernamePasswordAuthenticationToken.authenticated(user, null,
				AccountUserDetails.authoritiesOf(user));
		// 세션 고정 방지(세션 ID 교체) + CSRF 토큰 재발급
		sessionAuthenticationStrategy.onAuthentication(sessionAuth, request, response);
		SecurityContext context = contextHolder.createEmptyContext();
		context.setAuthentication(sessionAuth);
		contextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
	}

}

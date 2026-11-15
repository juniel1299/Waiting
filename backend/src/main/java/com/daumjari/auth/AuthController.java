package com.daumjari.auth;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.AuthDtos.CsrfResponse;
import com.daumjari.auth.AuthDtos.LoginRequest;
import com.daumjari.auth.AuthDtos.MeResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/** 로그아웃은 Spring Security LogoutFilter 가 POST /api/auth/logout 으로 처리한다. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthenticationManager authenticationManager;

	private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

	private final SecurityContextRepository securityContextRepository;

	private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

	public AuthController(AuthenticationManager authenticationManager,
			SessionAuthenticationStrategy sessionAuthenticationStrategy,
			SecurityContextRepository securityContextRepository) {
		this.authenticationManager = authenticationManager;
		this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
		this.securityContextRepository = securityContextRepository;
	}

	/** 토큰을 읽으면 XSRF-TOKEN 쿠키가 발급된다. 프런트는 로그인 전에 한 번 호출한다. */
	@GetMapping("/csrf")
	public CsrfResponse csrf(CsrfToken token) {
		return new CsrfResponse(token.getHeaderName(), token.getToken());
	}

	@PostMapping("/login")
	public MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		Authentication verified = authenticationManager
			.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.loginId(), body.password()));
		SessionUser user = ((AccountUserDetails) verified.getPrincipal()).toSessionUser();
		Authentication sessionAuth = UsernamePasswordAuthenticationToken.authenticated(user, null,
				AccountUserDetails.authoritiesOf(user));

		// 세션 고정 방지(세션 ID 교체) + CSRF 토큰 재발급
		sessionAuthenticationStrategy.onAuthentication(sessionAuth, request, response);
		SecurityContext context = contextHolder.createEmptyContext();
		context.setAuthentication(sessionAuth);
		contextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
		return MeResponse.from(user);
	}

	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal SessionUser user) {
		return MeResponse.from(user);
	}

}

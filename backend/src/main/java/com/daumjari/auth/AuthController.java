package com.daumjari.auth;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import com.daumjari.account.Account;
import com.daumjari.auth.AuthDtos.SignupRequest;
import com.daumjari.auth.AuthDtos.SignupResponse;

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

	private final SessionLogin sessionLogin;

	private final LoginThrottle loginThrottle;

	private final SignupService signupService;

	public AuthController(AuthenticationManager authenticationManager, SessionLogin sessionLogin,
			LoginThrottle loginThrottle, SignupService signupService) {
		this.authenticationManager = authenticationManager;
		this.sessionLogin = sessionLogin;
		this.loginThrottle = loginThrottle;
		this.signupService = signupService;
	}

	/** 토큰을 읽으면 XSRF-TOKEN 쿠키가 발급된다. 프런트는 로그인 전에 한 번 호출한다. */
	@GetMapping("/csrf")
	public CsrfResponse csrf(CsrfToken token) {
		return new CsrfResponse(token.getHeaderName(), token.getToken());
	}

	@PostMapping("/login")
	public MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		// 클라이언트 IP: Tomcat RemoteIpValve 가 신뢰 프록시에서 온 요청일 때만 X-Forwarded-For 를 반영한 값
		String clientIp = request.getRemoteAddr();
		loginThrottle.countAttempt(body.loginId(), clientIp);
		Authentication verified = authenticationManager
			.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.loginId(), body.password()));
		loginThrottle.onSuccess(body.loginId(), clientIp);
		SessionUser user = ((AccountUserDetails) verified.getPrincipal()).toSessionUser();
		sessionLogin.establish(user, request, response);
		return MeResponse.from(user);
	}

	/** 가입만 하고 로그인은 하지 않는다. 화면은 로그인으로 안내한다. */
	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public SignupResponse signup(@RequestBody SignupRequest body, HttpServletRequest request) {
		Account account = signupService.signup(body.loginId(), body.password(), body.passwordConfirm(),
				request.getRemoteAddr());
		return new SignupResponse(account.getId(), account.getLoginId(), account.getRole());
	}

	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal SessionUser user) {
		return MeResponse.from(user);
	}

}

package com.daumjari.demo;

import java.time.Clock;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.daumjari.auth.SessionUser;
import com.daumjari.common.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 체험이 끝난 방문자의 API 요청은 410 DEMO_EXPIRED. 새 체험 시작·인증 API 는 제외 (WebConfig). */
@Component
public class DemoExpiryInterceptor implements HandlerInterceptor {

	private final DemoTrials trials;

	private final Clock clock;

	public DemoExpiryInterceptor(DemoTrials trials, Clock clock) {
		this.trials = trials;
		this.clock = clock;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !(auth.getPrincipal() instanceof SessionUser user) || !user.isDemo()) {
			return true;
		}
		if (trials.isActive(user.demoTrialId(), Instant.now(clock))) {
			return true;
		}
		ErrorCode code = ErrorCode.DEMO_EXPIRED;
		response.setStatus(HttpStatus.GONE.value());
		response.setContentType("application/json;charset=UTF-8");
		// 고정 문자열만 쓴다 (사용자 입력 미포함)
		response.getWriter().write("{\"code\":\"" + code.name() + "\",\"message\":\"" + code.message() + "\"}");
		return false;
	}

}

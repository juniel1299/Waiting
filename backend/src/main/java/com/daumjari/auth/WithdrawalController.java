package com.daumjari.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
public class WithdrawalController {

	record WithdrawRequest(@NotNull @Size(max = 100) String password) {
	}

	private final WithdrawalService withdrawals;

	public WithdrawalController(WithdrawalService withdrawals) {
		this.withdrawals = withdrawals;
	}

	/** 성공하면 이 세션도 끝낸다. 같은 계정의 다른 세션은 WithdrawnAccountFilter 가 다음 요청에서 끊는다. */
	@PostMapping("/api/me/withdraw")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void withdraw(@AuthenticationPrincipal SessionUser user, @Valid @RequestBody WithdrawRequest body,
			HttpServletRequest request, HttpServletResponse response) {
		// 클라이언트 IP: 로그인과 같은 규칙 (Tomcat RemoteIpValve)
		withdrawals.withdraw(user, body.password(), request.getRemoteAddr());
		new SecurityContextLogoutHandler().logout(request, response,
				SecurityContextHolder.getContext().getAuthentication());
	}

}

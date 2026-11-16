package com.daumjari.auth;

import com.daumjari.account.Role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

final class AuthDtos {

	private AuthDtos() {
	}

	record LoginRequest(@NotBlank @Size(max = 50) String loginId, @NotBlank @Size(max = 100) String password) {
	}

	record MeResponse(Long id, String loginId, String displayName, Role role) {

		static MeResponse from(SessionUser user) {
			return new MeResponse(user.id(), user.loginId(), user.displayName(), user.role());
		}

	}

	record CsrfResponse(String headerName, String token) {
	}

	/** 429 본문. Retry-After 헤더와 같은 값을 retryAfterSeconds 로도 준다. */
	record ThrottledResponse(String code, String message, long retryAfterSeconds) {
	}

}

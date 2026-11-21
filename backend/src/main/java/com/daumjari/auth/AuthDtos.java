package com.daumjari.auth;

import com.daumjari.account.Role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

final class AuthDtos {

	private AuthDtos() {
	}

	record LoginRequest(@NotBlank @Size(max = 50) String loginId, @NotBlank @Size(max = 100) String password) {
	}

	record MeResponse(Long id, String loginId, String displayName, Role role, boolean demo) {

		static MeResponse from(SessionUser user) {
			return new MeResponse(user.id(), user.loginId(), user.displayName(), user.role(), user.isDemo());
		}

	}

	/** 역할은 받지 않는다: 서버가 참가자로 정한다. 형식 검증은 SignupService 가 칸별로 한다. */
	record SignupRequest(String loginId, String password, String passwordConfirm) {
	}

	record SignupResponse(Long id, String loginId, Role role) {
	}

	record CsrfResponse(String headerName, String token) {
	}

}

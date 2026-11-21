package com.daumjari.auth;

import java.io.Serializable;

import org.springframework.security.core.AuthenticatedPrincipal;

import com.daumjari.account.Role;

/** 세션에 저장되는 로그인 사용자. 비밀번호 해시는 담지 않는다. */
/** demoTrialId: 데모 체험 방문자면 체험 ID, 아니면 null. */
public record SessionUser(Long id, String loginId, String displayName, Role role, Long demoTrialId)
		implements Serializable, AuthenticatedPrincipal {

	/**
	 * 인증 이름은 아이디다. 세션 저장소(spring_session.principal_name, varchar(100))에 이 값이 들어간다.
	 * 구현하지 않으면 toString() 전체가 들어가 아이디가 11자 이상이면 저장이 실패했다 (운영 로그인 500).
	 */
	@Override
	public String getName() {
		return loginId;
	}

	public boolean isDemo() {
		return demoTrialId != null;
	}

}

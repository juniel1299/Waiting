package com.daumjari.auth;

import java.io.Serializable;

import com.daumjari.account.Role;

/** 세션에 저장되는 로그인 사용자. 비밀번호 해시는 담지 않는다. */
/** demoTrialId: 데모 체험 방문자면 체험 ID, 아니면 null. */
public record SessionUser(Long id, String loginId, String displayName, Role role, Long demoTrialId)
		implements Serializable {

	public boolean isDemo() {
		return demoTrialId != null;
	}

}

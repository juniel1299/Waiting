package com.daumjari.auth;

import java.io.Serializable;
import java.time.Instant;

/**
 * 서버 세션에 두는 OAuth state 한 건(운영은 JDBC 세션이라 직렬화 가능해야 한다).
 * 공급자·용도(연결/로그인)·연결을 시작한 계정·만료 시각. 콜백에서 꺼내면 바로 지운다(일회용).
 */
public record OAuthState(String provider, String mode, Long accountId, Instant expiresAt) implements Serializable {

	/** 테스트용: 이미 만료된 같은 state. */
	public OAuthState expiredCopy() {
		return new OAuthState(provider, mode, accountId, Instant.EPOCH);
	}

}

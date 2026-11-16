package com.daumjari.auth;

/** 로그인 시도 제한 중. 비밀번호를 확인하기 전에 던진다. */
public class LoginThrottledException extends RuntimeException {

	private final long retryAfterSeconds;

	public LoginThrottledException(long retryAfterSeconds) {
		super("login throttled for " + retryAfterSeconds + "s");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}

}

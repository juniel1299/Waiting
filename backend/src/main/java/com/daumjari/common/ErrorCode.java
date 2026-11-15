package com.daumjari.common;

import org.springframework.http.HttpStatus;

/** 서비스 계층 오류 코드. 인증·CSRF 오류는 SecurityConfig 에서 따로 응답한다. */
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
	SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "회차를 찾을 수 없습니다.");

	private final HttpStatus status;

	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus status() {
		return status;
	}

	public String message() {
		return message;
	}

}

package com.daumjari.common;

import org.springframework.http.HttpStatus;

/** 서비스 계층 오류 코드. 인증·CSRF 오류는 SecurityConfig 에서 따로 응답한다. */
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
	SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "회차를 찾을 수 없습니다."),
	SESSION_FULL(HttpStatus.CONFLICT, "정원이 마감되었습니다."),
	SESSION_STARTED(HttpStatus.CONFLICT, "이미 시작된 회차는 예약하거나 취소할 수 없습니다."),
	SEATS_AVAILABLE(HttpStatus.CONFLICT, "빈자리가 있는 회차는 대기 대신 바로 예약할 수 있습니다."),
	// 다른 사람의 예약도 존재 여부를 드러내지 않도록 같은 코드로 응답한다
	RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다.");

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

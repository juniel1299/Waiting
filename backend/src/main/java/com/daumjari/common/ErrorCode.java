package com.daumjari.common;

import org.springframework.http.HttpStatus;

/** 서비스 계층 오류 코드. 인증·CSRF 오류는 SecurityConfig 에서 따로 응답한다. */
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
	LOGIN_ID_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),
	// 필터 단계의 401 과 같은 코드: 탈퇴와 겹친 요청이 트랜잭션 안에서 막힐 때
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "비밀번호가 올바르지 않습니다."),
	WITHDRAWAL_NOT_ALLOWED(HttpStatus.FORBIDDEN, "운영자·체험 계정은 탈퇴할 수 없습니다."),
	SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "삭제되었거나 찾을 수 없는 회차입니다."),
	NOT_SESSION_OWNER(HttpStatus.FORBIDDEN, "이 회차를 관리할 권한이 없습니다."),
	SESSION_CREATE_FORBIDDEN(HttpStatus.FORBIDDEN, "체험 계정은 회차를 만들 수 없습니다."),
	SESSION_FULL(HttpStatus.CONFLICT, "정원이 마감되었습니다."),
	SESSION_STARTED(HttpStatus.CONFLICT, "이미 시작된 회차는 예약하거나 취소할 수 없습니다."),
	SEATS_AVAILABLE(HttpStatus.CONFLICT, "빈자리가 있는 회차는 대기 대신 바로 예약할 수 있습니다."),
	PROXY_REQUIRED(HttpStatus.FORBIDDEN, "프런트 프록시를 거친 요청만 받습니다."),
	DEMO_NOT_FOUND(HttpStatus.NOT_FOUND, "진행 중인 데모 체험이 없습니다."),
	DEMO_EXPIRED(HttpStatus.GONE, "데모 체험 시간이 끝났습니다. 다시 체험해 주세요."),
	CAPACITY_BELOW_CONFIRMED(HttpStatus.CONFLICT, "현재 확정 인원보다 적게 정원을 줄일 수 없습니다."),
	ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "이용이 정지된 계정입니다. 운영자에게 문의해 주세요."),
	ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "계정을 찾을 수 없습니다."),
	SELF_MODERATION_NOT_ALLOWED(HttpStatus.CONFLICT, "본인 계정은 정지하거나 탈퇴시킬 수 없습니다."),
	LAST_OPERATOR(HttpStatus.CONFLICT, "마지막 운영자 계정은 정지하거나 탈퇴시킬 수 없습니다."),
	ACCOUNT_STATE_CONFLICT(HttpStatus.CONFLICT, "이미 처리되었거나 지금 계정 상태에서는 할 수 없는 조치입니다."),
	BOARD_NOT_FOUND(HttpStatus.NOT_FOUND, "없는 게시판입니다."),
	POST_NOT_FOUND(HttpStatus.NOT_FOUND, "삭제되었거나 찾을 수 없는 글입니다."),
	BOARD_WRITE_FORBIDDEN(HttpStatus.FORBIDDEN, "이 게시판에 글을 쓸 권한이 없습니다."),
	NOT_POST_AUTHOR(HttpStatus.FORBIDDEN, "이 글을 고치거나 지울 권한이 없습니다."),
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

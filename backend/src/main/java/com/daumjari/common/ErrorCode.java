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
	COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "삭제되었거나 찾을 수 없는 댓글입니다."),
	COMMENT_WRITE_FORBIDDEN(HttpStatus.FORBIDDEN, "댓글을 쓰거나 추천할 권한이 없습니다."),
	NOT_COMMENT_AUTHOR(HttpStatus.FORBIDDEN, "이 댓글을 고치거나 지울 권한이 없습니다."),
	REPLY_DEPTH_EXCEEDED(HttpStatus.BAD_REQUEST, "답글에는 답글을 달 수 없습니다."),
	PROFILE_FORBIDDEN(HttpStatus.FORBIDDEN, "체험 계정은 닉네임을 정할 수 없습니다."),
	NICKNAME_TAKEN(HttpStatus.CONFLICT, "이미 다른 회원이 쓰는 닉네임입니다."),
	FEEDBACK_FORBIDDEN(HttpStatus.FORBIDDEN, "체험 계정은 개선 요청을 보낼 수 없습니다."),
	FEEDBACK_NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없는 개선 요청입니다."),
	FEEDBACK_CHANGED(HttpStatus.CONFLICT, "다른 개발자가 먼저 바꿨습니다. 새로 고친 뒤 다시 확인해 주세요."),
	SOCIAL_FORBIDDEN(HttpStatus.FORBIDDEN, "체험 계정은 소셜 계정을 연결할 수 없습니다."),
	SOCIAL_NOT_FOUND(HttpStatus.NOT_FOUND, "연결된 소셜 계정이 없습니다."),
	OAUTH_PROVIDER_NOT_FOUND(HttpStatus.NOT_FOUND, "지원하지 않는 로그인 방식입니다."),
	CHAT_MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없는 메시지입니다."),
	CHAT_DELETE_FORBIDDEN(HttpStatus.FORBIDDEN, "본인 메시지나 모임 운영자만 지울 수 있습니다."),
	CHAT_TOO_FAST(HttpStatus.TOO_MANY_REQUESTS, "메시지를 너무 빨리 보내고 있어요. 잠시 후 다시 보내 주세요."),
	PHOTO_INVALID(HttpStatus.BAD_REQUEST, "JPEG·PNG·WebP 사진만 올릴 수 있어요."),
	PHOTO_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "사진은 5MB 이하만 올릴 수 있어요."),
	PHOTO_TOO_FAST(HttpStatus.TOO_MANY_REQUESTS, "사진을 너무 많이 올리고 있어요. 잠시 후 다시 올려 주세요."),
	PHOTO_CLUB_FULL(HttpStatus.CONFLICT, "이 모임의 사진첩이 가득 찼어요. 오래된 사진을 지운 뒤 올려 주세요."),
	PHOTO_STORAGE_FULL(HttpStatus.CONFLICT, "사진 저장 공간이 가득 찼어요. 운영자에게 알려 주세요."),
	PHOTO_NOT_FOUND(HttpStatus.NOT_FOUND, "삭제되었거나 찾을 수 없는 사진입니다."),
	PHOTO_DELETE_FORBIDDEN(HttpStatus.FORBIDDEN, "본인 사진이나 모임 운영자만 지울 수 있습니다."),
	FAVORITE_FORBIDDEN(HttpStatus.FORBIDDEN, "체험 계정은 즐겨찾기를 쓸 수 없습니다."),
	// 다른 사람의 예약도 존재 여부를 드러내지 않도록 같은 코드로 응답한다
	NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "삭제되었거나 찾을 수 없는 알림입니다."),
	CLUB_NOT_FOUND(HttpStatus.NOT_FOUND, "삭제되었거나 찾을 수 없는 모임입니다."),
	CLUB_FORBIDDEN(HttpStatus.FORBIDDEN, "체험 계정은 모임을 신청하거나 가입할 수 없습니다."),
	NOT_CLUB_CREATOR(HttpStatus.FORBIDDEN, "이 모임을 고치거나 지울 권한이 없습니다."),
	CLUB_CHANGED(HttpStatus.CONFLICT, "모임 신청 내용이나 상태가 바뀌었습니다. 새로 고친 뒤 다시 확인해 주세요."),
	CREATOR_CANNOT_LEAVE(HttpStatus.CONFLICT, "모임을 만든 사람은 탈퇴할 수 없습니다. 모임을 삭제해 주세요."),
	CLUB_MEMBERS_ONLY(HttpStatus.FORBIDDEN, "모임 회원만 볼 수 있습니다."),
	CLUB_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "이 모임의 회원이 아닙니다."),
	CREATOR_ROLE_FIXED(HttpStatus.CONFLICT, "만든 사람의 모임 운영자 권한은 회수할 수 없습니다. 먼저 다른 회원에게 생성자를 넘겨 주세요."),
	SELF_ROLE_CHANGE(HttpStatus.FORBIDDEN, "자기 자신의 역할은 바꿀 수 없습니다. 다른 운영자에게 요청해 주세요."),
	ROLE_CHANGE_FORBIDDEN(HttpStatus.FORBIDDEN, "운영자만 역할을 바꿀 수 있습니다."),
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

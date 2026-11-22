package com.daumjari.usage;

/** 누적 사용량 지표. 성공한 동작만 센다. */
public enum Metric {

	/** 일반 회원가입 */
	SIGNUP,
	/** 데모 체험 시작 */
	DEMO_STARTED,
	/** 회차 생성 (데모 체험의 샘플 회차는 세지 않음) */
	SESSION_CREATED,
	/** 직접 예약 확정 (새 신청만, 같은 계정의 중복 요청은 제외) */
	RESERVATION_CONFIRMED,
	/** 대기자 자동 승격으로 확정 (취소·정원 증설·탈퇴로 생긴 자리) */
	RESERVATION_PROMOTED,
	/** 대기 신청 (새 신청만) */
	WAITLIST_JOINED,
	/** 신청 취소 (이미 취소된 신청은 제외, 탈퇴로 인한 취소 포함) */
	RESERVATION_CANCELLED,
	/** 회차 삭제 (생성자·운영자 삭제, 생성자 탈퇴로 인한 삭제 포함) */
	SESSION_DELETED

}

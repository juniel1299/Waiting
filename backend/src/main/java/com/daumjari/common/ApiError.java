package com.daumjari.common;

/** 모든 API 오류 응답 본문. 화면은 code 로 분기하고 message 는 표시용이다. */
public record ApiError(String code, String message) {
}

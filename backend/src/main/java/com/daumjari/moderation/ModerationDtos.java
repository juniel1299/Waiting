package com.daumjari.moderation;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.daumjari.account.Role;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;

import jakarta.validation.constraints.NotNull;

/** 운영자 계정 관리 API 의 요청·응답. 비밀번호 해시 같은 내부 값은 담지 않는다. */
public final class ModerationDtos {

	private ModerationDtos() {
	}

	public enum AccountStatus {
		ACTIVE, SUSPENDED, WITHDRAWN
	}

	public enum Action {
		SUSPEND, UNSUSPEND, FORCE_WITHDRAW
	}

	/** 계정 목록 한 줄. 탈퇴한 계정은 loginId 가 없고 이름은 익명 값이다. */
	public record AccountSummary(Long id, String loginId, String displayName, Role role, AccountStatus status,
			Instant createdAt, Instant suspendedAt, Instant deletedAt, long activeReservations, long createdSessions) {
	}

	public record AccountPage(List<AccountSummary> items, int page, int size, long totalElements, int totalPages) {
	}

	/**
	 * 조치 전 영향 범위. 정지는 신청·회차를 그대로 두고 접근만 막는다. 강제탈퇴는 본인 탈퇴와 같은 규칙:
	 * 다른 사람 회차의 시작 전 확정 취소(대기자 있으면 승격), 대기 종료, 시작된 회차 확정은 남김, 본인이 만든 회차는 신청째 삭제.
	 */
	public record Impact(Long id, String label, Role role, AccountStatus status, long upcomingConfirmed,
			long startedConfirmedKept, long waiting, long promotions, long ownedSessions, long ownedSessionReservations,
			boolean canSuspend, boolean canUnsuspend, boolean canWithdraw, String blockedReason) {
	}

	/** 사유는 필수 (앞뒤 공백 제외 1~500자). */
	public record ReasonRequest(@NotNull String reason) {

		String validReason() {
			String stripped = reason == null ? "" : reason.strip();
			if (stripped.isEmpty() || stripped.length() > 500) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED);
			}
			return stripped;
		}

	}

	/** 운영 조치 기록 한 줄. 라벨은 지금 계정 상태로 만든다 (탈퇴했으면 '탈퇴한 회원 #id'). */
	public record ActionEntry(Long id, Action action, String reason, Instant createdAt, Long operatorId,
			String operatorLabel, Long targetId, String targetLabel, int cancelledReservations, int deletedSessions) {
	}

	public record ActionPage(List<ActionEntry> items, int page, int size, long totalElements, int totalPages) {
	}

	/** 쪽 번호(1부터)·크기 검증. 틀리면 400 VALIDATION_FAILED. */
	record Paging(int page, int size) {

		static Paging parse(String page, String size) {
			return new Paging(number(page, 1, 10_000), number(size, 20, 100));
		}

		long offset() {
			return (long) (page - 1) * size;
		}

		int totalPages(long total) {
			return (int) ((total + size - 1) / size);
		}

		private static int number(String raw, int fallback, int max) {
			if (raw == null || raw.isBlank()) {
				return fallback;
			}
			int value;
			try {
				value = Integer.parseInt(raw.strip());
			}
			catch (NumberFormatException e) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED);
			}
			if (value < 1 || value > max) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED);
			}
			return value;
		}

	}

	/** 상태 필터. 없거나 ALL 이면 null (전체). */
	static AccountStatus statusFilter(String raw) {
		if (raw == null || raw.isBlank() || raw.strip().equalsIgnoreCase("ALL")) {
			return null;
		}
		try {
			return AccountStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
	}

}

package com.daumjari.club;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.daumjari.board.BoardDtos.Author;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;

/** 모임 API 요청·응답. 반려 사유·version 은 생성자와 서비스 운영자에게만 보인다. 생성자는 공개 라벨만. */
public final class ClubDtos {

	private ClubDtos() {
	}

	public enum Mode {
		OFFLINE, ONLINE, MIXED
	}

	public enum Status {
		PENDING, APPROVED, REJECTED, CANCELLED
	}

	public record ClubResponse(Long id, String name, String intro, String place, Mode mode, Status status,
			String rejectReason, Long version, Author creator, long memberCount, boolean joined, boolean mine,
			Instant submittedAt, Instant decidedAt, boolean canEdit, boolean canDelete, boolean canCancel, boolean canJoin,
			boolean canLeave) {
	}

	public record ClubPage(List<ClubResponse> items, int page, int size, long totalElements, int totalPages,
			boolean canApply) {
	}

	public record MyClubs(List<ClubResponse> applications, List<ClubResponse> joined) {
	}

	public record Membership(boolean joined, long memberCount) {
	}

	/** 이름 1~60자, 소개 1~2000자, 활동 장소 1~200자 (앞뒤 공백 제거), 방식 OFFLINE·ONLINE·MIXED. */
	public record ClubRequest(String name, String intro, String place, String mode) {

		ClubRequest validated() {
			String n = name == null ? "" : name.strip();
			String p = place == null ? "" : place.strip();
			if (n.isEmpty() || n.length() > 60 || intro == null || intro.isBlank() || intro.length() > 2000 || p.isEmpty()
					|| p.length() > 200) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED);
			}
			return new ClubRequest(n, intro.strip(), p, parseMode().name());
		}

		Mode parseMode() {
			try {
				return Mode.valueOf(mode == null ? "" : mode.strip().toUpperCase(Locale.ROOT));
			}
			catch (IllegalArgumentException e) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED);
			}
		}

	}

	/** 운영자 결정. version 은 운영자 화면이 본 값. 반려 사유 1~500자. */
	public record Decision(Long version, String reason) {

		String validReason() {
			String r = reason == null ? "" : reason.strip();
			if (r.isEmpty() || r.length() > 500) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED);
			}
			return r;
		}

	}

}

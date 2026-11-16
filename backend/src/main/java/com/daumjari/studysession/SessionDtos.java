package com.daumjari.studysession;

import java.time.Instant;

import com.daumjari.reservation.ReservationStatus;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class SessionDtos {

	private SessionDtos() {
	}

	public enum SessionStatus {
		OPEN,
		FULL,
		STARTED
	}

	/** 참가자·운영자 공통 회차 응답. 다른 참가자 정보는 담지 않는다. */
	public record SessionResponse(Long id, String title, Instant startsAt, String location, int capacity,
			long confirmedCount, long remainingSeats, long waitingCount, SessionStatus status,
			MyReservation myReservation) {
	}

	/** 조회한 사용자 본인의 활성 신청(확정·대기). 없으면 null. waitingPosition 은 대기일 때만 (1부터). */
	public record MyReservation(Long id, ReservationStatus status, Integer waitingPosition) {
	}

	public record CreateSessionRequest(@NotBlank @Size(max = 100) String title, @NotNull Instant startsAt,
			@NotBlank @Size(max = 200) String location, @NotNull @Min(1) @Max(1000) Integer capacity) {
	}

	public record ChangeCapacityRequest(@NotNull @Min(1) @Max(1000) Integer capacity) {
	}

	/** promotedCount: 늘어난 자리로 대기에서 확정된 인원. */
	public record CapacityChangeResponse(SessionResponse session, int promotedCount) {
	}

	public record RosterEntry(Long reservationId, Long accountId, String loginId, String displayName,
			Instant reservedAt) {
	}

}

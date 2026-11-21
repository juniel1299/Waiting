package com.daumjari.reservation;

import java.time.Instant;

public final class ReservationDtos {

	private ReservationDtos() {
	}

	/** waitingPosition 은 WAITING 일 때만 값이 있다 (1부터). */
	public record ReservationResponse(Long id, Long sessionId, String sessionTitle, Instant startsAt,
			String location, ReservationStatus status, Integer waitingPosition, Instant createdAt,
			Instant cancelledAt) {

		static ReservationResponse from(Reservation r, Integer waitingPosition) {
			return new ReservationResponse(r.getId(), r.getStudySession().getId(), r.getStudySession().getTitle(),
					r.getStudySession().getStartsAt(), r.getStudySession().getLocation(), r.getStatus(),
					waitingPosition, r.getCreatedAt(), r.getCancelledAt());
		}

	}

	/** created=false 면 이미 있던 활성 신청을 돌려준 것 (중복 요청). */
	public record ReserveResult(ReservationResponse reservation, boolean created) {
	}

}

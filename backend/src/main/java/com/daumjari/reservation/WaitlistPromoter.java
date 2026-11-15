package com.daumjari.reservation;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가장 먼저 신청한 대기자를 확정한다. 호출한 취소 트랜잭션(회차 행 잠금 보유) 안에서만 동작하며,
 * 여기서 실패하면 취소도 함께 롤백된다.
 */
@Component
public class WaitlistPromoter {

	private final ReservationRepository reservations;

	public WaitlistPromoter(ReservationRepository reservations) {
		this.reservations = reservations;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<Reservation> promoteNext(Long sessionId) {
		Optional<Reservation> next = reservations.findFirstByStudySessionIdAndStatusOrderByIdAsc(sessionId,
				ReservationStatus.WAITING);
		next.ifPresent(Reservation::promote);
		return next;
	}

}

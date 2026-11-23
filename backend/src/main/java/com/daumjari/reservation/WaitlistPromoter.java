package com.daumjari.reservation;

import java.util.Optional;

import org.springframework.stereotype.Component;
import com.daumjari.notification.Notifier;
import com.daumjari.usage.Metric;
import com.daumjari.usage.UsageCounter;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가장 먼저 신청한 대기자를 확정한다. 호출한 취소 트랜잭션(회차 행 잠금 보유) 안에서만 동작하며,
 * 여기서 실패하면 취소도 함께 롤백된다.
 */
@Component
public class WaitlistPromoter {

	private final ReservationRepository reservations;

	private final UsageCounter usage;

	private final Notifier notifier;

	public WaitlistPromoter(ReservationRepository reservations, UsageCounter usage, Notifier notifier) {
		this.reservations = reservations;
		this.usage = usage;
		this.notifier = notifier;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<Reservation> promoteNext(Long sessionId) {
		Optional<Reservation> next = reservations.findFirstByStudySessionIdAndStatusOrderByIdAsc(sessionId,
				ReservationStatus.WAITING);
		next.ifPresent(r -> {
			r.promote();
			usage.increment(Metric.RESERVATION_PROMOTED, r.getStudySession().isDemo());
			// 승격 알림도 같은 트랜잭션: 취소·정원 변경이 롤백되면 알림도 남지 않는다
			notifier.promoted(r.getId(), r.getAccount().getId(), sessionId);
		});
		return next;
	}

}

package com.daumjari.reservation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.daumjari.account.AccountRepository;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;
import com.daumjari.reservation.ReservationDtos.ReservationResponse;
import com.daumjari.reservation.ReservationDtos.ReserveResult;
import com.daumjari.studysession.StudySession;
import com.daumjari.studysession.StudySessionRepository;

/**
 * 예약·취소는 회차 행을 FOR UPDATE 로 잠근 뒤 같은 트랜잭션에서 잔여석을 판단하고 상태를 바꾼다.
 * 같은 회차의 요청은 직렬화되고, 다른 회차끼리는 서로 막지 않는다.
 */
@Service
public class ReservationService {

	private final ReservationRepository reservations;

	private final StudySessionRepository sessions;

	private final AccountRepository accounts;

	private final Clock clock;

	public ReservationService(ReservationRepository reservations, StudySessionRepository sessions,
			AccountRepository accounts, Clock clock) {
		this.reservations = reservations;
		this.sessions = sessions;
		this.accounts = accounts;
		this.clock = clock;
	}

	@Transactional
	public ReserveResult reserve(Long sessionId, Long accountId) {
		Instant now = Instant.now(clock);
		StudySession session = sessions.findByIdForUpdate(sessionId)
			.orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
		if (session.hasStarted(now)) {
			throw new ApiException(ErrorCode.SESSION_STARTED);
		}
		Optional<Reservation> existing = reservations.findByStudySessionIdAndAccountIdAndStatusNot(sessionId,
				accountId, ReservationStatus.CANCELLED);
		if (existing.isPresent()) {
			return new ReserveResult(ReservationResponse.from(existing.get()), false);
		}
		long confirmed = reservations.countByStudySessionIdAndStatus(sessionId, ReservationStatus.CONFIRMED);
		if (confirmed >= session.getCapacity()) {
			throw new ApiException(ErrorCode.SESSION_FULL);
		}
		Reservation saved = reservations
			.save(Reservation.confirm(session, accounts.getReferenceById(accountId), now));
		return new ReserveResult(ReservationResponse.from(saved), true);
	}

	@Transactional
	public ReservationResponse cancel(Long reservationId, Long accountId) {
		Instant now = Instant.now(clock);
		Long sessionId = reservations.findSessionIdByIdAndAccountId(reservationId, accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
		// 잠금 순서: 회차 → 신청 (예약과 같은 순서로 교착 방지)
		StudySession session = sessions.findByIdForUpdate(sessionId)
			.orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
		if (session.hasStarted(now)) {
			throw new ApiException(ErrorCode.SESSION_STARTED);
		}
		Reservation reservation = reservations.findByIdAndAccountIdForUpdate(reservationId, accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
		reservation.cancel(now);
		return ReservationResponse.from(reservation);
	}

	@Transactional(readOnly = true)
	public List<ReservationResponse> mine(Long accountId) {
		return reservations.findWithSessionByAccountId(accountId).stream().map(ReservationResponse::from).toList();
	}

}

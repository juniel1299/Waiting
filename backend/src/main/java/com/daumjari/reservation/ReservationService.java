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
 * 예약·대기·취소는 회차 행을 FOR UPDATE 로 잠근 뒤 같은 트랜잭션에서 잔여석·대기열을 판단하고 상태를 바꾼다.
 * 같은 회차의 요청은 직렬화되고, 다른 회차끼리는 서로 막지 않는다.
 */
@Service
public class ReservationService {

	private final ReservationRepository reservations;

	private final StudySessionRepository sessions;

	private final AccountRepository accounts;

	private final WaitlistPromoter promoter;

	private final Clock clock;

	public ReservationService(ReservationRepository reservations, StudySessionRepository sessions,
			AccountRepository accounts, WaitlistPromoter promoter, Clock clock) {
		this.reservations = reservations;
		this.sessions = sessions;
		this.accounts = accounts;
		this.promoter = promoter;
		this.clock = clock;
	}

	@Transactional
	public ReserveResult reserve(Long sessionId, Long accountId) {
		Instant now = Instant.now(clock);
		StudySession session = lockOpenSession(sessionId, now);
		Optional<Reservation> existing = activeOf(sessionId, accountId);
		if (existing.isPresent()) {
			return new ReserveResult(toResponse(existing.get()), false);
		}
		// 대기자가 있으면 빈자리가 보여도 대기자 몫이다
		if (seatsTaken(session) || hasWaiters(sessionId)) {
			throw new ApiException(ErrorCode.SESSION_FULL);
		}
		Reservation saved = reservations
			.save(Reservation.confirm(session, accounts.getReferenceById(accountId), now));
		return new ReserveResult(toResponse(saved), true);
	}

	/** 마감(또는 대기자가 있는) 회차에만 대기를 받는다. 재신청은 새 행이라 맨 뒤로 간다. */
	@Transactional
	public ReserveResult waitlist(Long sessionId, Long accountId) {
		Instant now = Instant.now(clock);
		StudySession session = lockOpenSession(sessionId, now);
		Optional<Reservation> existing = activeOf(sessionId, accountId);
		if (existing.isPresent()) {
			return new ReserveResult(toResponse(existing.get()), false);
		}
		if (!seatsTaken(session) && !hasWaiters(sessionId)) {
			throw new ApiException(ErrorCode.SEATS_AVAILABLE);
		}
		Reservation saved = reservations
			.save(Reservation.waitlist(session, accounts.getReferenceById(accountId), now));
		return new ReserveResult(toResponse(saved), true);
	}

	@Transactional
	public ReservationResponse cancel(Long reservationId, Long accountId) {
		Instant now = Instant.now(clock);
		Long sessionId = reservations.findSessionIdByIdAndAccountId(reservationId, accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
		// 잠금 순서: 회차 → 신청 (예약·대기와 같은 순서로 교착 방지)
		lockOpenSession(sessionId, now);
		Reservation reservation = reservations.findByIdAndAccountIdForUpdate(reservationId, accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
		boolean freesASeat = reservation.getStatus() == ReservationStatus.CONFIRMED;
		reservation.cancel(now);
		if (freesASeat) {
			// 같은 트랜잭션·같은 회차 잠금 아래에서 승격한다. 실패하면 이 취소도 롤백된다.
			promoter.promoteNext(sessionId);
		}
		return toResponse(reservation);
	}

	@Transactional(readOnly = true)
	public List<ReservationResponse> mine(Long accountId) {
		return reservations.findWithSessionByAccountId(accountId).stream().map(this::toResponse).toList();
	}

	private StudySession lockOpenSession(Long sessionId, Instant now) {
		StudySession session = sessions.findByIdForUpdate(sessionId)
			.orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
		if (session.hasStarted(now)) {
			throw new ApiException(ErrorCode.SESSION_STARTED);
		}
		return session;
	}

	private Optional<Reservation> activeOf(Long sessionId, Long accountId) {
		return reservations.findByStudySessionIdAndAccountIdAndStatusNot(sessionId, accountId,
				ReservationStatus.CANCELLED);
	}

	private boolean seatsTaken(StudySession session) {
		return reservations.countByStudySessionIdAndStatus(session.getId(),
				ReservationStatus.CONFIRMED) >= session.getCapacity();
	}

	private boolean hasWaiters(Long sessionId) {
		return reservations.countByStudySessionIdAndStatus(sessionId, ReservationStatus.WAITING) > 0;
	}

	private ReservationResponse toResponse(Reservation reservation) {
		return ReservationResponse.from(reservation, reservations.waitingPositionOf(reservation));
	}

}

package com.daumjari.reservation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;
import com.daumjari.reservation.ReservationDtos.ReservationResponse;
import com.daumjari.reservation.ReservationDtos.ReserveResult;
import com.daumjari.studysession.StudySession;
import com.daumjari.studysession.StudySessionRepository;
import com.daumjari.usage.Metric;
import com.daumjari.usage.UsageCounter;

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

	private final UsageCounter usage;

	private final Clock clock;

	public ReservationService(ReservationRepository reservations, StudySessionRepository sessions,
			AccountRepository accounts, WaitlistPromoter promoter, UsageCounter usage, Clock clock) {
		this.usage = usage;
		this.reservations = reservations;
		this.sessions = sessions;
		this.accounts = accounts;
		this.promoter = promoter;
		this.clock = clock;
	}

	@Transactional
	public ReserveResult reserve(Long sessionId, Long accountId) {
		Instant now = Instant.now(clock);
		Account account = lockActiveAccount(accountId);
		StudySession session = lockOpenSession(sessionId, now);
		requireVisible(session, account);
		Optional<Reservation> existing = activeOf(sessionId, accountId);
		if (existing.isPresent()) {
			return new ReserveResult(toResponse(existing.get()), false);
		}
		// 대기자가 있으면 빈자리가 보여도 대기자 몫이다
		if (seatsTaken(session) || hasWaiters(sessionId)) {
			throw new ApiException(ErrorCode.SESSION_FULL);
		}
		Reservation saved = reservations
			.save(Reservation.confirm(session, account, now));
		usage.increment(Metric.RESERVATION_CONFIRMED, session.isDemo());
		return new ReserveResult(toResponse(saved), true);
	}

	/** 마감(또는 대기자가 있는) 회차에만 대기를 받는다. 재신청은 새 행이라 맨 뒤로 간다. */
	@Transactional
	public ReserveResult waitlist(Long sessionId, Long accountId) {
		Instant now = Instant.now(clock);
		Account account = lockActiveAccount(accountId);
		StudySession session = lockOpenSession(sessionId, now);
		requireVisible(session, account);
		Optional<Reservation> existing = activeOf(sessionId, accountId);
		if (existing.isPresent()) {
			return new ReserveResult(toResponse(existing.get()), false);
		}
		if (!seatsTaken(session) && !hasWaiters(sessionId)) {
			throw new ApiException(ErrorCode.SEATS_AVAILABLE);
		}
		Reservation saved = reservations
			.save(Reservation.waitlist(session, account, now));
		usage.increment(Metric.WAITLIST_JOINED, session.isDemo());
		return new ReserveResult(toResponse(saved), true);
	}

	@Transactional
	public ReservationResponse cancel(Long reservationId, Long accountId) {
		Instant now = Instant.now(clock);
		Long sessionId = reservations.findSessionIdByIdAndAccountId(reservationId, accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
		// 잠금 순서: 회차 → 신청 (예약·대기와 같은 순서로 교착 방지)
		StudySession session = lockOpenSession(sessionId, now);
		Reservation reservation = reservations.findByIdAndAccountIdForUpdate(reservationId, accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
		boolean freesASeat = reservation.getStatus() == ReservationStatus.CONFIRMED;
		if (reservation.isActive()) {
			usage.increment(Metric.RESERVATION_CANCELLED, session.isDemo());
		}
		reservation.cancel(now);
		if (freesASeat) {
			// 같은 트랜잭션·같은 회차 잠금 아래에서 승격한다. 실패하면 이 취소도 롤백된다.
			promoter.promoteNext(sessionId);
		}
		return toResponse(reservation);
	}

	/**
	 * 탈퇴하는 계정의 신청 정리. 호출한 탈퇴 트랜잭션(계정 행 잠금 보유) 안에서만 동작하고, 여기서 실패하면 탈퇴도 롤백된다.
	 * 회차를 id 순으로 하나씩 잠근 뒤: 시작 전 확정은 취소하고 기존 승격 로직으로 대기자를 확정, 대기는 모두 취소,
	 * 시작된 회차의 확정은 참가 기록으로 남긴다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void releaseAllOf(Long accountId, Instant now) {
		// 본인이 만든 회차는 신청째 삭제하고(승격 없음), 다른 회차의 신청은 정리한다. 회차 id 순으로 하나씩 잠가 교착을 막는다.
		java.util.SortedSet<Long> owned = new java.util.TreeSet<>(sessions.findIdsByCreatorAccountId(accountId));
		java.util.SortedSet<Long> all = new java.util.TreeSet<>(owned);
		all.addAll(reservations.findActiveSessionIdsByAccountId(accountId));
		for (Long sessionId : all) {
			StudySession session = sessions.findByIdForUpdate(sessionId)
				.orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
			if (owned.contains(sessionId)) {
				sessions.deleteRowById(sessionId);
				usage.increment(Metric.SESSION_DELETED, false);
				continue;
			}
			Optional<Reservation> active = activeOf(sessionId, accountId);
			if (active.isEmpty()) {
				continue;
			}
			Reservation reservation = active.get();
			boolean confirmed = reservation.getStatus() == ReservationStatus.CONFIRMED;
			if (confirmed && session.hasStarted(now)) {
				continue;
			}
			usage.increment(Metric.RESERVATION_CANCELLED, session.isDemo());
			reservation.cancel(now);
			if (confirmed) {
				promoter.promoteNext(sessionId);
			}
		}
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

	/** 탈퇴와 겹친 요청: 탈퇴가 먼저 커밋됐으면 여기서 막혀 탈퇴된 계정에 새 신청이 생기지 않는다. */
	private Account lockActiveAccount(Long accountId) {
		return accounts.findByIdForShare(accountId)
			.filter(account -> !account.isDeleted())
			.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
	}

	/** 일반 계정은 일반 회차만, 데모 방문자는 자기 체험 회차만. 아니면 존재를 드러내지 않고 404. */
	private void requireVisible(StudySession session, Account account) {
		if (!session.isVisibleTo(account.getDemoTrialId())) {
			throw new ApiException(ErrorCode.SESSION_NOT_FOUND);
		}
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

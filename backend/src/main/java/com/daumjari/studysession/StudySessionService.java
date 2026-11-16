package com.daumjari.studysession;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;
import com.daumjari.reservation.Reservation;
import com.daumjari.reservation.ReservationRepository;
import com.daumjari.reservation.ReservationRepository.SessionCount;
import com.daumjari.reservation.ReservationStatus;
import com.daumjari.reservation.WaitlistPromoter;
import com.daumjari.studysession.SessionDtos.CapacityChangeResponse;
import com.daumjari.studysession.SessionDtos.CreateSessionRequest;
import com.daumjari.studysession.SessionDtos.MyReservation;
import com.daumjari.studysession.SessionDtos.RosterEntry;
import com.daumjari.studysession.SessionDtos.SessionResponse;
import com.daumjari.studysession.SessionDtos.SessionStatus;

@Service
@Transactional(readOnly = true)
public class StudySessionService {

	private final StudySessionRepository sessions;

	private final ReservationRepository reservations;

	private final WaitlistPromoter promoter;

	private final Clock clock;

	public StudySessionService(StudySessionRepository sessions, ReservationRepository reservations,
			WaitlistPromoter promoter, Clock clock) {
		this.sessions = sessions;
		this.reservations = reservations;
		this.promoter = promoter;
		this.clock = clock;
	}

	public List<SessionResponse> list(Long viewerId) {
		List<StudySession> all = sessions.findAllByOrderByStartsAtAscIdAsc();
		if (all.isEmpty()) {
			return List.of();
		}
		List<Long> ids = all.stream().map(StudySession::getId).toList();
		Map<Long, Long> confirmed = countsBy(ids, ReservationStatus.CONFIRMED);
		Map<Long, Long> waiting = countsBy(ids, ReservationStatus.WAITING);
		Map<Long, Reservation> mine = reservations
			.findByAccountIdAndStatusNot(viewerId, ReservationStatus.CANCELLED)
			.stream()
			.collect(Collectors.toMap(r -> r.getStudySession().getId(), r -> r));
		Instant now = Instant.now(clock);
		return all.stream()
			.map(s -> toResponse(s, confirmed.getOrDefault(s.getId(), 0L), waiting.getOrDefault(s.getId(), 0L),
					mine.get(s.getId()), now))
			.toList();
	}

	public SessionResponse get(Long sessionId, Long viewerId) {
		StudySession session = findSession(sessionId);
		Reservation mine = reservations
			.findByStudySessionIdAndAccountIdAndStatusNot(sessionId, viewerId, ReservationStatus.CANCELLED)
			.orElse(null);
		return toResponse(session, countOf(sessionId, ReservationStatus.CONFIRMED),
				countOf(sessionId, ReservationStatus.WAITING), mine, Instant.now(clock));
	}

	@Transactional
	public SessionResponse create(CreateSessionRequest request) {
		Instant now = Instant.now(clock);
		if (!request.startsAt().isAfter(now)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		StudySession saved = sessions.save(StudySession.create(request.title().strip(), request.startsAt(),
				request.location().strip(), request.capacity(), now));
		return toResponse(saved, 0, 0, null, now);
	}

	/**
	 * 회차 행을 잠근 뒤 정원을 바꾸고, 늘어난 자리만큼 기존 승격 객체로 대기 순서대로 확정한다.
	 * 한 트랜잭션이라 승격 중 실패하면 정원 변경과 앞선 승격도 함께 롤백된다.
	 */
	@Transactional
	public CapacityChangeResponse changeCapacity(Long sessionId, int capacity) {
		Instant now = Instant.now(clock);
		StudySession session = sessions.findByIdForUpdate(sessionId)
			.orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
		if (session.hasStarted(now)) {
			throw new ApiException(ErrorCode.SESSION_STARTED);
		}
		long confirmed = countOf(sessionId, ReservationStatus.CONFIRMED);
		if (capacity < confirmed) {
			throw new ApiException(ErrorCode.CAPACITY_BELOW_CONFIRMED);
		}
		session.changeCapacity(capacity);
		int promoted = 0;
		while (confirmed + promoted < capacity && promoter.promoteNext(sessionId).isPresent()) {
			promoted++;
		}
		return new CapacityChangeResponse(toResponse(session, confirmed + promoted,
				countOf(sessionId, ReservationStatus.WAITING), null, now), promoted);
	}

	public List<RosterEntry> roster(Long sessionId) {
		findSession(sessionId);
		return reservations.findWithAccountBySessionIdAndStatus(sessionId, ReservationStatus.CONFIRMED)
			.stream()
			.map(r -> new RosterEntry(r.getId(), r.getAccount().getId(), r.getAccount().getLoginId(),
					r.getAccount().getDisplayName(), r.getCreatedAt()))
			.toList();
	}

	private SessionResponse toResponse(StudySession session, long confirmed, long waiting, Reservation mine,
			Instant now) {
		long remaining = Math.max(0, session.getCapacity() - confirmed);
		MyReservation my = mine == null ? null
				: new MyReservation(mine.getId(), mine.getStatus(), reservations.waitingPositionOf(mine));
		return new SessionResponse(session.getId(), session.getTitle(), session.getStartsAt(), session.getLocation(),
				session.getCapacity(), confirmed, remaining, waiting, statusOf(session, remaining, waiting, now), my);
	}

	/** 대기자가 있으면 빈자리가 보여도 대기자 몫이라 FULL 로 본다 (ReservationService.reserve 와 같은 규칙). */
	private static SessionStatus statusOf(StudySession session, long remaining, long waiting, Instant now) {
		if (session.hasStarted(now)) {
			return SessionStatus.STARTED;
		}
		return remaining == 0 || waiting > 0 ? SessionStatus.FULL : SessionStatus.OPEN;
	}

	private Map<Long, Long> countsBy(List<Long> sessionIds, ReservationStatus status) {
		return reservations.countBySessionIds(sessionIds, status)
			.stream()
			.collect(Collectors.toMap(SessionCount::getSessionId, SessionCount::getTotal));
	}

	private long countOf(Long sessionId, ReservationStatus status) {
		return reservations.countByStudySessionIdAndStatus(sessionId, status);
	}

	private StudySession findSession(Long sessionId) {
		return sessions.findById(sessionId).orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
	}

}

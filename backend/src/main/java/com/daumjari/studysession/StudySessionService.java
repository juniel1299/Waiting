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

	private final Clock clock;

	public StudySessionService(StudySessionRepository sessions, ReservationRepository reservations, Clock clock) {
		this.sessions = sessions;
		this.reservations = reservations;
		this.clock = clock;
	}

	public List<SessionResponse> list(Long viewerId) {
		List<StudySession> all = sessions.findAllByOrderByStartsAtAscIdAsc();
		if (all.isEmpty()) {
			return List.of();
		}
		Map<Long, Long> confirmed = reservations
			.countBySessionIds(all.stream().map(StudySession::getId).toList(), ReservationStatus.CONFIRMED)
			.stream()
			.collect(Collectors.toMap(SessionCount::getSessionId, SessionCount::getTotal));
		Map<Long, Reservation> mine = reservations
			.findByAccountIdAndStatusNot(viewerId, ReservationStatus.CANCELLED)
			.stream()
			.collect(Collectors.toMap(r -> r.getStudySession().getId(), r -> r));
		Instant now = Instant.now(clock);
		return all.stream()
			.map(s -> toResponse(s, confirmed.getOrDefault(s.getId(), 0L), mine.get(s.getId()), now))
			.toList();
	}

	public SessionResponse get(Long sessionId, Long viewerId) {
		StudySession session = findSession(sessionId);
		Reservation mine = reservations
			.findByStudySessionIdAndAccountIdAndStatusNot(sessionId, viewerId, ReservationStatus.CANCELLED)
			.orElse(null);
		return toResponse(session, confirmedCount(sessionId), mine, Instant.now(clock));
	}

	@Transactional
	public SessionResponse create(CreateSessionRequest request) {
		Instant now = Instant.now(clock);
		if (!request.startsAt().isAfter(now)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		StudySession saved = sessions.save(StudySession.create(request.title().strip(), request.startsAt(),
				request.location().strip(), request.capacity(), now));
		return toResponse(saved, 0, null, now);
	}

	public List<RosterEntry> roster(Long sessionId) {
		findSession(sessionId);
		return reservations.findWithAccountBySessionIdAndStatus(sessionId, ReservationStatus.CONFIRMED)
			.stream()
			.map(r -> new RosterEntry(r.getId(), r.getAccount().getId(), r.getAccount().getLoginId(),
					r.getAccount().getDisplayName(), r.getCreatedAt()))
			.toList();
	}

	public SessionResponse toResponse(StudySession session, long confirmed, Reservation mine, Instant now) {
		long remaining = Math.max(0, session.getCapacity() - confirmed);
		MyReservation my = mine == null ? null : new MyReservation(mine.getId(), mine.getStatus());
		return new SessionResponse(session.getId(), session.getTitle(), session.getStartsAt(), session.getLocation(),
				session.getCapacity(), confirmed, remaining, statusOf(session, remaining, now), my);
	}

	private static SessionStatus statusOf(StudySession session, long remaining, Instant now) {
		if (session.hasStarted(now)) {
			return SessionStatus.STARTED;
		}
		return remaining == 0 ? SessionStatus.FULL : SessionStatus.OPEN;
	}

	private long confirmedCount(Long sessionId) {
		return reservations.countByStudySessionIdAndStatus(sessionId, ReservationStatus.CONFIRMED);
	}

	private StudySession findSession(Long sessionId) {
		return sessions.findById(sessionId).orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
	}

}

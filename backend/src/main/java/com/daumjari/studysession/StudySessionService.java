package com.daumjari.studysession;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;
import com.daumjari.auth.SessionUser;
import com.daumjari.usage.Metric;
import com.daumjari.usage.UsageCounter;
import com.daumjari.common.ApiException;
import com.daumjari.demo.DemoTrials;
import com.daumjari.common.ErrorCode;
import com.daumjari.reservation.Reservation;
import com.daumjari.reservation.ReservationRepository;
import com.daumjari.reservation.ReservationRepository.SessionCount;
import com.daumjari.reservation.ReservationStatus;
import com.daumjari.reservation.WaitlistPromoter;
import com.daumjari.studysession.SessionDtos.CapacityChangeResponse;
import com.daumjari.studysession.SessionDtos.CreateSessionRequest;
import com.daumjari.studysession.SessionDtos.EditSessionRequest;
import com.daumjari.studysession.SessionDtos.MyReservation;
import com.daumjari.studysession.SessionDtos.RosterEntry;
import com.daumjari.studysession.SessionDtos.SessionPage;
import com.daumjari.studysession.SessionDtos.SessionResponse;
import com.daumjari.studysession.SessionDtos.SessionStatus;

@Service
@Transactional(readOnly = true)
public class StudySessionService {

	private final StudySessionRepository sessions;

	private final ReservationRepository reservations;

	private final WaitlistPromoter promoter;

	private final AccountRepository accounts;

	private final DemoTrials demoTrials;

	private final UsageCounter usage;

	private final Clock clock;

	private final StudySessionSearchQuery searchQuery;

	public StudySessionService(StudySessionRepository sessions, ReservationRepository reservations,
			WaitlistPromoter promoter, AccountRepository accounts, DemoTrials demoTrials, UsageCounter usage,
			Clock clock, StudySessionSearchQuery searchQuery) {
		this.searchQuery = searchQuery;
		this.usage = usage;
		this.sessions = sessions;
		this.reservations = reservations;
		this.promoter = promoter;
		this.accounts = accounts;
		this.demoTrials = demoTrials;
		this.clock = clock;
	}

	/** 조회자의 데모 체험 ID (일반 계정은 null). 회차는 같은 체험 값끼리만 보인다. */
	private Long trialOf(Long viewerId) {
		return accounts.findById(viewerId).map(Account::getDemoTrialId).orElse(null);
	}

	/**
	 * 생성자만 수정·정원 변경을 한다. 생성자가 없는 일반 회차(백필 전 데이터·운영자 생성 이전 방식)는 운영자가 관리한다.
	 * 데모 체험 회차와 체험 방문자는 관리 대상이 아니다.
	 */
	static boolean canManage(StudySession session, SessionUser user) {
		if (session.isDemo() || user.isDemo()) {
			return false;
		}
		Long creator = session.getCreatorAccountId();
		return creator == null ? user.role() == Role.OPERATOR : creator.equals(user.id());
	}

	/** 명단 조회·삭제: 관리 권한이 있거나 운영자. */
	static boolean canDelete(StudySession session, SessionUser user) {
		return !session.isDemo() && (canManage(session, user) || user.role() == Role.OPERATOR);
	}

	public List<SessionResponse> list(SessionUser viewer) {
		Long trial = trialOf(viewer.id());
		return toResponses(trial == null ? sessions.findByDemoTrialIdIsNullOrderByStartsAtAscIdAsc()
				: sessions.findByDemoTrialIdOrderByStartsAtAscIdAsc(trial), viewer);
	}

	/** 내가 만든 회차 (시작 순). */
	public List<SessionResponse> mine(SessionUser viewer) {
		return toResponses(sessions.findByCreatorAccountIdOrderByStartsAtAscIdAsc(viewer.id()), viewer);
	}

	/** 전체 회차·내가 만든 회차 검색. 순서는 검색 SQL 이 정하고, 응답 변환은 목록과 같다. */
	public SessionPage search(SessionSearch search, SessionUser viewer) {
		StudySessionSearchQuery.Result found = searchQuery.search(search, viewer.id(), trialOf(viewer.id()),
				Instant.now(clock));
		Map<Long, StudySession> byId = sessions.findAllById(found.ids())
			.stream()
			.collect(Collectors.toMap(StudySession::getId, s -> s));
		List<StudySession> ordered = found.ids().stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
		int totalPages = (int) ((found.total() + search.size() - 1) / search.size());
		return new SessionPage(toResponses(ordered, viewer), search.page(), search.size(), found.total(), totalPages);
	}

	private List<SessionResponse> toResponses(List<StudySession> all, SessionUser viewer) {
		Long viewerId = viewer.id();
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
					mine.get(s.getId()), now, viewer))
			.toList();
	}

	public SessionResponse get(Long sessionId, SessionUser viewer) {
		Long viewerId = viewer.id();
		StudySession session = findSession(sessionId);
		if (!session.isVisibleTo(trialOf(viewerId))) {
			throw new ApiException(ErrorCode.SESSION_NOT_FOUND);
		}
		Reservation mine = reservations
			.findByStudySessionIdAndAccountIdAndStatusNot(sessionId, viewerId, ReservationStatus.CANCELLED)
			.orElse(null);
		return toResponse(session, countOf(sessionId, ReservationStatus.CONFIRMED),
				countOf(sessionId, ReservationStatus.WAITING), mine, Instant.now(clock), viewer);
	}

	/** 생성자는 요청 본문이 아니라 로그인 세션에서 정한다. 체험 방문자는 만들 수 없다. */
	@Transactional
	public SessionResponse create(CreateSessionRequest request, SessionUser creator) {
		if (creator.isDemo()) {
			throw new ApiException(ErrorCode.SESSION_CREATE_FORBIDDEN);
		}
		Instant now = Instant.now(clock);
		if (!request.startsAt().isAfter(now)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		StudySession session = StudySession.create(request.title().strip(), request.startsAt(),
				request.location().strip(), request.capacity(), now);
		session.assignCreator(creator.id());
		StudySession saved = sessions.save(session);
		usage.increment(Metric.SESSION_CREATED, false);
		return toResponse(saved, 0, 0, null, now, creator);
	}

	/** 제목·시작·장소 수정. 생성자만, 시작 전 회차만. */
	@Transactional
	public SessionResponse edit(Long sessionId, EditSessionRequest request, SessionUser user) {
		Instant now = Instant.now(clock);
		StudySession session = lockManageable(sessionId, user);
		if (session.hasStarted(now)) {
			throw new ApiException(ErrorCode.SESSION_STARTED);
		}
		if (!request.startsAt().isAfter(now)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		session.edit(request.title().strip(), request.startsAt(), request.location().strip());
		return toResponse(session, countOf(sessionId, ReservationStatus.CONFIRMED),
				countOf(sessionId, ReservationStatus.WAITING), null, now, user);
	}

	/**
	 * 물리 삭제. 회차 행을 잠근 뒤 지우고, 신청(확정·대기·취소)은 FK on delete cascade 로 같은 문장에서 함께 지워진다.
	 * 회차 전체를 지우므로 대기자 승격은 없다. 진행 중인 예약·대기·정원 변경은 같은 행 잠금으로 줄을 서서
	 * 삭제 전에 끝나 함께 지워지거나, 삭제 뒤에 와서 404 가 된다.
	 */
	@Transactional
	public void delete(Long sessionId, SessionUser user) {
		StudySession session = sessions.findByIdForUpdate(sessionId)
			.filter(s -> !s.isDemo())
			.orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
		if (!canDelete(session, user)) {
			throw new ApiException(ErrorCode.NOT_SESSION_OWNER);
		}
		sessions.deleteRowById(sessionId);
		usage.increment(Metric.SESSION_DELETED, false);
	}

	/** 회차 행을 잠그고 관리 권한(생성자)을 확인한다. 데모 체험 회차는 존재를 드러내지 않는다. */
	private StudySession lockManageable(Long sessionId, SessionUser user) {
		StudySession session = sessions.findByIdForUpdate(sessionId)
			.filter(s -> !s.isDemo())
			.orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
		if (!canManage(session, user)) {
			throw new ApiException(ErrorCode.NOT_SESSION_OWNER);
		}
		return session;
	}

	/**
	 * 회차 행을 잠근 뒤 정원을 바꾸고, 늘어난 자리만큼 기존 승격 객체로 대기 순서대로 확정한다.
	 * 한 트랜잭션이라 승격 중 실패하면 정원 변경과 앞선 승격도 함께 롤백된다.
	 */
	@Transactional
	public CapacityChangeResponse changeCapacity(Long sessionId, int capacity, SessionUser user) {
		Instant now = Instant.now(clock);
		StudySession session = lockManageable(sessionId, user);
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
				countOf(sessionId, ReservationStatus.WAITING), null, now, user), promoted);
	}

	public List<RosterEntry> roster(Long sessionId, SessionUser user) {
		StudySession session = findSession(sessionId);
		if (session.isDemo()) {
			throw new ApiException(ErrorCode.SESSION_NOT_FOUND);
		}
		if (!canDelete(session, user)) {
			throw new ApiException(ErrorCode.NOT_SESSION_OWNER);
		}
		return reservations.findWithAccountBySessionIdAndStatus(sessionId, ReservationStatus.CONFIRMED)
			.stream()
			.map(r -> new RosterEntry(r.getId(), r.getAccount().getId(), r.getAccount().getLoginId(),
					r.getAccount().getDisplayName(), r.getCreatedAt()))
			.toList();
	}

	private SessionResponse toResponse(StudySession session, long confirmed, long waiting, Reservation mine,
			Instant now, SessionUser viewer) {
		long remaining = Math.max(0, session.getCapacity() - confirmed);
		MyReservation my = mine == null ? null
				: new MyReservation(mine.getId(), mine.getStatus(), reservations.waitingPositionOf(mine));
		return new SessionResponse(session.getId(), session.getTitle(), session.getStartsAt(), session.getLocation(),
				session.getCapacity(), confirmed, remaining, waiting, statusOf(session, remaining, waiting, now), my,
				session.isDemo() ? demoTrials.info(session.getDemoTrialId()).orElse(null) : null,
				canManage(session, viewer), canDelete(session, viewer));
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

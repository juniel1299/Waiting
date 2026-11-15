package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.FixedClockConfiguration.TestClock;
import com.daumjari.account.Account;
import com.daumjari.account.Role;
import com.daumjari.studysession.StudySession;

/** 대기 신청·순번·대기 취소·자동 승격. 대기 행은 모두 API 로 만든다. */
@Import({ TestcontainersConfiguration.class, FixedClockConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class WaitlistApiTests extends ApiTestSupport {

	static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

	static final Instant STARTS_AT = NOW.plus(2, ChronoUnit.HOURS);

	@Autowired
	TestClock clock;

	@Autowired
	JdbcTemplate jdbc;

	Account carol;

	Account alice;

	Account bob;

	MockHttpSession carolSession;

	MockHttpSession aliceSession;

	MockHttpSession bobSession;

	@BeforeEach
	void setUp() throws Exception {
		clock.setInstant(NOW);
		carol = newAccount("carol", Role.PARTICIPANT);
		alice = newAccount("alice", Role.PARTICIPANT);
		bob = newAccount("bob", Role.PARTICIPANT);
		carolSession = login(carol);
		aliceSession = login(alice);
		bobSession = login(bob);
	}

	/** 정원 1 회차를 carol 이 확정해 마감시킨다. carol 의 신청 ID 를 돌려준다. */
	private Long fullSessionReservedByCarol(StudySession session) throws Exception {
		return idOf(reserve(session, carolSession).andExpect(status().isCreated()));
	}

	// --- 대기 신청·순번 ---

	@Test
	void waitlistOnFullSessionGetsPositionsInArrivalOrder() throws Exception {
		StudySession session = newSession("대기 순번", STARTS_AT, 1);
		fullSessionReservedByCarol(session);

		waitlist(session, aliceSession).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("WAITING"))
			.andExpect(jsonPath("$.waitingPosition").value(1));
		waitlist(session, bobSession).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("WAITING"))
			.andExpect(jsonPath("$.waitingPosition").value(2));

		mockMvc.perform(get("/api/sessions/{id}", session.getId()).session(bobSession))
			.andExpect(jsonPath("$.remainingSeats").value(0))
			.andExpect(jsonPath("$.waitingCount").value(2))
			.andExpect(jsonPath("$.myReservation.status").value("WAITING"))
			.andExpect(jsonPath("$.myReservation.waitingPosition").value(2));
		mockMvc.perform(get("/api/me/reservations").session(bobSession))
			.andExpect(jsonPath("$[0].status").value("WAITING"))
			.andExpect(jsonPath("$[0].waitingPosition").value(2));
		mockMvc.perform(get("/api/sessions").session(aliceSession))
			.andExpect(jsonPath("$[?(@.id == %d)].myReservation.waitingPosition".formatted(session.getId()))
				.value(hasItem(1)));
	}

	@Test
	void waitlistIsRejectedWhileSeatsAreAvailable() throws Exception {
		StudySession session = newSession("빈자리 회차", STARTS_AT, 2);

		waitlist(session, aliceSession).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SEATS_AVAILABLE"));

		assertThat(activeCount(session, alice)).isZero();
	}

	@Test
	void duplicateRequestsWhileWaitingKeepOneActiveEntry() throws Exception {
		StudySession session = newSession("대기 중복", STARTS_AT, 1);
		fullSessionReservedByCarol(session);
		Long first = idOf(waitlist(session, aliceSession).andExpect(status().isCreated()));

		waitlist(session, aliceSession).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(first))
			.andExpect(jsonPath("$.waitingPosition").value(1));
		reserve(session, aliceSession).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(first))
			.andExpect(jsonPath("$.status").value("WAITING"));

		assertThat(activeCount(session, alice)).isEqualTo(1);
	}

	@Test
	void confirmedParticipantAskingToWaitGetsExistingConfirmation() throws Exception {
		StudySession session = newSession("확정자 대기 요청", STARTS_AT, 1);
		Long carolId = fullSessionReservedByCarol(session);

		waitlist(session, carolSession).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(carolId))
			.andExpect(jsonPath("$.status").value("CONFIRMED"));
	}

	// --- 대기 취소·재신청 ---

	@Test
	void cancelledWaiterReapplyingGoesToTheBack() throws Exception {
		StudySession session = newSession("재신청 맨 뒤", STARTS_AT, 1);
		fullSessionReservedByCarol(session);
		Long aliceFirst = idOf(waitlist(session, aliceSession));
		waitlist(session, bobSession);

		cancel(aliceFirst, aliceSession).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));
		assertPosition(session, bobSession, 1);

		waitlist(session, aliceSession).andExpect(status().isCreated())
			.andExpect(jsonPath("$.waitingPosition").value(2));
		assertThat(statusCount(session, "CONFIRMED")).isEqualTo(1);
	}

	@Test
	void cancellingAWaitingEntryPromotesNobody() throws Exception {
		StudySession session = newSession("대기 취소", STARTS_AT, 1);
		Long carolId = fullSessionReservedByCarol(session);
		waitlist(session, aliceSession);
		Long bobId = idOf(waitlist(session, bobSession));

		cancel(bobId, bobSession).andExpect(status().isOk());

		assertThat(statusOf(carolId)).isEqualTo("CONFIRMED");
		assertThat(statusCount(session, "CONFIRMED")).isEqualTo(1);
		assertPosition(session, aliceSession, 1);
	}

	// --- 자동 승격 ---

	@Test
	void cancellingAConfirmationPromotesTheEarliestWaiter() throws Exception {
		StudySession session = newSession("승격 순서", STARTS_AT, 1);
		Long carolId = fullSessionReservedByCarol(session);
		Long aliceId = idOf(waitlist(session, aliceSession));
		Long bobId = idOf(waitlist(session, bobSession));

		cancel(carolId, carolSession).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));

		assertThat(statusOf(aliceId)).isEqualTo("CONFIRMED");
		assertThat(statusOf(bobId)).isEqualTo("WAITING");
		assertThat(statusCount(session, "CONFIRMED")).isEqualTo(1);
		assertPosition(session, bobSession, 1);
		mockMvc.perform(get("/api/sessions/{id}", session.getId()).session(aliceSession))
			.andExpect(jsonPath("$.myReservation.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.myReservation.waitingPosition").value(nullValue()));
	}

	@Test
	void repeatedCancelOfAConfirmationPromotesOnlyOnce() throws Exception {
		StudySession session = newSession("반복 취소 승격", STARTS_AT, 1);
		Long carolId = fullSessionReservedByCarol(session);
		Long aliceId = idOf(waitlist(session, aliceSession));
		Long bobId = idOf(waitlist(session, bobSession));

		cancel(carolId, carolSession).andExpect(status().isOk());
		cancel(carolId, carolSession).andExpect(status().isOk());

		assertThat(statusOf(aliceId)).isEqualTo("CONFIRMED");
		assertThat(statusOf(bobId)).isEqualTo("WAITING");
		assertThat(statusCount(session, "CONFIRMED")).isEqualTo(1);
	}

	@Test
	void newReservationYieldsToExistingWaitersEvenIfASeatLooksFree() throws Exception {
		StudySession session = newSession("대기자 우선", STARTS_AT, 1);
		fullSessionReservedByCarol(session);
		waitlist(session, aliceSession);
		// 정원 증설 같은 경로로 자리가 생긴 상태를 만든다 (증설 API 는 아직 없음)
		jdbc.update("update study_session set capacity = 2 where id = ?", session.getId());

		reserve(session, bobSession).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SESSION_FULL"));
		waitlist(session, bobSession).andExpect(status().isCreated())
			.andExpect(jsonPath("$.waitingPosition").value(2));
	}

	// --- 시작 시각 경계 (고정 시계) ---

	@Test
	void waitlistIsAcceptedUntilJustBeforeStartAndRejectedFromStart() throws Exception {
		StudySession session = newSession("대기 경계", STARTS_AT, 1);
		fullSessionReservedByCarol(session);

		clock.setInstant(STARTS_AT.minusSeconds(1));
		waitlist(session, aliceSession).andExpect(status().isCreated());

		clock.setInstant(STARTS_AT);
		waitlist(session, bobSession).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SESSION_STARTED"));
		assertThat(activeCount(session, bob)).isZero();
	}

	@Test
	void cancellingAfterStartIsRejectedAndPromotesNobody() throws Exception {
		StudySession session = newSession("시작 후 취소", STARTS_AT, 1);
		Long carolId = fullSessionReservedByCarol(session);
		Long aliceId = idOf(waitlist(session, aliceSession));

		clock.setInstant(STARTS_AT);
		cancel(carolId, carolSession).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SESSION_STARTED"));

		assertThat(statusOf(carolId)).isEqualTo("CONFIRMED");
		assertThat(statusOf(aliceId)).isEqualTo("WAITING");
	}

	// --- 권한·없는 회차 ---

	@Test
	void operatorCannotJoinWaitlist() throws Exception {
		StudySession session = newSession("운영자 대기", STARTS_AT, 1);
		fullSessionReservedByCarol(session);

		waitlist(session, login(newAccount("op", Role.OPERATOR))).andExpect(status().isForbidden());
	}

	@Test
	void unknownSessionIsNotFound() throws Exception {
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", 999_999), aliceSession))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
	}

	// --- helpers ---

	private ResultActions reserve(StudySession session, MockHttpSession who) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/sessions/{id}/reservations", session.getId()), who));
	}

	private ResultActions waitlist(StudySession session, MockHttpSession who) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", session.getId()), who));
	}

	private ResultActions cancel(Long reservationId, MockHttpSession who) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/reservations/{id}/cancel", reservationId), who));
	}

	private void assertPosition(StudySession session, MockHttpSession who, int expected) throws Exception {
		mockMvc.perform(get("/api/sessions/{id}", session.getId()).session(who))
			.andExpect(jsonPath("$.myReservation.status").value("WAITING"))
			.andExpect(jsonPath("$.myReservation.waitingPosition").value(expected));
	}

	private long activeCount(StudySession session, Account account) {
		return jdbc.queryForObject("""
				select count(*) from reservation
				where study_session_id = ? and account_id = ? and status <> 'CANCELLED'
				""", Long.class, session.getId(), account.getId());
	}

	private long statusCount(StudySession session, String status) {
		return jdbc.queryForObject("select count(*) from reservation where study_session_id = ? and status = ?",
				Long.class, session.getId(), status);
	}

	private String statusOf(Long reservationId) {
		return jdbc.queryForObject("select status from reservation where id = ?", String.class, reservationId);
	}

	private static Long idOf(ResultActions result) throws Exception {
		String json = result.andReturn().getResponse().getContentAsString();
		return Long.valueOf(json.replaceAll("^\\{\"id\":(\\d+).*", "$1").trim());
	}

}

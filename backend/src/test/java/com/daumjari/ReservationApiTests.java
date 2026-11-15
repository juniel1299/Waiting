package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
import com.daumjari.reservation.Reservation;
import com.daumjari.studysession.StudySession;

@Import({ TestcontainersConfiguration.class, FixedClockConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ReservationApiTests extends ApiTestSupport {

	static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

	static final Instant STARTS_AT = NOW.plus(2, ChronoUnit.HOURS);

	@Autowired
	TestClock clock;

	@Autowired
	JdbcTemplate jdbc;

	Account alice;

	Account bob;

	MockHttpSession aliceSession;

	@BeforeEach
	void setUp() throws Exception {
		clock.setInstant(NOW);
		alice = newAccount("alice", Role.PARTICIPANT);
		bob = newAccount("bob", Role.PARTICIPANT);
		aliceSession = login(alice);
	}

	// --- 예약 ---

	@Test
	void reserveConfirmsWhenSeatAvailable() throws Exception {
		StudySession session = newSession("예약 회차", STARTS_AT, 2);

		reserve(session, aliceSession)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isNumber())
			.andExpect(jsonPath("$.sessionId").value(session.getId()))
			.andExpect(jsonPath("$.status").value("CONFIRMED"));

		mockMvc.perform(get("/api/sessions/{id}", session.getId()).session(aliceSession))
			.andExpect(jsonPath("$.remainingSeats").value(1))
			.andExpect(jsonPath("$.myReservation.status").value("CONFIRMED"));
		mockMvc.perform(get("/api/me/reservations").session(aliceSession))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].sessionId").value(hasItem(session.getId().intValue())))
			.andExpect(jsonPath("$[0].sessionTitle").value("예약 회차"));
	}

	@Test
	void duplicateReserveDoesNotAddActiveReservation() throws Exception {
		StudySession session = newSession("중복 회차", STARTS_AT, 3);

		String first = reserve(session, aliceSession).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		String second = reserve(session, aliceSession).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(idOf(second)).isEqualTo(idOf(first));
		assertThat(activeCount(session, alice)).isEqualTo(1);
	}

	@Test
	void fullSessionIsRejected() throws Exception {
		StudySession session = newSession("마감 회차", STARTS_AT, 1);
		confirmDirectly(session, bob);

		reserve(session, aliceSession)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SESSION_FULL"));

		assertThat(activeCount(session, alice)).isZero();
	}

	@Test
	void unknownSessionIsNotFound() throws Exception {
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/reservations", 999_999), aliceSession))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
	}

	@Test
	void operatorCannotReserve() throws Exception {
		StudySession session = newSession("운영자 회차", STARTS_AT, 3);

		reserve(session, login(newAccount("op", Role.OPERATOR)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void reservationRequiresLogin() throws Exception {
		StudySession session = newSession("미인증 회차", STARTS_AT, 3);

		mockMvc.perform(withXsrf(post("/api/sessions/{id}/reservations", session.getId()), null))
			.andExpect(status().isUnauthorized());
	}

	// --- 취소 ---

	@Test
	void repeatedCancelRestoresSeatOnlyOnce() throws Exception {
		StudySession session = newSession("취소 회차", STARTS_AT, 2);
		confirmDirectly(session, bob);
		Long id = idOf(reserve(session, aliceSession).andReturn().getResponse().getContentAsString());
		assertRemaining(session, 0);

		cancel(id, aliceSession)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.cancelledAt").value(NOW.toString()));
		assertRemaining(session, 1);

		cancel(id, aliceSession)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));
		assertRemaining(session, 1);
		assertThat(confirmedCount(session)).isEqualTo(1);
	}

	@Test
	void canReserveAgainAfterCancel() throws Exception {
		StudySession session = newSession("재예약 회차", STARTS_AT, 2);
		Long first = idOf(reserve(session, aliceSession).andReturn().getResponse().getContentAsString());
		cancel(first, aliceSession).andExpect(status().isOk());

		String again = reserve(session, aliceSession).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("CONFIRMED"))
			.andReturn().getResponse().getContentAsString();

		assertThat(idOf(again)).isNotEqualTo(first);
		assertThat(activeCount(session, alice)).isEqualTo(1);
	}

	@Test
	void cannotCancelOtherParticipantsReservation() throws Exception {
		StudySession session = newSession("남의 예약 회차", STARTS_AT, 2);
		Reservation bobs = confirmDirectly(session, bob);

		cancel(bobs.getId(), aliceSession)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));

		assertThat(statusOf(bobs.getId())).isEqualTo("CONFIRMED");
		mockMvc.perform(get("/api/me/reservations").session(aliceSession))
			.andExpect(jsonPath("$[*].id").value(not(hasItem(bobs.getId().intValue()))));
	}

	@Test
	void unknownReservationCancelIsNotFound() throws Exception {
		cancel(999_999L, aliceSession)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
	}

	// --- 시작 시각 경계 (고정 시계) ---

	@Test
	void reserveIsAcceptedUntilJustBeforeStartAndRejectedFromStart() throws Exception {
		StudySession session = newSession("경계 예약 회차", STARTS_AT, 5);

		clock.setInstant(STARTS_AT.minusSeconds(1));
		reserve(session, aliceSession).andExpect(status().isCreated());

		clock.setInstant(STARTS_AT);
		MockHttpSession bobSession = login(bob);
		reserve(session, bobSession)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SESSION_STARTED"));

		clock.setInstant(STARTS_AT.plusSeconds(1));
		reserve(session, bobSession)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SESSION_STARTED"));

		assertThat(activeCount(session, bob)).isZero();
		assertThat(confirmedCount(session)).isEqualTo(1);
	}

	@Test
	void cancelIsAcceptedUntilJustBeforeStartAndRejectedFromStart() throws Exception {
		StudySession session = newSession("경계 취소 회차", STARTS_AT, 5);
		MockHttpSession bobSession = login(bob);
		Long aliceId = idOf(reserve(session, aliceSession).andReturn().getResponse().getContentAsString());
		Long bobId = idOf(reserve(session, bobSession).andReturn().getResponse().getContentAsString());

		clock.setInstant(STARTS_AT.minusSeconds(1));
		cancel(aliceId, aliceSession).andExpect(status().isOk());

		clock.setInstant(STARTS_AT);
		cancel(bobId, bobSession)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SESSION_STARTED"));

		clock.setInstant(STARTS_AT.plusSeconds(1));
		cancel(bobId, bobSession)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SESSION_STARTED"));

		assertThat(statusOf(bobId)).isEqualTo("CONFIRMED");
		assertThat(statusOf(aliceId)).isEqualTo("CANCELLED");
	}

	// --- helpers ---

	private ResultActions reserve(StudySession session, MockHttpSession who) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/sessions/{id}/reservations", session.getId()), who));
	}

	private ResultActions cancel(Long reservationId, MockHttpSession who) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/reservations/{id}/cancel", reservationId), who));
	}

	private void assertRemaining(StudySession session, int expected) throws Exception {
		mockMvc.perform(get("/api/sessions/{id}", session.getId()).session(aliceSession))
			.andExpect(jsonPath("$.remainingSeats").value(expected));
	}

	private long activeCount(StudySession session, Account account) {
		return jdbc.queryForObject("""
				select count(*) from reservation
				where study_session_id = ? and account_id = ? and status <> 'CANCELLED'
				""", Long.class, session.getId(), account.getId());
	}

	private long confirmedCount(StudySession session) {
		return jdbc.queryForObject(
				"select count(*) from reservation where study_session_id = ? and status = 'CONFIRMED'", Long.class,
				session.getId());
	}

	private String statusOf(Long reservationId) {
		return jdbc.queryForObject("select status from reservation where id = ?", String.class, reservationId);
	}

	private static Long idOf(String json) {
		return Long.valueOf(json.replaceAll("^\\{\"id\":(\\d+).*", "$1").trim());
	}

}

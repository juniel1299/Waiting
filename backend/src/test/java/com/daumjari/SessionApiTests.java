package com.daumjari;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;
import com.daumjari.reservation.Reservation;
import com.daumjari.studysession.StudySession;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SessionApiTests extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	Account operator;

	Account participant;

	Account other;

	Instant nextWeek;

	@BeforeEach
	void setUp() {
		operator = newAccount("op", Role.OPERATOR);
		participant = newAccount("part", Role.PARTICIPANT);
		other = newAccount("other", Role.PARTICIPANT);
		nextWeek = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
	}

	// --- 참가자 조회 ---

	@Test
	void participantSeesSessionWithRemainingSeats() throws Exception {
		StudySession session = newSession("잔여석 회차", nextWeek, 3);
		confirmDirectly(session, other);
		MockHttpSession me = login(participant);

		mockMvc.perform(get("/api/sessions").session(me))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == %d)].remainingSeats".formatted(session.getId())).value(hasItem(2)));

		mockMvc.perform(get("/api/sessions/{id}", session.getId()).session(me))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("잔여석 회차"))
			.andExpect(jsonPath("$.capacity").value(3))
			.andExpect(jsonPath("$.confirmedCount").value(1))
			.andExpect(jsonPath("$.remainingSeats").value(2))
			.andExpect(jsonPath("$.status").value("OPEN"))
			.andExpect(jsonPath("$.myReservation").value(nullValue()));
	}

	@Test
	void detailShowsMyReservationButNotOthers() throws Exception {
		StudySession session = newSession("내 예약 회차", nextWeek, 3);
		Reservation mine = confirmDirectly(session, participant);
		confirmDirectly(session, other);

		mockMvc.perform(get("/api/sessions/{id}", session.getId()).session(login(participant)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.myReservation.id").value(mine.getId()))
			.andExpect(jsonPath("$.myReservation.status").value("CONFIRMED"))
			.andExpect(content().string(not(containsString(other.getLoginId()))));
	}

	@Test
	void fullSessionIsMarkedFull() throws Exception {
		StudySession session = newSession("마감 회차", nextWeek, 1);
		confirmDirectly(session, other);

		mockMvc.perform(get("/api/sessions/{id}", session.getId()).session(login(participant)))
			.andExpect(jsonPath("$.remainingSeats").value(0))
			.andExpect(jsonPath("$.status").value("FULL"));
	}

	@Test
	void startedSessionIsMarkedStarted() throws Exception {
		StudySession session = newSession("지난 회차", Instant.now().minus(1, ChronoUnit.HOURS), 5);

		mockMvc.perform(get("/api/sessions/{id}", session.getId()).session(login(participant)))
			.andExpect(jsonPath("$.status").value("STARTED"));
	}

	@Test
	void unknownSessionIsNotFound() throws Exception {
		mockMvc.perform(get("/api/sessions/{id}", 999_999).session(login(participant)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
	}

	@Test
	void sessionsRequireLogin() throws Exception {
		mockMvc.perform(get("/api/sessions"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	// --- 운영자 API ---

	@Test
	void operatorCreatesSession() throws Exception {
		MockHttpSession op = login(operator);

		String body = mockMvc.perform(withXsrf(post("/api/operator/sessions"), op)
				.contentType(MediaType.APPLICATION_JSON)
				.content(createJson("새 회차", nextWeek.toString(), "판교", 4)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isNumber())
			.andExpect(jsonPath("$.title").value("새 회차"))
			.andExpect(jsonPath("$.location").value("판교"))
			.andExpect(jsonPath("$.capacity").value(4))
			.andExpect(jsonPath("$.remainingSeats").value(4))
			.andExpect(jsonPath("$.status").value("OPEN"))
			.andReturn().getResponse().getContentAsString();

		Long id = Long.valueOf(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
		mockMvc.perform(get("/api/sessions/{id}", id).session(login(participant)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("새 회차"));
	}

	@Test
	void createRejectsNonPositiveCapacityAndPastStart() throws Exception {
		MockHttpSession op = login(operator);

		mockMvc.perform(withXsrf(post("/api/operator/sessions"), op)
				.contentType(MediaType.APPLICATION_JSON)
				.content(createJson("정원 0", nextWeek.toString(), "판교", 0)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mockMvc.perform(withXsrf(post("/api/operator/sessions"), op)
				.contentType(MediaType.APPLICATION_JSON)
				.content(createJson("과거", Instant.now().minus(1, ChronoUnit.DAYS).toString(), "판교", 3)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void operatorSeesConfirmedRosterOnly() throws Exception {
		StudySession session = newSession("명단 회차", nextWeek, 5);
		confirmDirectly(session, participant);
		Reservation cancelled = confirmDirectly(session, other);
		jdbc.update("update reservation set status = 'CANCELLED', cancelled_at = now() where id = ?",
				cancelled.getId());

		mockMvc.perform(get("/api/operator/sessions/{id}/roster", session.getId()).session(login(operator)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].loginId").value(participant.getLoginId()))
			.andExpect(jsonPath("$[0].displayName").value("part"))
			.andExpect(jsonPath("$[0].reservationId").isNumber());
	}

	@Test
	void rosterOfUnknownSessionIsNotFound() throws Exception {
		mockMvc.perform(get("/api/operator/sessions/{id}/roster", 999_999).session(login(operator)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
	}

	@Test
	void participantIsForbiddenFromOperatorApis() throws Exception {
		StudySession session = newSession("권한 회차", nextWeek, 5);
		confirmDirectly(session, other);
		MockHttpSession me = login(participant);

		mockMvc.perform(withXsrf(post("/api/operator/sessions"), me)
				.contentType(MediaType.APPLICATION_JSON)
				.content(createJson("몰래", nextWeek.toString(), "판교", 4)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mockMvc.perform(get("/api/operator/sessions/{id}/roster", session.getId()).session(me))
			.andExpect(status().isForbidden())
			.andExpect(content().string(not(containsString(other.getLoginId()))));
	}

	private static String createJson(String title, String startsAt, String location, int capacity) {
		return """
				{"title":"%s","startsAt":"%s","location":"%s","capacity":%d}
				""".formatted(title, startsAt, location, capacity);
	}

}

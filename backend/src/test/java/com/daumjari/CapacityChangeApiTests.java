package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.FixedClockConfiguration.TestClock;
import com.daumjari.account.Role;
import com.daumjari.studysession.StudySession;

/** 운영자 정원 변경과 증설 시 대기자 승격. 신청은 모두 API 로 만든다. */
@Import({ TestcontainersConfiguration.class, FixedClockConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CapacityChangeApiTests extends ApiTestSupport {

	static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

	static final Instant STARTS_AT = NOW.plus(2, ChronoUnit.HOURS);

	@Autowired
	TestClock clock;

	@Autowired
	JdbcTemplate jdbc;

	MockHttpSession operator;

	@BeforeEach
	void setUp() throws Exception {
		clock.setInstant(NOW);
		operator = login(newAccount("op", Role.OPERATOR));
	}

	/** 대기자 한 명: 로그인 세션과 신청 ID. */
	private record Waiter(MockHttpSession session, Long reservationId) {
	}

	/** confirmed 명을 확정시키고 waiting 명을 대기시킨다. 대기자는 대기 순서대로. */
	private List<Waiter> fill(StudySession session, int confirmed, int waiting) throws Exception {
		for (int i = 0; i < confirmed; i++) {
			mockMvc.perform(withXsrf(post("/api/sessions/{id}/reservations", session.getId()), participant()))
				.andExpect(status().isCreated());
		}
		List<Waiter> waiters = new ArrayList<>();
		for (int i = 0; i < waiting; i++) {
			MockHttpSession who = participant();
			waiters.add(new Waiter(who, idOf(mockMvc
				.perform(withXsrf(post("/api/sessions/{id}/waitlist", session.getId()), who))
				.andExpect(status().isCreated()))));
		}
		return waiters;
	}

	private MockHttpSession participant() throws Exception {
		return login(newAccount("p", Role.PARTICIPANT));
	}

	// --- 증설 ---

	@Test
	void increasingCapacityPromotesWaitersInQueueOrder() throws Exception {
		StudySession session = newSession("증설 승격", STARTS_AT, 3);
		List<Waiter> waiters = fill(session, 3, 3);

		change(session, 5).andExpect(status().isOk())
			.andExpect(jsonPath("$.promotedCount").value(2))
			.andExpect(jsonPath("$.session.capacity").value(5))
			.andExpect(jsonPath("$.session.confirmedCount").value(5))
			.andExpect(jsonPath("$.session.waitingCount").value(1))
			.andExpect(jsonPath("$.session.status").value("FULL"));

		assertThat(statusOf(waiters.get(0))).isEqualTo("CONFIRMED");
		assertThat(statusOf(waiters.get(1))).isEqualTo("CONFIRMED");
		assertThat(statusOf(waiters.get(2))).isEqualTo("WAITING");
		mockMvc.perform(get("/api/sessions/{id}", session.getId()).session(waiters.get(2).session()))
			.andExpect(jsonPath("$.myReservation.waitingPosition").value(1));
	}

	@Test
	void seatsLeftAfterAllWaitersArePromotedOpenForReservations() throws Exception {
		StudySession session = newSession("증설 후 빈자리", STARTS_AT, 1);
		List<Waiter> waiters = fill(session, 1, 1);

		change(session, 3).andExpect(status().isOk())
			.andExpect(jsonPath("$.promotedCount").value(1))
			.andExpect(jsonPath("$.session.remainingSeats").value(1))
			.andExpect(jsonPath("$.session.waitingCount").value(0))
			.andExpect(jsonPath("$.session.status").value("OPEN"));
		assertThat(statusOf(waiters.get(0))).isEqualTo("CONFIRMED");

		mockMvc.perform(withXsrf(post("/api/sessions/{id}/reservations", session.getId()), participant()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("CONFIRMED"));
	}

	// --- 축소 ---

	@Test
	void decreasingDownToConfirmedCountIsAllowedAndKeepsWaiters() throws Exception {
		StudySession session = newSession("축소", STARTS_AT, 3);
		fill(session, 2, 0);

		change(session, 2).andExpect(status().isOk())
			.andExpect(jsonPath("$.promotedCount").value(0))
			.andExpect(jsonPath("$.session.capacity").value(2))
			.andExpect(jsonPath("$.session.status").value("FULL"));
	}

	@Test
	void decreasingBelowConfirmedCountIsRejectedWithoutChanges() throws Exception {
		StudySession session = newSession("확정 미만", STARTS_AT, 3);
		fill(session, 3, 1);
		Snapshot before = snapshot(session);

		change(session, 2).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CAPACITY_BELOW_CONFIRMED"));

		assertThat(snapshot(session)).isEqualTo(before);
	}

	// --- 입력·권한·시각 거절 (정원과 신청 상태 불변) ---

	@ParameterizedTest
	@ValueSource(strings = { "0", "-1", "2.5", "\"abc\"", "null", "1001" })
	void invalidCapacityIsRejectedWithoutChanges(String value) throws Exception {
		StudySession session = newSession("잘못된 값 " + value, STARTS_AT, 1);
		fill(session, 1, 1);
		Snapshot before = snapshot(session);

		mockMvc.perform(withXsrf(patch("/api/operator/sessions/{id}/capacity", session.getId()), operator)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"capacity\":" + value + "}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		assertThat(snapshot(session)).isEqualTo(before);
	}

	@Test
	void participantCannotChangeCapacity() throws Exception {
		StudySession session = newSession("참가자 변경", STARTS_AT, 1);
		fill(session, 1, 1);
		Snapshot before = snapshot(session);

		mockMvc.perform(withXsrf(patch("/api/operator/sessions/{id}/capacity", session.getId()), participant())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"capacity\":5}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		assertThat(snapshot(session)).isEqualTo(before);
	}

	@Test
	void changeIsAcceptedUntilJustBeforeStartAndRejectedFromStart() throws Exception {
		StudySession session = newSession("시작 경계", STARTS_AT, 1);
		fill(session, 1, 2);

		clock.setInstant(STARTS_AT.minusSeconds(1));
		change(session, 2).andExpect(status().isOk()).andExpect(jsonPath("$.promotedCount").value(1));

		clock.setInstant(STARTS_AT);
		Snapshot before = snapshot(session);
		change(session, 5).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SESSION_STARTED"));
		assertThat(snapshot(session)).isEqualTo(before);
	}

	@Test
	void unknownSessionIsNotFound() throws Exception {
		mockMvc.perform(withXsrf(patch("/api/operator/sessions/{id}/capacity", 999_999), operator)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"capacity\":5}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
	}

	// --- helpers ---

	private ResultActions change(StudySession session, int capacity) throws Exception {
		return mockMvc.perform(withXsrf(patch("/api/operator/sessions/{id}/capacity", session.getId()), operator)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"capacity\":" + capacity + "}"));
	}

	/** 정원과 모든 신청의 (id, 상태, 취소 시각). 거절 뒤 그대로인지 비교한다. */
	private record Snapshot(int capacity, List<Map<String, Object>> reservations) {
	}

	private Snapshot snapshot(StudySession session) {
		Integer capacity = jdbc.queryForObject("select capacity from study_session where id = ?", Integer.class,
				session.getId());
		List<Map<String, Object>> rows = jdbc.queryForList(
				"select id, status, cancelled_at from reservation where study_session_id = ? order by id",
				session.getId());
		return new Snapshot(capacity, rows);
	}

	private String statusOf(Waiter waiter) {
		return jdbc.queryForObject("select status from reservation where id = ?", String.class,
				waiter.reservationId());
	}

	private static Long idOf(ResultActions result) throws Exception {
		String json = result.andReturn().getResponse().getContentAsString();
		return Long.valueOf(json.replaceAll("^\\{\"id\":(\\d+).*", "$1").trim());
	}

}

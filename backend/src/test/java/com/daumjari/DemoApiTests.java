package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.FixedClockConfiguration.TestClock;
import com.daumjari.account.Role;
import com.daumjari.demo.DemoService;
import com.daumjari.studysession.StudySession;

/**
 * 가입 없는 데모 체험: 시작·대기·샘플 취소 승격, 방문자 간·일반 회차·운영자 격리, 만료와 정리, 시작 남용 제한.
 * 기본 설정: 체험 30분, 체험 회차 정원 2(샘플 2명 확정), 시작은 IP 당 1시간에 10회.
 */
@Import({ TestcontainersConfiguration.class, FixedClockConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DemoApiTests extends ApiTestSupport {

	static final Duration TTL = Duration.ofMinutes(30);

	static Instant now = Instant.parse("2032-01-01T00:00:00Z");

	@Autowired
	TestClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DemoService demoService;

	String ip;

	@BeforeEach
	void setUp() {
		now = now.plus(Duration.ofDays(1));
		clock.setInstant(now);
		ip = "100.64." + ThreadLocalRandom.current().nextInt(1, 255) + "." + ThreadLocalRandom.current().nextInt(1, 255);
	}

	/** 체험 하나: 방문자 세션과 체험 회차 ID. */
	private record Trial(MockHttpSession visitor, long sessionId) {
	}

	private Trial startTrial() throws Exception {
		MvcResult result = start(ip).andExpect(status().isCreated()).andReturn();
		long sessionId = Long.parseLong(
				result.getResponse().getContentAsString().replaceAll(".*\"sessionId\":(\\d+).*", "$1"));
		return new Trial((MockHttpSession) result.getRequest().getSession(false), sessionId);
	}

	private ResultActions start(String fromIp) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/demo/start"), null).with(request -> {
			request.setRemoteAddr(fromIp);
			return request;
		}));
	}

	// --- 체험 시작·흐름 ---

	@Test
	void startPreparesAFullSampleSessionAndLogsTheVisitorIn() throws Exception {
		Trial trial = startTrial();

		mockMvc.perform(get("/api/auth/me").session(trial.visitor()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("PARTICIPANT"))
			.andExpect(jsonPath("$.demo").value(true));
		mockMvc.perform(get("/api/sessions/{id}", trial.sessionId()).session(trial.visitor()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.capacity").value(2))
			.andExpect(jsonPath("$.confirmedCount").value(2))
			.andExpect(jsonPath("$.status").value("FULL"))
			.andExpect(jsonPath("$.myReservation").doesNotExist())
			.andExpect(jsonPath("$.demo.sampleReservationActive").value(true))
			.andExpect(jsonPath("$.demo.expiresAt").value(now.plus(TTL).toString()));
	}

	@Test
	void startRequiresCsrf() throws Exception {
		mockMvc.perform(post("/api/demo/start")).andExpect(status().isForbidden());
	}

	@Test
	void waitlistThenSampleCancelPromotesTheVisitorOnlyOnce() throws Exception {
		Trial trial = startTrial();
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", trial.sessionId()), trial.visitor()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.waitingPosition").value(1));

		sampleCancel(trial.visitor()).andExpect(status().isOk());

		mockMvc.perform(get("/api/sessions/{id}", trial.sessionId()).session(trial.visitor()))
			.andExpect(jsonPath("$.myReservation.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.confirmedCount").value(2))
			.andExpect(jsonPath("$.demo.sampleReservationActive").value(false));

		sampleCancel(trial.visitor()).andExpect(status().isOk());
		assertThat(count("select count(*) from reservation where study_session_id = ? and status = 'CONFIRMED'",
				trial.sessionId())).isEqualTo(2);
	}

	@Test
	void startingAgainBeginsAFreshTrial() throws Exception {
		Trial first = startTrial();
		Trial second = startTrial();

		assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
		mockMvc.perform(get("/api/sessions").session(second.visitor()))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(second.sessionId()));
	}

	// --- 격리 ---

	@Test
	void visitorSeesAndChangesOnlyTheirOwnTrial() throws Exception {
		Trial trial = startTrial();
		StudySession general = newSession("일반 회차", now.plus(3, ChronoUnit.DAYS), 5);

		mockMvc.perform(get("/api/sessions").session(trial.visitor()))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(trial.sessionId()));
		mockMvc.perform(get("/api/sessions/{id}", general.getId()).session(trial.visitor()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/reservations", general.getId()), trial.visitor()))
			.andExpect(status().isNotFound());
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", general.getId()), trial.visitor()))
			.andExpect(status().isNotFound());

		assertThat(count("select count(*) from reservation where study_session_id = ?", general.getId())).isZero();
	}

	@Test
	void anotherVisitorCannotSeeJoinOrCancelInMyTrial() throws Exception {
		Trial mine = startTrial();
		Trial theirs = startTrial();
		long mySample = sampleReservationId(mine.sessionId());

		mockMvc.perform(get("/api/sessions/{id}", mine.sessionId()).session(theirs.visitor()))
			.andExpect(status().isNotFound());
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", mine.sessionId()), theirs.visitor()))
			.andExpect(status().isNotFound());
		mockMvc.perform(withXsrf(post("/api/reservations/{id}/cancel", mySample), theirs.visitor()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));

		sampleCancel(theirs.visitor()).andExpect(status().isOk());

		assertThat(statusOf(mySample)).isEqualTo("CONFIRMED");
		assertThat(count("select count(*) from reservation where study_session_id = ? and status = 'CONFIRMED'",
				mine.sessionId())).isEqualTo(2);
	}

	@Test
	void regularParticipantCannotSeeOrJoinDemoSessions() throws Exception {
		Trial trial = startTrial();
		MockHttpSession member = login(newAccount("member", Role.PARTICIPANT));

		mockMvc.perform(get("/api/sessions").session(member))
			.andExpect(jsonPath("$[*].id").value(not(hasItem((int) trial.sessionId()))));
		mockMvc.perform(get("/api/sessions/{id}", trial.sessionId()).session(member))
			.andExpect(status().isNotFound());
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", trial.sessionId()), member))
			.andExpect(status().isNotFound());
		sampleCancel(member).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DEMO_NOT_FOUND"));
	}

	@Test
	void operatorDoesNotSeeDemoSessionsAndVisitorsCannotUseOperatorApis() throws Exception {
		Trial trial = startTrial();
		MockHttpSession operator = login(newAccount("op", Role.OPERATOR));

		mockMvc.perform(get("/api/sessions").session(operator))
			.andExpect(jsonPath("$[*].id").value(not(hasItem((int) trial.sessionId()))));
		mockMvc.perform(get("/api/operator/sessions/{id}/roster", trial.sessionId()).session(operator))
			.andExpect(status().isNotFound());
		mockMvc.perform(withXsrf(patch("/api/operator/sessions/{id}/capacity", trial.sessionId()), operator)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"capacity\":5}"))
			.andExpect(status().isNotFound());

		mockMvc.perform(get("/api/operator/sessions/{id}/roster", trial.sessionId()).session(trial.visitor()))
			.andExpect(status().isForbidden());
		assertThat(count("select capacity from study_session where id = ?", trial.sessionId())).isEqualTo(2);
	}

	@Test
	void demoAccountsCannotLogInWithAPassword() throws Exception {
		Trial trial = startTrial();
		String loginId = mockMvc.perform(get("/api/auth/me").session(trial.visitor()))
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replaceAll(".*\"loginId\":\"([^\"]+)\".*", "$1");

		mockMvc.perform(withXsrf(post("/api/auth/login"), null).contentType(MediaType.APPLICATION_JSON)
				.content("{\"loginId\":\"%s\",\"password\":\"anything\"}".formatted(loginId)))
			.andExpect(status().isUnauthorized());
	}

	// --- 만료·정리 ---

	@Test
	void myReservationsShowOnlyMyTrialAndExpireWithIt() throws Exception {
		Trial mine = startTrial();
		Trial theirs = startTrial();
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", mine.sessionId()), mine.visitor()))
			.andExpect(status().isCreated());
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", theirs.sessionId()), theirs.visitor()))
			.andExpect(status().isCreated());

		mockMvc.perform(get("/api/me/reservations").session(mine.visitor()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].sessionId").value(mine.sessionId()))
			.andExpect(jsonPath("$[0].status").value("WAITING"))
			.andExpect(jsonPath("$[0].location").value("온라인 체험룸"));

		clock.setInstant(now.plus(TTL));
		mockMvc.perform(get("/api/me/reservations").session(mine.visitor()))
			.andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("DEMO_EXPIRED"));
	}

	@Test
	void expiredTrialIsRejectedAndCleanupDeletesItsData() throws Exception {
		Trial trial = startTrial();
		long trialId = count("select demo_trial_id from study_session where id = ?", trial.sessionId());

		clock.setInstant(now.plus(TTL).minusSeconds(1));
		demoService.purgeExpired();
		mockMvc.perform(get("/api/sessions/{id}", trial.sessionId()).session(trial.visitor()))
			.andExpect(status().isOk());

		clock.setInstant(now.plus(TTL));
		mockMvc.perform(get("/api/sessions/{id}", trial.sessionId()).session(trial.visitor()))
			.andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("DEMO_EXPIRED"));
		sampleCancel(trial.visitor()).andExpect(status().isGone());

		demoService.purgeExpired();
		assertThat(count("select count(*) from demo_trial where id = ?", trialId)).isZero();
		assertThat(count("select count(*) from account where demo_trial_id = ?", trialId)).isZero();
		assertThat(count("select count(*) from study_session where demo_trial_id = ?", trialId)).isZero();
		assertThat(count("select count(*) from reservation where study_session_id = ?", trial.sessionId())).isZero();
	}

	// --- 남용 제한 ---

	@Test
	void startIsLimitedPerClientIp() throws Exception {
		for (int i = 0; i < 10; i++) {
			start(ip).andExpect(status().isCreated());
		}
		start(ip).andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
		start("100.127.0.9").andExpect(status().isCreated());
	}

	// --- helpers ---

	private ResultActions sampleCancel(MockHttpSession who) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/demo/sample-cancel"), who));
	}

	private long sampleReservationId(long sessionId) {
		return count("""
				select t.sample_reservation_id from demo_trial t
				join study_session s on s.demo_trial_id = t.id where s.id = ?
				""", sessionId);
	}

	private String statusOf(long reservationId) {
		return jdbc.queryForObject("select status from reservation where id = ?", String.class, reservationId);
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

}

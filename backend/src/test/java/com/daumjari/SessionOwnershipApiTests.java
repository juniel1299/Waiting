package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

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

import com.daumjari.account.Account;
import com.daumjari.account.Role;
import com.daumjari.reservation.Reservation;

/**
 * 회차 생성자 권한: 일반 회원도 회차를 만들고(생성자는 세션에서 결정), 본인 회차만 수정·정원·명단·삭제한다.
 * 운영자는 모든 회차의 명단을 보고 삭제할 수 있다. 다른 회원이 관리 API 를 직접 불러도 403.
 * 삭제는 물리 삭제이고 신청(확정·대기·취소)이 함께 지워지며 승격은 없다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SessionOwnershipApiTests extends ApiTestSupport {

	static final Instant STARTS_AT = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void memberCreatesASessionAndTheCreatorComesFromTheLoginSession() throws Exception {
		Account me = newAccount("own-me", Role.PARTICIPANT);
		Account other = newAccount("own-other", Role.PARTICIPANT);
		MockHttpSession session = login(me);

		MvcResult created = create(session, """
				{"title":"내 스터디","startsAt":"%s","location":"강남","capacity":3,"creatorAccountId":%d}
				""".formatted(STARTS_AT, other.getId())).andExpect(status().isCreated())
			.andExpect(jsonPath("$.canManage").value(true))
			.andExpect(jsonPath("$.canDelete").value(true))
			.andReturn();
		long id = idOf(created);
		assertThat(jdbc.queryForObject("select creator_account_id from study_session where id = ?", Long.class, id))
			.isEqualTo(me.getId());
		mockMvc.perform(get("/api/sessions/{id}", id).session(login(other)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.canManage").value(false))
			.andExpect(jsonPath("$.canDelete").value(false));
	}

	@Test
	void creatorEditsChangesCapacityReadsRosterAndDeletes() throws Exception {
		Account creator = newAccount("own-creator", Role.PARTICIPANT);
		MockHttpSession session = login(creator);
		long id = idOf(create(session, body("관리 회차", 1)).andReturn());
		Account member = newAccount("own-member", Role.PARTICIPANT);
		Account waiter = newAccount("own-waiter", Role.PARTICIPANT);
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/reservations", id), login(member))).andExpect(status().isCreated());
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", id), login(waiter))).andExpect(status().isCreated());

		mockMvc.perform(withXsrf(patch("/api/sessions/{id}", id), session).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"title":"바뀐 제목","startsAt":"%s","location":"잠실"}
					""".formatted(STARTS_AT.plus(1, ChronoUnit.DAYS))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("바뀐 제목"))
			.andExpect(jsonPath("$.location").value("잠실"));
		capacity(session, id, 2).andExpect(status().isOk()).andExpect(jsonPath("$.promotedCount").value(1));
		mockMvc.perform(get("/api/sessions/{id}/roster", id).session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2));

		mockMvc.perform(withXsrf(delete("/api/sessions/{id}", id), session)).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select count(*) from study_session where id = ?", Long.class, id)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from reservation where study_session_id = ?", Long.class, id))
			.isZero();
		mockMvc.perform(get("/api/sessions/{id}", id).session(session))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("삭제되었거나 찾을 수 없는 회차입니다."));
	}

	@Test
	void anotherMemberCannotManageSomeoneElsesSession() throws Exception {
		long id = idOf(create(login(newAccount("own-a", Role.PARTICIPANT)), body("남의 회차", 3)).andReturn());
		MockHttpSession intruder = login(newAccount("own-b", Role.PARTICIPANT));

		mockMvc.perform(withXsrf(patch("/api/sessions/{id}", id), intruder).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"title":"x","startsAt":"%s","location":"x"}
					""".formatted(STARTS_AT))).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_SESSION_OWNER"));
		capacity(intruder, id, 5).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/sessions/{id}/roster", id).session(intruder)).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/sessions/{id}", id), intruder)).andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("select capacity from study_session where id = ?", Integer.class, id)).isEqualTo(3);
	}

	@Test
	void operatorReadsRosterAndDeletesAnySessionButCannotEditOthers() throws Exception {
		long id = idOf(create(login(newAccount("own-c", Role.PARTICIPANT)), body("운영자가 볼 회차", 3)).andReturn());
		MockHttpSession operator = login(newAccount("own-op", Role.OPERATOR));

		mockMvc.perform(get("/api/sessions/{id}", id).session(operator))
			.andExpect(jsonPath("$.canManage").value(false))
			.andExpect(jsonPath("$.canDelete").value(true));
		mockMvc.perform(get("/api/sessions/{id}/roster", id).session(operator)).andExpect(status().isOk());
		capacity(operator, id, 5).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/sessions/{id}", id), operator)).andExpect(status().isNoContent());
	}

	@Test
	void deletingASessionWithWaitersPromotesNobodyAndRemovesEveryApplication() throws Exception {
		Account creator = newAccount("own-full", Role.PARTICIPANT);
		MockHttpSession session = login(creator);
		long id = idOf(create(session, body("마감 회차", 1)).andReturn());
		Account holder = newAccount("own-holder", Role.PARTICIPANT);
		MockHttpSession holderSession = login(holder);
		String held = mockMvc.perform(withXsrf(post("/api/sessions/{id}/reservations", id), holderSession))
			.andReturn().getResponse().getContentAsString();
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", id), login(newAccount("own-w1", Role.PARTICIPANT))));
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", id), login(newAccount("own-w2", Role.PARTICIPANT))));
		// 취소된 신청도 함께 지워진다
		mockMvc.perform(withXsrf(post("/api/reservations/{rid}/cancel", Long.parseLong(held.replaceAll(".*\"id\":(\\d+).*", "$1"))),
				holderSession)).andExpect(status().isOk());

		mockMvc.perform(withXsrf(delete("/api/sessions/{id}", id), session)).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select count(*) from reservation where study_session_id = ?", Long.class, id))
			.isZero();
		mockMvc.perform(withXsrf(delete("/api/sessions/{id}", id), session)).andExpect(status().isNotFound());
	}

	@Test
	void demoVisitorsCannotCreateAndDemoSessionsAreNotManageable() throws Exception {
		MvcResult demo = mockMvc.perform(withXsrf(post("/api/demo/start"), null).with(request -> {
			request.setRemoteAddr(SignupApiTests.randomIp());
			return request;
		})).andExpect(status().isCreated()).andReturn();
		MockHttpSession visitor = (MockHttpSession) demo.getRequest().getSession(false);
		long trialSession = Long.parseLong(demo.getResponse().getContentAsString().replaceAll(".*\"sessionId\":(\\d+).*", "$1"));

		create(visitor, body("체험자가 만든 회차", 2)).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/sessions/{id}", trialSession), visitor)).andExpect(status().isNotFound());
		mockMvc.perform(withXsrf(delete("/api/sessions/{id}", trialSession), login(newAccount("own-op2", Role.OPERATOR))))
			.andExpect(status().isNotFound());
	}

	@Test
	void creatorWithdrawalDeletesTheirSessionsWithApplications() throws Exception {
		Account creator = newAccount("own-leaver", Role.PARTICIPANT);
		MockHttpSession session = login(creator);
		long id = idOf(create(session, body("탈퇴자 회차", 2)).andReturn());
		Reservation joined = confirmDirectly(sessions.findById(id).orElseThrow(), newAccount("own-joiner", Role.PARTICIPANT));

		mockMvc.perform(get("/api/me/sessions").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(id))
			.andExpect(jsonPath("$[0].confirmedCount").value(1));
		mockMvc.perform(withXsrf(post("/api/me/withdraw"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(PASSWORD))).andExpect(status().isNoContent());

		assertThat(jdbc.queryForObject("select count(*) from study_session where id = ?", Long.class, id)).isZero();
		assertThat(reservations.findById(joined.getId())).isEmpty();
		// 회차 삭제(영속성 컨텍스트 비움) 뒤에도 계정 익명화가 저장된다 (회귀: 2026-11-22 발견)
		assertThat(jdbc.queryForObject("select deleted_at is not null and login_id like 'deleted-%' from account where id = ?",
				Boolean.class, creator.getId())).isTrue();
	}

	private ResultActions create(MockHttpSession session, String json) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/sessions"), session).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions capacity(MockHttpSession session, long id, int capacity) throws Exception {
		return mockMvc.perform(withXsrf(patch("/api/sessions/{id}/capacity", id), session)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"capacity\":" + capacity + "}"));
	}

	private static String body(String title, int capacity) {
		return """
				{"title":"%s","startsAt":"%s","location":"테스트룸","capacity":%d}
				""".formatted(title, STARTS_AT, capacity);
	}

	private static long idOf(MvcResult result) throws Exception {
		return Long.parseLong(result.getResponse().getContentAsString().replaceAll("^\\{\"id\":(\\d+).*", "$1"));
	}

}

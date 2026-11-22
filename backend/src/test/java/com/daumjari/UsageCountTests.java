package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;
import com.daumjari.studysession.StudySession;
import com.daumjari.usage.Metric;
import com.daumjari.usage.UsageCounter;

/**
 * 누적 사용량: 성공한 동작만 같은 트랜잭션에서 날짜(서울)·지표·데모 여부별로 센다. 원본을 지워도 남고,
 * 롤백·중복 요청에는 늘지 않는다. 개인정보·신청 id 는 남기지 않는다(표에 칸이 없음).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class UsageCountTests extends ApiTestSupport {

	static final Instant STARTS_AT = Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);

	@Autowired
	JdbcTemplate jdbc;

	@MockitoSpyBean
	UsageCounter counter;

	@AfterEach
	void restore() {
		reset(spy());
	}

	@Test
	void successfulActionsAreCountedAndSurviveDeletion() throws Exception {
		long signup = total(Metric.SIGNUP, false);
		long created = total(Metric.SESSION_CREATED, false);
		long confirmed = total(Metric.RESERVATION_CONFIRMED, false);
		long waitlisted = total(Metric.WAITLIST_JOINED, false);
		long promoted = total(Metric.RESERVATION_PROMOTED, false);
		long cancelled = total(Metric.RESERVATION_CANCELLED, false);
		long deleted = total(Metric.SESSION_DELETED, false);

		mockMvc.perform(withXsrf(post("/api/auth/signup"), null).with(r -> {
			r.setRemoteAddr(SignupApiTests.randomIp());
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content("""
				{"loginId":"%s","password":"%s","passwordConfirm":"%s"}
				""".formatted(SignupApiTests.uniqueId(), PASSWORD, PASSWORD))).andExpect(status().isCreated());
		MockHttpSession creator = login(newAccount("u-creator", Role.PARTICIPANT));
		long id = Long.parseLong(mockMvc.perform(withXsrf(post("/api/sessions"), creator)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"title":"집계","startsAt":"%s","location":"x","capacity":1}
					""".formatted(STARTS_AT))).andReturn().getResponse().getContentAsString().replaceAll("^\\{\"id\":(\\d+).*", "$1"));
		MockHttpSession holder = login(newAccount("u-holder", Role.PARTICIPANT));
		String held = mockMvc.perform(withXsrf(post("/api/sessions/{id}/reservations", id), holder))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		// 같은 계정의 중복 예약은 기존 신청을 돌려줄 뿐 세지 않는다
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/reservations", id), holder)).andExpect(status().isOk());
		MockHttpSession waiter = login(newAccount("u-waiter", Role.PARTICIPANT));
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", id), waiter)).andExpect(status().isCreated());
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", id), waiter)).andExpect(status().isOk());
		long heldId = Long.parseLong(held.replaceAll(".*\"id\":(\\d+).*", "$1"));
		mockMvc.perform(withXsrf(post("/api/reservations/{rid}/cancel", heldId), holder)).andExpect(status().isOk());
		// 이미 취소된 신청을 다시 취소해도 세지 않는다
		mockMvc.perform(withXsrf(post("/api/reservations/{rid}/cancel", heldId), holder)).andExpect(status().isOk());
		mockMvc.perform(withXsrf(delete("/api/sessions/{id}", id), creator)).andExpect(status().isNoContent());

		assertThat(total(Metric.SIGNUP, false)).isEqualTo(signup + 1);
		assertThat(total(Metric.SESSION_CREATED, false)).isEqualTo(created + 1);
		assertThat(total(Metric.RESERVATION_CONFIRMED, false)).isEqualTo(confirmed + 1);
		assertThat(total(Metric.WAITLIST_JOINED, false)).isEqualTo(waitlisted + 1);
		assertThat(total(Metric.RESERVATION_PROMOTED, false)).isEqualTo(promoted + 1);
		assertThat(total(Metric.RESERVATION_CANCELLED, false)).isEqualTo(cancelled + 1);
		assertThat(total(Metric.SESSION_DELETED, false)).isEqualTo(deleted + 1);
		assertThat(jdbc.queryForObject("select count(*) from usage_meta where key = 'counting_started_at'", Long.class))
			.isEqualTo(1L);
	}

	@Test
	void aRolledBackDeleteCountsNothingAndKeepsEverything() throws Exception {
		Account creatorAccount = newAccount("u-rb", Role.PARTICIPANT);
		MockHttpSession creator = login(creatorAccount);
		StudySession session = newSession("롤백 회차", STARTS_AT, 2);
		jdbc.update("update study_session set creator_account_id = ? where id = ?", creatorAccount.getId(), session.getId());
		confirmDirectly(session, newAccount("u-rb-m", Role.PARTICIPANT));
		long deleted = total(Metric.SESSION_DELETED, false);
		doThrow(new IllegalStateException("집계 저장 실패 주입")).when(spy()).increment(eq(Metric.SESSION_DELETED), anyBoolean());

		assertThatThrownBy(() -> mockMvc.perform(withXsrf(delete("/api/sessions/{id}", session.getId()), creator)))
			.hasRootCauseMessage("집계 저장 실패 주입");

		assertThat(jdbc.queryForObject("select count(*) from study_session where id = ?", Long.class, session.getId()))
			.isEqualTo(1L);
		assertThat(jdbc.queryForObject("select count(*) from reservation where study_session_id = ?", Long.class,
				session.getId()))
			.isEqualTo(1L);
		assertThat(total(Metric.SESSION_DELETED, false)).isEqualTo(deleted);
	}

	@Test
	void demoActionsAreCountedSeparately() throws Exception {
		long demoStarts = total(Metric.DEMO_STARTED, true);
		long demoWaitlist = total(Metric.WAITLIST_JOINED, true);
		var demo = mockMvc.perform(withXsrf(post("/api/demo/start"), null).with(r -> {
			r.setRemoteAddr(SignupApiTests.randomIp());
			return r;
		})).andExpect(status().isCreated()).andReturn();
		MockHttpSession visitor = (MockHttpSession) demo.getRequest().getSession(false);
		long sid = Long.parseLong(demo.getResponse().getContentAsString().replaceAll(".*\"sessionId\":(\\d+).*", "$1"));
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", sid), visitor)).andExpect(status().isCreated());
		mockMvc.perform(get("/api/sessions/{id}", sid).session(visitor)).andExpect(status().isOk());

		assertThat(total(Metric.DEMO_STARTED, true)).isEqualTo(demoStarts + 1);
		assertThat(total(Metric.WAITLIST_JOINED, true)).isEqualTo(demoWaitlist + 1);
	}

	private long total(Metric metric, boolean demo) {
		return jdbc.queryForObject("select coalesce(sum(count), 0) from usage_daily where metric = ? and demo = ?",
				Long.class, metric.name(), demo);
	}

	private UsageCounter spy() {
		return AopTestUtils.getUltimateTargetObject(counter);
	}

}

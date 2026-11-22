package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;
import com.daumjari.studysession.StudySession;
import com.jayway.jsonpath.JsonPath;

/**
 * 운영자 사용량 대시보드: 현재 데이터(지금 남아 있는 행 수, 삭제되면 줄어듦)와 누적 사용량(usage_daily, 원본을 지워도 유지)을
 * 나누고, 일반·데모를 나눈다. 누적은 서울 날짜 범위로 합계·일별을 돌려주고, 집계 시작 시각을 함께 준다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class UsageDashboardApiTests extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void currentDataFallsWhenRowsAreDeletedButCumulativeUsageStays() throws Exception {
		MockHttpSession operator = login(newAccount("dash-op", Role.OPERATOR));
		Map<String, Object> before = current(operator, "regular");
		Account member = newAccount("dash-member", Role.PARTICIPANT);

		StudySession session = newSession("대시보드 회차", Instant.now().plus(2, ChronoUnit.DAYS), 2);
		confirmDirectly(session, member);
		Map<String, Object> during = current(operator, "regular");
		assertThat(delta(during, before, "sessions")).isEqualTo(1);
		assertThat(delta(during, before, "confirmed")).isEqualTo(1);
		assertThat(delta(during, before, "accounts")).isEqualTo(1);

		long deletedBefore = total(operator, "SESSION_DELETED", "regular");
		mockMvc.perform(withXsrf(delete("/api/sessions/{id}", session.getId()), operator))
			.andExpect(status().isNoContent());
		Map<String, Object> after = current(operator, "regular");
		assertThat(delta(after, before, "sessions")).isZero();
		assertThat(delta(after, before, "confirmed")).isZero();
		assertThat(total(operator, "SESSION_DELETED", "regular")).isEqualTo(deletedBefore + 1);
	}

	@Test
	void cumulativeUsageIsSummedByMetricAndDayWithinTheSeoulDateRangeSplittingDemo() throws Exception {
		MockHttpSession operator = login(newAccount("dash-op2", Role.OPERATOR));
		// 집계 도입 이전 날짜의 행을 직접 넣어 범위·합계·일별을 확인한다 (테스트 DB 를 공유하므로 다른 테스트와 겹치지 않는 날짜)
		jdbc.update("delete from usage_daily where day between '2001-02-01' and '2001-02-28'");
		jdbc.update("""
				insert into usage_daily (day, metric, demo, count) values
				('2001-02-01', 'SIGNUP', false, 3), ('2001-02-02', 'SIGNUP', false, 2),
				('2001-02-02', 'RESERVATION_CONFIRMED', true, 5), ('2001-02-02', 'RESERVATION_PROMOTED', false, 1),
				('2001-02-03', 'SIGNUP', false, 7)
				""");

		String body = mockMvc.perform(get("/api/operator/usage").param("from", "2001-02-01").param("to", "2001-02-02")
			.session(operator))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.from").value("2001-02-01"))
			.andExpect(jsonPath("$.to").value("2001-02-02"))
			.andExpect(jsonPath("$.countingStartedAt").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);

		assertThat(totalOf(body, "SIGNUP", "regular")).isEqualTo(5);
		assertThat(totalOf(body, "SIGNUP", "demo")).isZero();
		assertThat(totalOf(body, "RESERVATION_CONFIRMED", "demo")).isEqualTo(5);
		assertThat(totalOf(body, "RESERVATION_PROMOTED", "regular")).isEqualTo(1);
		assertThat(totalOf(body, "SESSION_DELETED", "regular")).isZero();
		List<String> metrics = JsonPath.read(body, "$.totals[*].metric");
		assertThat(metrics).containsExactly("SIGNUP", "DEMO_STARTED", "SESSION_CREATED", "RESERVATION_CONFIRMED",
				"RESERVATION_PROMOTED", "WAITLIST_JOINED", "RESERVATION_CANCELLED", "SESSION_DELETED");
		List<Object> days = JsonPath.read(body, "$.daily[?(@.metric == 'SIGNUP')].day");
		assertThat(days).containsExactly("2001-02-01", "2001-02-02");
	}

	@Test
	void rejectsBadRangesAndNonOperators() throws Exception {
		MockHttpSession operator = login(newAccount("dash-op3", Role.OPERATOR));
		for (String[] range : List.of(new String[] { "2001-02-03", "2001-02-01" },
				new String[] { "2001-01-01", "2002-01-03" }, new String[] { "2001-13-01", "2001-02-01" })) {
			mockMvc.perform(get("/api/operator/usage").param("from", range[0]).param("to", range[1]).session(operator))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		// 기본 범위: 오늘(서울)까지 최근 30일
		mockMvc.perform(get("/api/operator/usage").session(operator))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.current.regular.accounts").isNumber())
			.andExpect(jsonPath("$.current.demo.sessions").isNumber());
		mockMvc.perform(get("/api/operator/usage").session(login(newAccount("dash-p", Role.PARTICIPANT))))
			.andExpect(status().isForbidden());
	}

	private Map<String, Object> current(MockHttpSession operator, String kind) throws Exception {
		String body = mockMvc.perform(get("/api/operator/usage").session(operator))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		return JsonPath.read(body, "$.current." + kind);
	}

	private long total(MockHttpSession operator, String metric, String kind) throws Exception {
		String body = mockMvc.perform(get("/api/operator/usage").session(operator))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		return totalOf(body, metric, kind);
	}

	private static long totalOf(String body, String metric, String kind) {
		List<Number> values = JsonPath.read(body, "$.totals[?(@.metric == '" + metric + "')]." + kind);
		return values.get(0).longValue();
	}

	private static long delta(Map<String, Object> after, Map<String, Object> before, String key) {
		return ((Number) after.get(key)).longValue() - ((Number) before.get(key)).longValue();
	}

}

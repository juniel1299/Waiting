package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;
import com.daumjari.errorlog.ErrorLog;

/**
 * 확장 3단계: 에러 로그. 예상하지 못한 서버 오류(500)와 화면 오류 보고를 지문별로 모아 횟수만 늘리고, 비밀값은 저장 전에 지운다.
 * 사용자 실수(4xx)는 모으지 않는다. 개발자만 본다(운영자만으로는 403). 같은 오류 알림은 개발자에게 1시간에 한 번, 30일 지나면 지운다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ErrorLogApiTests extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ErrorLog errorLog;

	Account dev;

	MockHttpSession developer;

	MockHttpSession member;

	@BeforeEach
	void setUp() throws Exception {
		dev = newAccount("el-dev", Role.PARTICIPANT);
		jdbc.update("insert into account_role (account_id, role) values (?, 'DEVELOPER')", dev.getId());
		developer = login(dev);
		member = login(newAccount("el-member", Role.PARTICIPANT));
	}

	@Test
	void unexpectedServerErrorsAreGroupedMaskedAndGiveATraceId() throws Exception {
		String marker = UUID.randomUUID().toString().substring(0, 8);
		String trace = mockMvc.perform(get("/api/test/boom/{id}", 1).session(member)).andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.traceId").isNotEmpty())
			.andExpect(header().exists("X-Trace-Id"))
			.andReturn().getResponse().getHeader("X-Trace-Id");
		mockMvc.perform(get("/api/test/boom/{id}", 2).session(member)).andExpect(status().isInternalServerError());

		Map<String, Object> row = jdbc.queryForMap(
				"select source, message, path, occurrences, last_trace_id from error_group where path = '/api/test/boom/:id'");
		assertThat(row.get("source")).isEqualTo("BACKEND");
		assertThat((Long) row.get("occurrences")).isGreaterThanOrEqualTo(2L);
		assertThat((String) row.get("message")).contains("IllegalStateException")
			.doesNotContain("hunter2", "user@example.com", "eyJhbGci", "abcdef0123456789abcdef");
		assertThat(trace).hasSize(16);
		assertThat(marker).isNotEmpty();

		// 사용자 실수(404·400)는 모으지 않는다
		long before = jdbc.queryForObject("select count(*) from error_group", Long.class);
		mockMvc.perform(get("/api/posts/{id}", 99999999).session(member)).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select count(*) from error_group", Long.class)).isEqualTo(before);
	}

	@Test
	void clientReportsAreAcceptedFromAnyoneButMaskedAndTemplated() throws Exception {
		String unique = "TypeError: x is undefined " + marker();
		mockMvc.perform(withXsrf(post("/api/errors"), null).contentType(MediaType.APPLICATION_JSON)
			.content("{\"message\":\"%s token=abc123secret\",\"path\":\"/sessions/42?q=1\",\"release\":\"abc1234\"}".formatted(unique)))
			.andExpect(status().isAccepted());
		Map<String, Object> row = jdbc.queryForMap("select source, message, path, release from error_group where message like ?",
				"%" + unique.substring(unique.length() - 8) + "%");
		assertThat(row.get("source")).isEqualTo("FRONTEND");
		assertThat(row.get("path")).isEqualTo("/sessions/:id");
		assertThat(row.get("release")).isEqualTo("abc1234");
		assertThat((String) row.get("message")).doesNotContain("abc123secret");

		mockMvc.perform(withXsrf(post("/api/errors"), null).contentType(MediaType.APPLICATION_JSON)
			.content("{\"message\":\"\",\"path\":\"" + "/x".repeat(400) + "\"}")).andExpect(status().isAccepted());
	}

	@Test
	void onlyDevelopersReadTheErrorLog() throws Exception {
		MockHttpSession operator = login(newAccount("el-op", Role.OPERATOR));
		mockMvc.perform(get("/api/dev/errors").session(member)).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/dev/errors").session(operator)).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/dev/errors")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/test/boom/{id}", 3).session(member));
		mockMvc.perform(get("/api/dev/errors").session(developer).param("source", "BACKEND").param("q", "IllegalState"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].path").value("/api/test/boom/:id"))
			.andExpect(jsonPath("$.items[0].occurrences").isNumber())
			.andExpect(jsonPath("$.items[0].firstSeenAt").isNotEmpty())
			.andExpect(jsonPath("$.items[0].lastTraceId").isNotEmpty());
	}

	@Test
	void developersAreAlertedOncePerHourPerErrorAndOldGroupsArePurged() throws Exception {
		String unique = "알림 " + marker();
		report(unique);
		report(unique);
		long group = jdbc.queryForObject("select id from error_group where message like ?", Long.class, "%" + unique + "%");
		assertThat(alerts(group)).isEqualTo(1);

		jdbc.update("update error_group set last_alerted_at = last_alerted_at - interval '61 minutes' where id = ?", group);
		report(unique);
		assertThat(alerts(group)).isEqualTo(2);
		mockMvc.perform(get("/api/me/notifications").session(developer))
			.andExpect(jsonPath("$.items[0].type").value("ERROR_ALERT"))
			.andExpect(jsonPath("$.items[0].errorMessage").isNotEmpty());
		assertThat(jdbc.queryForObject("select count(*) from notification where error_group_id = ? and recipient_account_id <> ?",
				Long.class, group, dev.getId())).isGreaterThanOrEqualTo(0);

		jdbc.update("update error_group set last_seen_at = now() - interval '31 days' where id = ?", group);
		errorLog.purgeExpired();
		assertThat(jdbc.queryForObject("select count(*) from error_group where id = ?", Long.class, group)).isZero();
		assertThat(alerts(group)).isZero();
	}

	private void report(String message) throws Exception {
		mockMvc.perform(withXsrf(post("/api/errors"), null).contentType(MediaType.APPLICATION_JSON)
			.content("{\"message\":\"%s\",\"path\":\"/board\"}".formatted(message))).andExpect(status().isAccepted());
	}

	private long alerts(long group) {
		return jdbc.queryForObject("select count(*) from notification where error_group_id = ? and recipient_account_id = ?", Long.class,
				group, dev.getId());
	}

	/** 가림 규칙(긴 영숫자 덩어리)에 걸리지 않는 고유 표지: UUID 16진수를 한글 글자로 바꾼다. */
	static String marker() {
		StringBuilder out = new StringBuilder();
		for (char c : UUID.randomUUID().toString().replace("-", "").substring(0, 12).toCharArray()) {
			out.append((char) ('가' + Character.digit(c, 16)));
		}
		return out.toString();
	}

}

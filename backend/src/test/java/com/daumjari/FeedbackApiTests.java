package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * 확장 3단계: 개선사항. 회원만 접수(데모 403·비회원 401, 계정당 횟수 제한), 작성자는 자기 것만(남의 것은 404),
 * 개발자만 전체 검색·상태 변경(운영자만으로는 403). 검증 요청·보류는 사유 필수, 상태가 바뀌면 기록과 작성자 알림, 낡은 version 은 409.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class FeedbackApiTests extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	Account author;

	MockHttpSession authorSession;

	MockHttpSession developer;

	@BeforeEach
	void setUp() throws Exception {
		author = newAccount("fb-author", Role.PARTICIPANT);
		authorSession = login(author);
		Account dev = newAccount("fb-dev", Role.PARTICIPANT);
		jdbc.update("insert into account_role (account_id, role) values (?, 'DEVELOPER')", dev.getId());
		developer = login(dev);
	}

	@Test
	void membersSubmitAndSeeOnlyTheirOwn() throws Exception {
		long id = submit(authorSession, "검색이 느려요").andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("RECEIVED")).andReturnId();
		mockMvc.perform(get("/api/me/feedback").session(authorSession)).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(id));
		mockMvc.perform(get("/api/feedback/{id}", id).session(authorSession)).andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("검색이 느려요"));

		MockHttpSession other = login(newAccount("fb-other", Role.PARTICIPANT));
		mockMvc.perform(get("/api/feedback/{id}", id).session(other)).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/me/feedback").session(other)).andExpect(jsonPath("$.items.length()").value(0));

		mockMvc.perform(withXsrf(post("/api/feedback"), authorSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"category\":\"BUG\",\"title\":\" \",\"body\":\"본문\"}")).andExpect(status().isBadRequest());
		mockMvc.perform(withXsrf(post("/api/feedback"), null).contentType(MediaType.APPLICATION_JSON)
			.content("{\"category\":\"BUG\",\"title\":\"비회원\",\"body\":\"본문\"}")).andExpect(status().isUnauthorized());
		MockHttpSession demo = (MockHttpSession) mockMvc.perform(withXsrf(post("/api/demo/start"), null).with(r -> {
			r.setRemoteAddr("100.70.3." + (int) (Math.random() * 200 + 1));
			return r;
		})).andExpect(status().isCreated()).andReturn().getRequest().getSession(false);
		submit(demo, "데모 요청").andExpect(status().isForbidden());
	}

	@Test
	void onlyDevelopersManageAndOperatorsAloneCannot() throws Exception {
		long id = submit(authorSession, "다크 모드 대비").andReturnId();
		MockHttpSession operator = login(newAccount("fb-op", Role.OPERATOR));
		for (MockHttpSession s : new MockHttpSession[] { authorSession, operator }) {
			mockMvc.perform(get("/api/dev/feedback").session(s)).andExpect(status().isForbidden());
			change(s, id, "IN_PROGRESS", null, 0).andExpect(status().isForbidden());
		}
		mockMvc.perform(get("/api/dev/feedback").session(developer).param("q", "다크").param("status", "RECEIVED"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(id))
			.andExpect(jsonPath("$.items[0].author").isNotEmpty());
		mockMvc.perform(get("/api/dev/feedback").session(developer).param("status", "DONE"))
			.andExpect(jsonPath("$.items[?(@.id == %d)]".formatted(id)).isEmpty());
	}

	@Test
	void statusChangesNeedReasonsWhereRequiredAreLoggedAndNotifyTheAuthor() throws Exception {
		long id = submit(authorSession, "알림이 늦어요").andReturnId();
		change(developer, id, "ON_HOLD", " ", 0).andExpect(status().isBadRequest());
		change(developer, id, "IN_PROGRESS", null, 0).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
		change(developer, id, "IN_PROGRESS", null, 0).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("FEEDBACK_CHANGED"));
		mockMvc.perform(withXsrf(patch("/api/dev/feedback/{id}", id), developer).contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"VERIFY_REQUESTED\",\"reason\":\"배포 뒤 확인 부탁\",\"resolution\":\"폴링 간격 조정\","
					+ "\"prUrl\":\"https://example.com/x\",\"version\":1}"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(withXsrf(patch("/api/dev/feedback/{id}", id), developer).contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"VERIFY_REQUESTED\",\"reason\":\"배포 뒤 확인 부탁\",\"resolution\":\"폴링 간격 조정\","
					+ "\"prUrl\":\"https://github.com/juniel1299/Waiting/pull/52\",\"version\":1}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("VERIFY_REQUESTED"))
			.andExpect(jsonPath("$.prUrl").value("https://github.com/juniel1299/Waiting/pull/52"));

		mockMvc.perform(get("/api/feedback/{id}", id).session(authorSession))
			.andExpect(jsonPath("$.status").value("VERIFY_REQUESTED"))
			.andExpect(jsonPath("$.resolution").value("폴링 간격 조정"))
			.andExpect(jsonPath("$.history[0].toStatus").value("VERIFY_REQUESTED"))
			.andExpect(jsonPath("$.history[0].reason").value("배포 뒤 확인 부탁"));
		assertThat(jdbc.queryForObject("select count(*) from feedback_history where feedback_id = ?", Long.class, id)).isEqualTo(2);
		mockMvc.perform(get("/api/me/notifications").session(authorSession))
			.andExpect(jsonPath("$.items[0].type").value("FEEDBACK_STATUS"))
			.andExpect(jsonPath("$.items[0].feedbackTitle").value("알림이 늦어요"));
	}

	@Test
	void submissionsPerAccountAreLimited() throws Exception {
		MockHttpSession busy = login(newAccount("fb-busy", Role.PARTICIPANT));
		for (int i = 0; i < 5; i++) {
			submit(busy, "요청 " + i).andExpect(status().isCreated());
		}
		submit(busy, "요청 6").andExpect(status().isTooManyRequests());
	}

	private Submitted submit(MockHttpSession s, String title) throws Exception {
		return new Submitted(mockMvc.perform(withXsrf(post("/api/feedback"), s).contentType(MediaType.APPLICATION_JSON)
			.content("{\"category\":\"IDEA\",\"title\":\"%s\",\"body\":\"자세한 내용\",\"pagePath\":\"/sessions/3?x=1\"}".formatted(title))));
	}

	private ResultActions change(MockHttpSession s, long id, String to, String reason, long version) throws Exception {
		String r = reason == null ? "null" : "\"" + reason + "\"";
		return mockMvc.perform(withXsrf(patch("/api/dev/feedback/{id}", id), s).contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"%s\",\"reason\":%s,\"version\":%d}".formatted(to, r, version)));
	}

	record Submitted(ResultActions actions) {

		Submitted andExpect(org.springframework.test.web.servlet.ResultMatcher m) throws Exception {
			actions.andExpect(m);
			return this;
		}

		long andReturnId() throws Exception {
			String body = actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
			return ((Number) JsonPath.read(body, "$.id")).longValue();
		}

	}

}

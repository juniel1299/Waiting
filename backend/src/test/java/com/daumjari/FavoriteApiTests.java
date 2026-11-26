package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
import com.daumjari.studysession.StudySession;
import com.jayway.jsonpath.JsonPath;

/**
 * 확장 2단계: 즐겨찾기(모임·회차). 계정·대상별 한 번(DB 유일), PUT·DELETE 멱등, 대상이 지워지면 함께 지워지고,
 * 목록은 지금 볼 수 있는 대상만 보인다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class FavoriteApiTests extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	Account me;

	MockHttpSession session;

	MockHttpSession operator;

	@BeforeEach
	void setUp() throws Exception {
		me = newAccount("fv-me", Role.PARTICIPANT);
		session = login(me);
		operator = login(newAccount("fv-op", Role.OPERATOR));
	}

	@Test
	void favoritingIsIdempotentAndListedByKind() throws Exception {
		StudySession s = newSession("즐겨찾기 회차", Instant.now().plus(3, ChronoUnit.DAYS), 3);
		long club = approvedClub("즐겨찾기 모임");
		for (int i = 0; i < 2; i++) {
			mockMvc.perform(withXsrf(put("/api/sessions/{id}/favorite", s.getId()), session)).andExpect(status().isOk())
				.andExpect(jsonPath("$.favorite").value(true));
			mockMvc.perform(withXsrf(put("/api/clubs/{id}/favorite", club), session)).andExpect(status().isOk());
		}
		assertThat(count("select count(*) from favorite where account_id = ?", me.getId())).isEqualTo(2);
		mockMvc.perform(get("/api/me/favorites").session(session)).andExpect(status().isOk())
			.andExpect(jsonPath("$.sessions[0].id").value(s.getId()))
			.andExpect(jsonPath("$.clubs[0].id").value(club));

		for (int i = 0; i < 2; i++) {
			mockMvc.perform(withXsrf(delete("/api/sessions/{id}/favorite", s.getId()), session)).andExpect(status().isOk())
				.andExpect(jsonPath("$.favorite").value(false));
		}
		assertThat(count("select count(*) from favorite where account_id = ? and session_id is not null", me.getId())).isZero();
	}

	@Test
	void deletingTheTargetRemovesFavoritesAndTheListAppliesCurrentVisibility() throws Exception {
		long club = approvedClub("지울 모임");
		mockMvc.perform(withXsrf(put("/api/clubs/{id}/favorite", club), session)).andExpect(status().isOk());
		mockMvc.perform(withXsrf(delete("/api/clubs/{id}", club), operator)).andExpect(status().isNoContent());
		assertThat(count("select count(*) from favorite where club_id = ?", club)).isZero();

		StudySession s = newSession("지울 회차", Instant.now().plus(3, ChronoUnit.DAYS), 3);
		mockMvc.perform(withXsrf(put("/api/sessions/{id}/favorite", s.getId()), session)).andExpect(status().isOk());
		mockMvc.perform(withXsrf(delete("/api/sessions/{id}", s.getId()), operator)).andExpect(status().isNoContent());
		assertThat(count("select count(*) from favorite where session_id = ?", s.getId())).isZero();

		// 승인 전 모임은 즐겨찾기할 수 없고(404), 남의 대기 모임은 목록에도 보이지 않는다
		String pending = mockMvc.perform(withXsrf(post("/api/clubs"), operator).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"대기 모임\",\"intro\":\"소개\",\"place\":\"온라인\",\"mode\":\"ONLINE\"}"))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		mockMvc.perform(withXsrf(put("/api/clubs/{id}/favorite", ((Number) JsonPath.read(pending, "$.id")).longValue()), session))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/me/favorites").session(session)).andExpect(jsonPath("$.clubs.length()").value(0));
	}

	@Test
	void withdrawalSelfOrForcedRemovesTheAccountsFavorites() throws Exception {
		long club = approvedClub("탈퇴 정리 모임");
		mockMvc.perform(withXsrf(put("/api/clubs/{id}/favorite", club), session)).andExpect(status().isOk());
		mockMvc.perform(withXsrf(post("/api/me/withdraw"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(PASSWORD))).andExpect(status().isNoContent());
		assertThat(count("select count(*) from favorite where account_id = ?", me.getId())).isZero();

		Account other = newAccount("fv-forced", Role.PARTICIPANT);
		mockMvc.perform(withXsrf(put("/api/clubs/{id}/favorite", club), login(other))).andExpect(status().isOk());
		mockMvc.perform(withXsrf(post("/api/operator/accounts/{id}/withdraw", other.getId()), operator)
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"정리 확인\"}")).andExpect(status().isOk());
		assertThat(count("select count(*) from favorite where account_id = ?", other.getId())).isZero();
	}

	@Test
	void demoAndAnonymousCannotFavorite() throws Exception {
		long club = approvedClub("데모 모임");
		mockMvc.perform(withXsrf(put("/api/clubs/{id}/favorite", club), null)).andExpect(status().isUnauthorized());
		MockHttpSession demo = (MockHttpSession) mockMvc.perform(withXsrf(post("/api/demo/start"), null).with(r -> {
			r.setRemoteAddr("100.69.7." + (int) (Math.random() * 200 + 1));
			return r;
		})).andExpect(status().isCreated()).andReturn().getRequest().getSession(false);
		mockMvc.perform(withXsrf(put("/api/clubs/{id}/favorite", club), demo)).andExpect(status().isForbidden());
	}

	private long approvedClub(String name) throws Exception {
		String created = mockMvc.perform(withXsrf(post("/api/clubs"), operator).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"%s\",\"intro\":\"소개\",\"place\":\"온라인\",\"mode\":\"ONLINE\"}".formatted(name)))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		long club = ((Number) JsonPath.read(created, "$.id")).longValue();
		mockMvc.perform(withXsrf(post("/api/operator/clubs/{id}/approve", club), operator).contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + JsonPath.read(created, "$.version") + "}")).andExpect(status().isOk());
		return club;
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

}

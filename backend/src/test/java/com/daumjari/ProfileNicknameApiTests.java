package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * 확장 2단계: 닉네임(정규화·규칙·DB 유일성·가입 시 무작위)과 프로필(소개·관심 태그, 공개 항목만).
 * 닉네임이 바뀌어도 글·멘션은 계정 id 로 이어져 새 닉네임으로 보인다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ProfileNicknameApiTests extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	Account me;

	MockHttpSession session;

	@BeforeEach
	void setUp() throws Exception {
		me = newAccount("pf-me", Role.PARTICIPANT);
		session = login(me);
	}

	@Test
	void signupGivesARandomKoreanNicknameThatCanBeChanged() throws Exception {
		String loginId = "pfnew" + UUID.randomUUID().toString().substring(0, 6);
		String body = mockMvc.perform(withXsrf(post("/api/auth/signup"), null).contentType(MediaType.APPLICATION_JSON)
			.content("{\"loginId\":\"%s\",\"password\":\"%s\",\"passwordConfirm\":\"%s\"}".formatted(loginId, PASSWORD, PASSWORD)))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		long id = ((Number) JsonPath.read(body, "$.id")).longValue();
		String nickname = jdbc.queryForObject("select nickname from account where id = ?", String.class, id);
		assertThat(nickname).matches("[가-힣]+[0-9]{1,4}");
	}

	@Test
	void nicknamesAreNormalizedValidatedAndUniqueIgnoringCase() throws Exception {
		// NFC 정규화(분리된 자모 → 완성형)·앞뒤 공백 제거·연속 공백 하나로
		nickname(session, "  한   Byeol  ").andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("한 Byeol"));
		for (String bad : new String[] { "a", "열세글자가넘는아주긴닉네임", "운영자", "관리자님", "회원12", "탈퇴한 회원", "새별!", "@[멘션#1]" }) {
			nickname(session, bad).andExpect(status().isBadRequest());
		}
		MockHttpSession other = login(newAccount("pf-other", Role.PARTICIPANT));
		nickname(other, "한 byeol").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NICKNAME_TAKEN"));
		mockMvc.perform(get("/api/me/nickname/check").session(other).param("value", "한 BYEOL"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false));
		mockMvc.perform(get("/api/me/nickname/check").session(other).param("value", "새로운별"))
			.andExpect(jsonPath("$.available").value(true));
		mockMvc.perform(get("/api/me/nickname/check").session(other).param("value", "운영자"))
			.andExpect(jsonPath("$.available").value(false)).andExpect(jsonPath("$.reason").isNotEmpty());
	}

	@Test
	void profileBioAndTagsAreEditableAndOnlyPublicFieldsAreExposed() throws Exception {
		mockMvc.perform(withXsrf(patch("/api/me/profile"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"nickname\":\"프로필별\",\"bio\":\"백엔드 공부 중\",\"tags\":[\"Java\",\" 스프링 \",\"Java\"]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.bio").value("백엔드 공부 중"))
			.andExpect(jsonPath("$.tags.length()").value(2));
		MockHttpSession viewer = login(newAccount("pf-viewer", Role.PARTICIPANT));
		String json = mockMvc.perform(get("/api/profiles/{id}", me.getId()).session(viewer)).andExpect(status().isOk())
			.andExpect(jsonPath("$.nickname").value("프로필별"))
			.andExpect(jsonPath("$.bio").value("백엔드 공부 중"))
			.andExpect(jsonPath("$.tags[1]").value("스프링"))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(json).doesNotContain(me.getLoginId()).doesNotContain("loginId").doesNotContain("role");
		mockMvc.perform(get("/api/profiles/{id}", me.getId())).andExpect(status().isUnauthorized());
		mockMvc.perform(withXsrf(patch("/api/me/profile"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"tags\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]}")).andExpect(status().isBadRequest());
	}

	@Test
	void renamingKeepsPostsAndMentionsLinkedByAccountId() throws Exception {
		MockHttpSession writer = login(newAccount("pf-writer", Role.PARTICIPANT));
		nickname(session, "옛이름").andExpect(status().isOk());
		String post = mockMvc.perform(withXsrf(post("/api/boards/community/posts"), writer).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"멘션 글\",\"body\":\"@[옛이름#%d] 안녕\"}".formatted(me.getId())))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		long postId = ((Number) JsonPath.read(post, "$.id")).longValue();
		nickname(session, "새이름").andExpect(status().isOk());
		mockMvc.perform(get("/api/posts/{id}", postId).session(writer)).andExpect(jsonPath("$.mentions[0].nickname").value("새이름"));
	}

	@Test
	void withdrawalFreesTheNickname() throws Exception {
		Account leaver = newAccount("pf-leaver", Role.PARTICIPANT);
		MockHttpSession leaverSession = login(leaver);
		nickname(leaverSession, "떠날이름").andExpect(status().isOk());
		mockMvc.perform(withXsrf(post("/api/me/withdraw"), leaverSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(PASSWORD))).andExpect(status().isNoContent());
		nickname(session, "떠날이름").andExpect(status().isOk());
	}

	private ResultActions nickname(MockHttpSession s, String value) throws Exception {
		return mockMvc.perform(withXsrf(patch("/api/me/profile"), s).contentType(MediaType.APPLICATION_JSON)
			.content("{\"nickname\":\"%s\"}".formatted(value.replace("\\", "\\\\").replace("\"", "\\\""))));
	}

}

package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * 확장 1단계: 서비스 역할(운영자·개발자, 한 계정에 여러 개)과 서비스 권한 관리.
 * 운영자만 부여·회수(사유 필수, 기록), 자기 자신은 바꾸지 못하고, 개발자 역할만으로는 운영 권한이 생기지 않는다.
 * 부여·회수는 이미 로그인한 세션의 다음 요청부터 반영된다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ServiceRoleApiTests extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	Account operator;

	MockHttpSession operatorSession;

	Account member;

	MockHttpSession memberSession;

	@BeforeEach
	void setUp() throws Exception {
		operator = newAccount("sr-op", Role.OPERATOR);
		operatorSession = login(operator);
		member = newAccount("sr-member", Role.PARTICIPANT);
		memberSession = login(member);
	}

	@Test
	void operatorsHaveTheOperatorRoleAndMembersHaveNone() throws Exception {
		mockMvc.perform(get("/api/auth/me").session(operatorSession)).andExpect(jsonPath("$.roles[0]").value("OPERATOR"));
		mockMvc.perform(get("/api/auth/me").session(memberSession)).andExpect(jsonPath("$.roles.length()").value(0));
	}

	@Test
	void operatorGrantsAndRevokesWithAReasonAndEachChangeIsLoggedAndAppliesToTheLiveSession() throws Exception {
		grant(operatorSession, member, "DEVELOPER", "에러 로그 담당").andExpect(status().isOk())
			.andExpect(jsonPath("$.roles[0]").value("DEVELOPER"));
		// 다시 로그인하지 않은 회원 세션에도 바로 반영
		mockMvc.perform(get("/api/auth/me").session(memberSession)).andExpect(jsonPath("$.roles[0]").value("DEVELOPER"));

		grant(operatorSession, member, "OPERATOR", "운영 인력 추가").andExpect(status().isOk());
		mockMvc.perform(get("/api/auth/me").session(memberSession))
			.andExpect(jsonPath("$.roles.length()").value(2))
			.andExpect(jsonPath("$.role").value("OPERATOR"));
		mockMvc.perform(get("/api/operator/roles").session(memberSession)).andExpect(status().isOk());

		revoke(operatorSession, member, "OPERATOR", "역할 정리").andExpect(status().isOk());
		mockMvc.perform(get("/api/operator/roles").session(memberSession)).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/auth/me").session(memberSession)).andExpect(jsonPath("$.role").value("PARTICIPANT"));

		mockMvc.perform(get("/api/operator/roles/changes").session(operatorSession)).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].action").value("REVOKE"))
			.andExpect(jsonPath("$.items[0].role").value("OPERATOR"))
			.andExpect(jsonPath("$.items[0].reason").value("역할 정리"))
			.andExpect(jsonPath("$.items[0].actor").isNotEmpty())
			.andExpect(jsonPath("$.items[0].createdAt").isNotEmpty());
		assertThat(jdbc.queryForObject("select count(*) from account_role_change where target_account_id = ?", Long.class,
				member.getId())).isEqualTo(3);
	}

	@Test
	void reasonIsRequiredAndUnknownRolesAreRejected() throws Exception {
		grant(operatorSession, member, "DEVELOPER", " ").andExpect(status().isBadRequest());
		grant(operatorSession, member, "ADMIN", "없는 역할").andExpect(status().isBadRequest());
	}

	@Test
	void membersDevelopersAndOperatorsThemselvesCannotRaiseOrChangeTheirOwnRoles() throws Exception {
		grant(memberSession, member, "OPERATOR", "셀프").andExpect(status().isForbidden());
		mockMvc.perform(get("/api/operator/roles").session(memberSession)).andExpect(status().isForbidden());
		grant(operatorSession, member, "DEVELOPER", "개발 담당").andExpect(status().isOk());
		grant(memberSession, member, "OPERATOR", "개발자의 셀프 상승").andExpect(status().isForbidden());
		grant(operatorSession, operator, "DEVELOPER", "운영자 자기 변경").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("SELF_ROLE_CHANGE"));
		revoke(operatorSession, operator, "OPERATOR", "운영자 자기 회수").andExpect(status().isForbidden());
	}

	@Test
	void theDeveloperRoleAloneGrantsNoOperatorPowers() throws Exception {
		grant(operatorSession, member, "DEVELOPER", "개발 담당").andExpect(status().isOk());
		Account victim = newAccount("sr-victim", Role.PARTICIPANT);
		for (String path : new String[] { "/api/operator/accounts", "/api/operator/usage", "/api/operator/clubs", "/api/operator/roles",
				"/api/operator/actions" }) {
			mockMvc.perform(get(path).session(memberSession)).andExpect(status().isForbidden());
		}
		mockMvc.perform(withXsrf(post("/api/operator/accounts/{id}/withdraw", victim.getId()), memberSession)
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"개발자의 강제탈퇴\"}")).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(post("/api/operator/accounts/{id}/suspend", victim.getId()), memberSession)
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"개발자의 정지\"}")).andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("select deleted_at is null and suspended_at is null from account where id = ?", Boolean.class,
				victim.getId())).isTrue();

		// 모임 내부(비공개 콘텐츠)도 회원이 아니면 403
		String created = mockMvc.perform(withXsrf(post("/api/clubs"), operatorSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"비공개 확인\",\"intro\":\"소개\",\"place\":\"온라인\",\"mode\":\"ONLINE\"}"))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		long club = ((Number) JsonPath.read(created, "$.id")).longValue();
		mockMvc.perform(withXsrf(post("/api/operator/clubs/{id}/approve", club), operatorSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + JsonPath.read(created, "$.version") + "}")).andExpect(status().isOk());
		mockMvc.perform(get("/api/clubs/{id}/boards/board/posts", club).session(memberSession)).andExpect(status().isForbidden());
	}

	@Test
	void demoVisitorsHaveNoRolesAndCannotUseRoleManagement() throws Exception {
		MockHttpSession demo = (MockHttpSession) mockMvc.perform(withXsrf(post("/api/demo/start"), null).with(r -> {
			r.setRemoteAddr("100.68.9." + (int) (Math.random() * 200 + 1));
			return r;
		})).andExpect(status().isCreated()).andReturn().getRequest().getSession(false);
		mockMvc.perform(get("/api/auth/me").session(demo)).andExpect(jsonPath("$.roles.length()").value(0));
		mockMvc.perform(get("/api/operator/roles").session(demo)).andExpect(status().isForbidden());
	}

	private ResultActions grant(MockHttpSession session, Account target, String role, String reason) throws Exception {
		return mockMvc.perform(withXsrf(put("/api/operator/roles/{id}/{role}", target.getId(), role), session)
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"%s\"}".formatted(reason)));
	}

	private ResultActions revoke(MockHttpSession session, Account target, String role, String reason) throws Exception {
		return mockMvc.perform(withXsrf(delete("/api/operator/roles/{id}/{role}", target.getId(), role), session)
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"%s\"}".formatted(reason)));
	}

}

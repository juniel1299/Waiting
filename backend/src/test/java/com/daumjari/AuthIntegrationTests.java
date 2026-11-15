package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;

import jakarta.servlet.http.Cookie;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuthIntegrationTests {

	static final String PASSWORD = "test-pass-1!";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AccountRepository accounts;

	@Autowired
	PasswordEncoder passwordEncoder;

	@BeforeEach
	void ensureAccounts() {
		ensureAccount("auth-operator", Role.OPERATOR);
		ensureAccount("auth-participant", Role.PARTICIPANT);
	}

	@Test
	void loginWithCsrfCookieFlowCreatesSession() throws Exception {
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
			.andReturn();
		Cookie xsrf = csrfResult.getResponse().getCookie("XSRF-TOKEN");
		String token = xsrf.getValue();

		MvcResult login = mockMvc.perform(post("/api/auth/login")
				.cookie(xsrf)
				.header("X-XSRF-TOKEN", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginJson("auth-participant", PASSWORD)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.loginId").value("auth-participant"))
			.andExpect(jsonPath("$.role").value("PARTICIPANT"))
			.andReturn();

		mockMvc.perform(get("/api/auth/me").session(sessionOf(login)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.loginId").value("auth-participant"));
	}

	@Test
	void csrfTokenIsRotatedOnLogin() throws Exception {
		Cookie beforeLogin = issueXsrf(null);
		MvcResult result = mockMvc.perform(post("/api/auth/login")
				.cookie(beforeLogin)
				.header("X-XSRF-TOKEN", beforeLogin.getValue())
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginJson("auth-participant", PASSWORD)))
			.andExpect(status().isOk())
			.andReturn();

		Cookie cleared = result.getResponse().getCookie("XSRF-TOKEN");
		assertThat(cleared).isNotNull();
		assertThat(cleared.getMaxAge()).isZero();
		assertThat(issueXsrf(sessionOf(result)).getValue()).isNotEqualTo(beforeLogin.getValue());
	}

	@Test
	void loginWithoutCsrfIsRejected() throws Exception {
		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginJson("auth-participant", PASSWORD)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CSRF_INVALID"));
	}

	@Test
	void wrongPasswordIsRejectedWithoutSession() throws Exception {
		MvcResult result = mockMvc.perform(withXsrf(post("/api/auth/login"), null)
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginJson("auth-participant", "wrong-password")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
			.andReturn();

		MockHttpSession session = sessionOf(result);
		if (session != null) {
			mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
		}
	}

	@Test
	void unknownAccountGetsSameErrorAsWrongPassword() throws Exception {
		mockMvc.perform(withXsrf(post("/api/auth/login"), null)
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginJson("nobody", PASSWORD)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void blankLoginFieldsAreRejected() throws Exception {
		mockMvc.perform(withXsrf(post("/api/auth/login"), null)
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginJson("", "")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void meWithoutLoginIsUnauthenticated() throws Exception {
		mockMvc.perform(get("/api/auth/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void logoutEndsSession() throws Exception {
		MockHttpSession session = login("auth-participant");

		mockMvc.perform(withXsrf(post("/api/auth/logout"), session))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void roleInRequestBodyIsIgnored() throws Exception {
		MvcResult result = mockMvc.perform(withXsrf(post("/api/auth/login"), null)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"loginId":"auth-participant","password":"%s","role":"OPERATOR","id":1}
						""".formatted(PASSWORD)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("PARTICIPANT"))
			.andReturn();

		mockMvc.perform(get("/api/operator/sessions").session(sessionOf(result)))
			.andExpect(status().isForbidden());
	}

	@Test
	void participantIsForbiddenFromOperatorApi() throws Exception {
		MockHttpSession session = login("auth-participant");

		mockMvc.perform(get("/api/operator/sessions").session(session))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mockMvc.perform(withXsrf(post("/api/operator/sessions"), session)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void unauthenticatedOperatorApiIsRejected() throws Exception {
		mockMvc.perform(get("/api/operator/sessions"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void operatorPassesOperatorAuthorization() throws Exception {
		MockHttpSession session = login("auth-operator");

		// 인가는 통과하고 없는 경로라 404 (실제 운영자 API 성공은 SessionApiTests 에서 검증)
		mockMvc.perform(get("/api/operator/does-not-exist").session(session)).andExpect(status().isNotFound());
	}

	private MockHttpServletRequestBuilder withXsrf(MockHttpServletRequestBuilder request, MockHttpSession session)
			throws Exception {
		Cookie xsrf = issueXsrf(session);
		request.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
		return session == null ? request : request.session(session);
	}

	private Cookie issueXsrf(MockHttpSession session) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/auth/csrf");
		if (session != null) {
			request.session(session);
		}
		return mockMvc.perform(request).andReturn().getResponse().getCookie("XSRF-TOKEN");
	}

	private MockHttpSession login(String loginId) throws Exception {
		MvcResult result = mockMvc.perform(withXsrf(post("/api/auth/login"), null)
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginJson(loginId, PASSWORD)))
			.andExpect(status().isOk())
			.andReturn();
		return sessionOf(result);
	}

	private static MockHttpSession sessionOf(MvcResult result) {
		return (MockHttpSession) result.getRequest().getSession(false);
	}

	private static String loginJson(String loginId, String password) {
		return """
				{"loginId":"%s","password":"%s"}
				""".formatted(loginId, password);
	}

	private void ensureAccount(String loginId, Role role) {
		if (accounts.findByLoginId(loginId).isEmpty()) {
			accounts.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), loginId, role, Instant.now()));
		}
	}

}

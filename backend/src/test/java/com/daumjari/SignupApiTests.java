package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;

/** 회원가입: 서버가 역할을 정하고, 아이디 규칙·중복·비밀번호 확인을 검증하며, IP 당 시도를 제한한다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SignupApiTests extends ApiTestSupport {

	private final String ip = randomIp();

	@Test
	void signupCreatesAParticipantWhoCanLogIn() throws Exception {
		String loginId = uniqueId();
		signup(ip, loginId, PASSWORD, PASSWORD).andExpect(status().isCreated())
			.andExpect(jsonPath("$.loginId").value(loginId))
			.andExpect(jsonPath("$.role").value("PARTICIPANT"));

		Account saved = accounts.findByLoginId(loginId).orElseThrow();
		assertThat(saved.getRole()).isEqualTo(Role.PARTICIPANT);
		assertThat(saved.getPasswordHash()).startsWith("{bcrypt}").doesNotContain(PASSWORD);
		MockHttpSession session = login(saved);
		mockMvc.perform(get("/api/auth/me").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.loginId").value(loginId))
			.andExpect(jsonPath("$.role").value("PARTICIPANT"));
	}

	@Test
	void roleInTheRequestIsIgnored() throws Exception {
		String loginId = uniqueId();
		mockMvc.perform(withXsrf(post("/api/auth/signup"), null).with(from(ip))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"loginId":"%s","password":"%s","passwordConfirm":"%s","role":"OPERATOR"}
					""".formatted(loginId, PASSWORD, PASSWORD)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.role").value("PARTICIPANT"));
		assertThat(accounts.findByLoginId(loginId).orElseThrow().getRole()).isEqualTo(Role.PARTICIPANT);
	}

	@Test
	void loginIdIsNormalizedToLowerCase() throws Exception {
		String loginId = uniqueId();
		signup(ip, "  " + loginId.toUpperCase() + " ", PASSWORD, PASSWORD).andExpect(status().isCreated())
			.andExpect(jsonPath("$.loginId").value(loginId));
	}

	@Test
	void duplicateLoginIdIsRejectedCaseInsensitively() throws Exception {
		String loginId = uniqueId();
		signup(ip, loginId, PASSWORD, PASSWORD).andExpect(status().isCreated());
		signup(ip, loginId.toUpperCase(), PASSWORD, PASSWORD).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LOGIN_ID_TAKEN"));
	}

	@Test
	void invalidInputIsReportedPerField() throws Exception {
		signup(ip, "ab", "short", "different").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.fields.loginId").exists())
			.andExpect(jsonPath("$.fields.password").exists())
			.andExpect(jsonPath("$.fields.passwordConfirm").exists());
		signup(ip, "bad-id!", PASSWORD, PASSWORD).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.loginId").exists());
		signup(ip, uniqueId(), PASSWORD, PASSWORD + "x").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.passwordConfirm").exists());
	}

	@Test
	void missingFieldsAreRejected() throws Exception {
		mockMvc.perform(withXsrf(post("/api/auth/signup"), null).with(from(ip))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void signupAttemptsAreLimitedPerIp() throws Exception {
		for (int i = 0; i < 5; i++) {
			signup(ip, "x", PASSWORD, PASSWORD).andExpect(status().isBadRequest());
		}
		signup(ip, uniqueId(), PASSWORD, PASSWORD).andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
		// 다른 IP 는 영향 없다
		signup(randomIp(), uniqueId(), PASSWORD, PASSWORD).andExpect(status().isCreated());
	}

	@Test
	void signupRequiresCsrf() throws Exception {
		mockMvc.perform(post("/api/auth/signup").with(from(ip))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"loginId":"%s","password":"%s","passwordConfirm":"%s"}
					""".formatted(uniqueId(), PASSWORD, PASSWORD)))
			.andExpect(status().isForbidden());
	}

	private ResultActions signup(String fromIp, String loginId, String password, String confirm) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/auth/signup"), null).with(from(fromIp))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"loginId":"%s","password":"%s","passwordConfirm":"%s"}
					""".formatted(loginId, password, confirm)));
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String fromIp) {
		return request -> {
			request.setRemoteAddr(fromIp);
			return request;
		};
	}

	static String uniqueId() {
		return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	static String randomIp() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
	}

}

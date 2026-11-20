package com.daumjari;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;

import jakarta.servlet.http.Cookie;

/**
 * 공개 주소의 백엔드는 프런트 프록시(공유 비밀 토큰)만 쓸 수 있다. 클라이언트 IP 는 토큰이 맞을 때만 X-Daumjari-Client-IP.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "daumjari.proxy.shared-secret=test-proxy-secret-0123456789")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ProxyTokenTests extends ApiTestSupport {

	static final String SECRET = "test-proxy-secret-0123456789";

	static RequestPostProcessor viaProxy(String clientIp) {
		return (MockHttpServletRequest request) -> {
			request.addHeader("X-Daumjari-Proxy-Token", SECRET);
			if (clientIp != null) {
				request.addHeader("X-Daumjari-Client-IP", clientIp);
			}
			return request;
		};
	}

	@Test
	void directCallsWithoutTheTokenAreForbidden() throws Exception {
		mockMvc.perform(get("/api/auth/csrf"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PROXY_REQUIRED"));
		mockMvc.perform(post("/api/demo/start")).andExpect(status().isForbidden());
	}

	@Test
	void wrongTokenIsForbidden() throws Exception {
		mockMvc.perform(get("/api/auth/csrf").header("X-Daumjari-Proxy-Token", "wrong"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PROXY_REQUIRED"));
	}

	@Test
	void healthIsOpenForThePlatformHealthCheck() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void clientIpComesFromTheProxyHeaderOnlyWithTheToken() throws Exception {
		Account member = newAccount("pt", Role.PARTICIPANT);
		String ipA = "198.51.100." + ThreadLocalRandom.current().nextInt(1, 255);
		for (int i = 0; i < 5; i++) {
			attempt(member, "wrong", ipA).andExpect(status().isUnauthorized());
		}
		attempt(member, "wrong", ipA).andExpect(status().isTooManyRequests());

		// 다른 방문자(프록시가 알려준 다른 IP)는 같은 아이디라도 막히지 않는다
		attempt(member, PASSWORD, "203.0.113." + ThreadLocalRandom.current().nextInt(1, 255))
			.andExpect(status().isOk());
	}

	@Test
	void forgedForwardedForIsIgnoredBehindTheProxy() throws Exception {
		Account member = newAccount("pt-xff", Role.PARTICIPANT);
		String ipA = "192.0.2." + ThreadLocalRandom.current().nextInt(1, 255);
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(login(member, "wrong", ipA).header("X-Forwarded-For", "10.9.0." + i))
				.andExpect(status().isUnauthorized());
		}
		mockMvc.perform(login(member, "wrong", ipA).header("X-Forwarded-For", "10.9.0.99"))
			.andExpect(status().isTooManyRequests());
	}

	private ResultActions attempt(Account member, String password, String clientIp) throws Exception {
		return mockMvc.perform(login(member, password, clientIp));
	}

	private MockHttpServletRequestBuilder login(Account member, String password, String clientIp) throws Exception {
		Cookie xsrf = mockMvc.perform(get("/api/auth/csrf").with(viaProxy(null)))
			.andReturn()
			.getResponse()
			.getCookie("XSRF-TOKEN");
		return post("/api/auth/login").with(viaProxy(clientIp))
			.cookie(xsrf)
			.header("X-XSRF-TOKEN", xsrf.getValue())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"loginId\":\"%s\",\"password\":\"%s\"}".formatted(member.getLoginId(), password));
	}

}

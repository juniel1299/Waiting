package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.FixedClockConfiguration.TestClock;
import com.daumjari.account.Account;
import com.daumjari.account.Role;

/**
 * 로그인 실패 제한 (기본 설정: 계정+IP 5회/15분, IP 50회/15분). 고정 시계로 창 경계를 검증한다.
 * 테스트마다 다른 클라이언트 IP 를 써서 서로 영향이 없게 한다.
 */
@Import({ TestcontainersConfiguration.class, FixedClockConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class LoginThrottleApiTests extends ApiTestSupport {

	static final Duration WINDOW = Duration.ofMinutes(15);

	static final int MAX_PER_ACCOUNT_IP = 5;

	static final int MAX_PER_IP = 50;

	/** 테스트마다 새 시각에서 시작해 이전 테스트의 창과 겹치지 않게 한다. */
	static Instant now = Instant.parse("2031-01-01T00:00:00Z");

	@Autowired
	TestClock clock;

	Account member;

	String ip;

	@BeforeEach
	void setUp() {
		now = now.plus(Duration.ofDays(1));
		clock.setInstant(now);
		member = newAccount("member", Role.PARTICIPANT);
		ip = randomIp("198.51.100.");
	}

	@Test
	void failuresBeforeTheLimitKeepTheNormalResponseThenReturn429WithRetryAfter() throws Exception {
		for (int i = 0; i < MAX_PER_ACCOUNT_IP; i++) {
			attempt(member.getLoginId(), "wrong", ip).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		}

		attempt(member.getLoginId(), "wrong", ip).andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", String.valueOf(WINDOW.toSeconds())))
			.andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
			.andExpect(jsonPath("$.retryAfterSeconds").value(WINDOW.toSeconds()));
	}

	@Test
	void unknownAccountFollowsExactlyTheSameRule() throws Exception {
		String ghost = "ghost-" + UUID.randomUUID().toString().substring(0, 8);
		for (int i = 0; i < MAX_PER_ACCOUNT_IP; i++) {
			String known = body(attempt(member.getLoginId(), "wrong", ip).andExpect(status().isUnauthorized()));
			String unknown = body(attempt(ghost, "wrong", ip).andExpect(status().isUnauthorized()));
			assertThat(unknown).isEqualTo(known);
		}

		String known = body(attempt(member.getLoginId(), "wrong", ip).andExpect(status().isTooManyRequests()));
		String unknown = body(attempt(ghost, "wrong", ip).andExpect(status().isTooManyRequests()));
		assertThat(unknown).isEqualTo(known);
	}

	@Test
	void whileLimitedEvenTheCorrectPasswordIsRejectedWithoutASession() throws Exception {
		lockOut(member, ip);

		MvcResult result = attempt(member.getLoginId(), PASSWORD, ip).andExpect(status().isTooManyRequests())
			.andReturn();
		MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
		if (session != null) {
			assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
		}
	}

	@Test
	void afterTheWindowPassesLoginWorksAgain() throws Exception {
		lockOut(member, ip);

		clock.setInstant(now.plus(WINDOW).minusSeconds(1));
		attempt(member.getLoginId(), PASSWORD, ip).andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", "1"));

		clock.setInstant(now.plus(WINDOW));
		attempt(member.getLoginId(), PASSWORD, ip).andExpect(status().isOk())
			.andExpect(jsonPath("$.loginId").value(member.getLoginId()));
	}

	@Test
	void successfulLoginResetsTheAccountCounter() throws Exception {
		for (int i = 0; i < MAX_PER_ACCOUNT_IP - 1; i++) {
			attempt(member.getLoginId(), "wrong", ip).andExpect(status().isUnauthorized());
		}
		attempt(member.getLoginId(), PASSWORD, ip).andExpect(status().isOk());

		for (int i = 0; i < MAX_PER_ACCOUNT_IP; i++) {
			attempt(member.getLoginId(), "wrong", ip).andExpect(status().isUnauthorized());
		}
		attempt(member.getLoginId(), "wrong", ip).andExpect(status().isTooManyRequests());
	}

	@Test
	void anotherClientIpIsNotLockedOutOfTheSameAccount() throws Exception {
		lockOut(member, ip);

		attempt(member.getLoginId(), PASSWORD, randomIp("203.0.113.")).andExpect(status().isOk());
	}

	@Test
	void oneIpSprayingManyAccountsHitsTheIpLimit() throws Exception {
		String sprayIp = randomIp("192.0.2.");
		for (int i = 0; i < MAX_PER_IP; i++) {
			attempt("spray-" + i + "-" + UUID.randomUUID().toString().substring(0, 4), "wrong", sprayIp)
				.andExpect(status().isUnauthorized());
		}

		attempt(member.getLoginId(), PASSWORD, sprayIp).andExpect(status().isTooManyRequests());
		attempt(member.getLoginId(), PASSWORD, ip).andExpect(status().isOk());
	}

	// --- helpers ---

	private void lockOut(Account account, String fromIp) throws Exception {
		for (int i = 0; i < MAX_PER_ACCOUNT_IP; i++) {
			attempt(account.getLoginId(), "wrong", fromIp).andExpect(status().isUnauthorized());
		}
		attempt(account.getLoginId(), "wrong", fromIp).andExpect(status().isTooManyRequests());
	}

	private ResultActions attempt(String loginId, String password, String fromIp) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/auth/login"), null).with(request -> {
			request.setRemoteAddr(fromIp);
			return request;
		}).contentType(MediaType.APPLICATION_JSON).content("""
				{"loginId":"%s","password":"%s"}
				""".formatted(loginId, password)));
	}

	private static String randomIp(String prefix) {
		return prefix + ThreadLocalRandom.current().nextInt(1, 255);
	}

	private static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

}

package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;

/**
 * 실제 Tomcat 에서 X-Forwarded-For 를 어떤 경우에 믿는지.
 * 테스트 클라이언트는 localhost 에서 접속하므로, 신뢰 프록시 목록에 loopback 이 있으면 "프록시(Next)를 거친 요청" 이 된다.
 */
class ForwardedHeaderTrustTests {

	static final String PASSWORD = "forwarded-pass-1!";

	static final int MAX_PER_ACCOUNT_IP = 5;

	@Import(TestcontainersConfiguration.class)
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
	@Testcontainers(disabledWithoutDocker = true)
	abstract static class Base {

		@Value("${local.server.port}")
		int port;

		@Autowired
		AccountRepository accounts;

		@Autowired
		PasswordEncoder passwordEncoder;

		String base() {
			return "http://localhost:" + port;
		}

		Account newAccount() {
			String loginId = "fwd-" + UUID.randomUUID().toString().substring(0, 8);
			return accounts
				.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), "fwd", Role.PARTICIPANT, Instant.now()));
		}

		int attempt(Account account, String password, String forwardedFor) throws Exception {
			return HttpTestClient.attemptLogin(base(), account.getLoginId(), password, forwardedFor).statusCode();
		}

	}

	/** 기본 설정: loopback(같은 기기의 Next)만 신뢰 프록시. 프록시가 넘긴 클라이언트 IP 별로 따로 센다. */
	@Nested
	class FromTrustedProxy extends Base {

		@Test
		void clientsBehindTheTrustedProxyAreCountedSeparately() throws Exception {
			Account account = newAccount();
			for (int i = 0; i < MAX_PER_ACCOUNT_IP; i++) {
				assertThat(attempt(account, "wrong", "203.0.113.10")).isEqualTo(401);
			}
			assertThat(attempt(account, "wrong", "203.0.113.10")).isEqualTo(429);

			assertThat(attempt(account, PASSWORD, "203.0.113.20")).isEqualTo(200);
		}

		@Test
		void onlyTheRightMostHopAddedByTheProxyIsUsed() throws Exception {
			Account account = newAccount();
			// 클라이언트가 앞쪽 값을 바꿔 끼워도 프록시가 붙인 맨 오른쪽 값(203.0.113.30)으로 센다
			for (int i = 0; i < MAX_PER_ACCOUNT_IP; i++) {
				assertThat(attempt(account, "wrong", "10.66.0." + i + ", 203.0.113.30")).isEqualTo(401);
			}
			assertThat(attempt(account, "wrong", "10.66.0.99, 203.0.113.30")).isEqualTo(429);
		}

	}

	/** 접속한 쪽이 신뢰 프록시가 아니면 X-Forwarded-For 를 무시하고 실제 접속 주소로 센다. */
	@Nested
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
			properties = "server.tomcat.remoteip.internal-proxies=10\\.9\\.9\\.9")
	class FromUntrustedPeer extends Base {

		@Test
		void forgedForwardedForCannotRotateTheThrottleKey() throws Exception {
			Account account = newAccount();
			for (int i = 0; i < MAX_PER_ACCOUNT_IP; i++) {
				assertThat(attempt(account, "wrong", "198.18.0." + i)).isEqualTo(401);
			}
			assertThat(attempt(account, "wrong", "198.18.0.200")).isEqualTo(429);
			assertThat(attempt(account, PASSWORD, "198.18.0.201")).isEqualTo(429);
		}

	}

}

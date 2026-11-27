package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;

/** 두 회원이 같은 카카오 계정을 동시에 연결하면 한 명만 연결된다 (DB 유일, 실제 PostgreSQL, 20회). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class SocialLinkConcurrencyTests {

	static final String PASSWORD = "social-race-1!";

	@DynamicPropertySource
	static void oauth(DynamicPropertyRegistry registry) throws IOException {
		FakeOAuthServer.register(registry);
	}

	@Value("${local.server.port}")
	int port;

	@Autowired
	AccountRepository accounts;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbc;

	@RepeatedTest(20)
	void twoMembersLinkingTheSameSocialAccountAtOnceLeaveItWithOne() throws Exception {
		HttpTestClient a = client("sr-a");
		HttpTestClient b = client("sr-b");
		String code = "u" + (System.nanoTime() % 1_000_000_000L);
		String stateA = state(a.send("GET", "/api/auth/oauth/kakao/link/start", null));
		String stateB = state(b.send("GET", "/api/auth/oauth/kakao/link/start", null));

		List<HttpResponse<String>> r = HttpTestClient.race(
				() -> a.send("GET", "/api/auth/oauth/kakao/callback?code=" + code + "&state=" + stateA, null),
				() -> b.send("GET", "/api/auth/oauth/kakao/callback?code=" + code + "&state=" + stateB, null));

		List<String> locations = r.stream().map(x -> x.headers().firstValue("Location").orElse("")).sorted().toList();
		assertThat(locations).containsExactly("/me/accounts?error=SOCIAL_ALREADY_LINKED", "/me/accounts?linked=kakao");
		assertThat(jdbc.queryForObject("select count(*) from social_account where provider = 'KAKAO' and provider_user_id = ?",
				Long.class, code.replaceAll("\\D", ""))).isEqualTo(1);
	}

	private static String state(HttpResponse<String> start) {
		assertThat(start.statusCode()).isEqualTo(302);
		return UriComponentsBuilder.fromUriString(start.headers().firstValue("Location").orElseThrow()).build().getQueryParams()
			.getFirst("state");
	}

	private HttpTestClient client(String prefix) throws Exception {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		accounts.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), prefix, Role.PARTICIPANT, Instant.now()));
		return HttpTestClient.login("http://localhost:" + port, loginId, PASSWORD);
	}

}

package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;
import com.daumjari.studysession.StudySession;
import com.daumjari.studysession.StudySessionRepository;

/**
 * 확장 2단계 경합 (실제 PostgreSQL, 각 20회).
 * 1) 두 회원이 같은 닉네임을 동시에 저장하면 하나만 200, 나머지는 409 이고 그 닉네임은 한 명에게만 있다 (DB 유일 인덱스).
 * 2) 같은 계정의 즐겨찾기 두 번이 겹쳐도 행은 하나.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class NicknameFavoriteConcurrencyTests {

	static final String PASSWORD = "nick-race-1!";

	@Value("${local.server.port}")
	int port;

	@Autowired
	AccountRepository accounts;

	@Autowired
	StudySessionRepository sessions;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbc;

	@RepeatedTest(20)
	void twoMembersTakingTheSameNicknameAtOnceLeaveItWithOne() throws Exception {
		HttpTestClient a = client("nr-a");
		HttpTestClient b = client("nr-b");
		String name = "경합" + UUID.randomUUID().toString().replaceAll("[^0-9]", "").substring(0, 4);

		List<HttpResponse<String>> r = HttpTestClient.race(() -> a.send("PATCH", "/api/me/profile", "{\"nickname\":\"" + name + "\"}"),
				() -> b.send("PATCH", "/api/me/profile", "{\"nickname\":\"" + name + "\"}"));

		assertThat(r.stream().map(HttpResponse::statusCode).sorted().toList()).isEqualTo(List.of(200, 409));
		assertThat(jdbc.queryForObject("select count(*) from account where lower(nickname) = lower(?)", Long.class, name)).isEqualTo(1);
	}

	@RepeatedTest(20)
	void doubleFavoriteFromOneAccountLeavesOneRow() throws Exception {
		HttpTestClient a = client("nf-a");
		StudySession s = sessions.save(StudySession.create("경합 회차", Instant.now().plusSeconds(86400 * 3), "온라인", 3, Instant.now()));

		List<HttpResponse<String>> r = HttpTestClient.race(() -> a.send("PUT", "/api/sessions/" + s.getId() + "/favorite", null),
				() -> a.send("PUT", "/api/sessions/" + s.getId() + "/favorite", null));

		assertThat(r).allSatisfy(x -> assertThat(x.statusCode()).as(x.body()).isEqualTo(200));
		assertThat(jdbc.queryForObject("select count(*) from favorite where session_id = ?", Long.class, s.getId())).isEqualTo(1);
	}

	private HttpTestClient client(String prefix) throws Exception {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		accounts.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), prefix, Role.PARTICIPANT, Instant.now()));
		return HttpTestClient.login("http://localhost:" + port, loginId, PASSWORD);
	}

}

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

/**
 * 운영자 둘이 서로의 운영자 역할을 동시에 회수해도(각 20회) 하나만 성공하고 둘 중 한 명은 운영자로 남는다.
 * 뒤 요청은 활성 운영자 잠금을 기다렸다가 처리자가 더는 운영자가 아니라 403 이 된다 (실제 PostgreSQL).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ServiceRoleConcurrencyTests {

	static final String PASSWORD = "role-race-1!";

	@Value("${local.server.port}")
	int port;

	@Autowired
	AccountRepository accounts;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbc;

	@RepeatedTest(20)
	void twoOperatorsRevokingEachOtherLeaveOneOperator() throws Exception {
		Account a = operator("rr-a");
		Account b = operator("rr-b");
		HttpTestClient ca = HttpTestClient.login("http://localhost:" + port, a.getLoginId(), PASSWORD);
		HttpTestClient cb = HttpTestClient.login("http://localhost:" + port, b.getLoginId(), PASSWORD);

		List<HttpResponse<String>> r = HttpTestClient.race(
				() -> ca.send("DELETE", "/api/operator/roles/" + b.getId() + "/OPERATOR", "{\"reason\":\"동시 회수\"}"),
				() -> cb.send("DELETE", "/api/operator/roles/" + a.getId() + "/OPERATOR", "{\"reason\":\"동시 회수\"}"));

		assertThat(r.stream().map(HttpResponse::statusCode).sorted().toList()).isEqualTo(List.of(200, 403));
		assertThat(jdbc.queryForObject("select count(*) from account where id in (?, ?) and role = 'OPERATOR'", Long.class, a.getId(),
				b.getId())).isEqualTo(1);
	}

	private Account operator(String prefix) {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		return accounts.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), prefix, Role.OPERATOR, Instant.now()));
	}

}

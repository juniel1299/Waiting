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
import com.jayway.jsonpath.JsonPath;

/**
 * 모임 경합 (실제 포트·PostgreSQL, 동시 출발, 각 20회).
 * 1) 운영자 승인과 신청자 수정이 겹치면: 수정이 먼저면 승인은 409(본 버전이 낡음), 승인이 먼저면 수정은 승인된 모임에 즉시 반영.
 * 2) 두 운영자의 승인이 겹쳐도 승인·알림·생성자 회원 행은 하나.
 * 3) 같은 계정의 가입 두 번이 겹쳐도 회원 행은 하나 (PK).
 * 4) 모임 삭제와 가입이 겹쳐도 지워진 모임의 회원 행이 남지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ClubConcurrencyTests {

	static final int REPEAT = 20;

	static final String PASSWORD = "club-race-1!";

	@Value("${local.server.port}")
	int port;

	@Autowired
	AccountRepository accounts;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbc;

	@RepeatedTest(REPEAT)
	void approvalRacingAnEditNeverApprovesUnseenContent() throws Exception {
		HttpTestClient creator = client("cr-c", Role.PARTICIPANT);
		HttpTestClient operator = client("cr-o", Role.OPERATOR);
		long club = apply(creator);
		long seen = version(club);

		List<HttpResponse<String>> r = HttpTestClient.race(
				() -> operator.send("POST", "/api/operator/clubs/" + club + "/approve", "{\"version\":" + seen + "}"),
				() -> creator.send("PATCH", "/api/clubs/" + club,
						"{\"name\":\"고친 이름\",\"intro\":\"고친 소개\",\"place\":\"온라인\",\"mode\":\"ONLINE\"}"));

		assertThat(r.get(1).statusCode()).as(r.get(1).body()).isEqualTo(200);
		String status = jdbc.queryForObject("select status from club where id = ?", String.class, club);
		if (r.get(0).statusCode() == 200) {
			assertThat(status).isEqualTo("APPROVED");
		}
		else {
			assertThat(r.get(0).statusCode()).as(r.get(0).body()).isEqualTo(409);
			assertThat(status).isEqualTo("PENDING");
		}
		assertThat(jdbc.queryForObject("select name from club where id = ?", String.class, club)).isEqualTo("고친 이름");
	}

	@RepeatedTest(REPEAT)
	void doubleApprovalLeavesOneDecisionOneNotificationOneManager() throws Exception {
		HttpTestClient creator = client("cd-c", Role.PARTICIPANT);
		HttpTestClient op1 = client("cd-o1", Role.OPERATOR);
		HttpTestClient op2 = client("cd-o2", Role.OPERATOR);
		long club = apply(creator);
		String body = "{\"version\":" + version(club) + "}";

		List<HttpResponse<String>> r = HttpTestClient.race(
				() -> op1.send("POST", "/api/operator/clubs/" + club + "/approve", body),
				() -> op2.send("POST", "/api/operator/clubs/" + club + "/approve", body));

		assertThat(r.stream().map(HttpResponse::statusCode).sorted().toList()).isEqualTo(List.of(200, 409));
		assertThat(jdbc.queryForObject("select count(*) from notification where club_id = ?", Long.class, club)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from club_member where club_id = ?", Long.class, club)).isEqualTo(1);
	}

	@RepeatedTest(REPEAT)
	void doubleJoinFromOneAccountLeavesOneRow() throws Exception {
		long club = approvedClub("cj");
		HttpTestClient joiner = client("cj-j", Role.PARTICIPANT);

		List<HttpResponse<String>> r = HttpTestClient.race(() -> joiner.send("POST", "/api/clubs/" + club + "/join", null),
				() -> joiner.send("POST", "/api/clubs/" + club + "/join", null));

		assertThat(r).allSatisfy(x -> assertThat(x.statusCode()).as(x.body()).isEqualTo(200));
		assertThat(jdbc.queryForObject("select count(*) from club_member where club_id = ?", Long.class, club)).isEqualTo(2);
	}

	@RepeatedTest(REPEAT)
	void deletingAClubWhileJoiningLeavesNoMembers() throws Exception {
		HttpTestClient creator = client("cx-c", Role.PARTICIPANT);
		HttpTestClient operator = client("cx-o", Role.OPERATOR);
		long club = apply(creator);
		assertThat(operator.send("POST", "/api/operator/clubs/" + club + "/approve", "{\"version\":" + version(club) + "}")
			.statusCode()).isEqualTo(200);
		HttpTestClient joiner = client("cx-j", Role.PARTICIPANT);

		List<HttpResponse<String>> r = HttpTestClient.race(() -> creator.send("DELETE", "/api/clubs/" + club, null),
				() -> joiner.send("POST", "/api/clubs/" + club + "/join", null));

		assertThat(r.get(0).statusCode()).as(r.get(0).body()).isEqualTo(204);
		assertThat(r.get(1).statusCode()).as(r.get(1).body()).isIn(200, 404);
		assertThat(jdbc.queryForObject("select count(*) from club_member where club_id = ?", Long.class, club)).isZero();
	}

	private long approvedClub(String prefix) throws Exception {
		HttpTestClient creator = client(prefix + "-c", Role.PARTICIPANT);
		HttpTestClient operator = client(prefix + "-o", Role.OPERATOR);
		long club = apply(creator);
		assertThat(operator.send("POST", "/api/operator/clubs/" + club + "/approve", "{\"version\":" + version(club) + "}")
			.statusCode()).isEqualTo(200);
		return club;
	}

	private long apply(HttpTestClient creator) throws Exception {
		HttpResponse<String> r = creator.send("POST", "/api/clubs",
				"{\"name\":\"경합 모임\",\"intro\":\"소개\",\"place\":\"강남\",\"mode\":\"OFFLINE\"}");
		assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
		return ((Number) JsonPath.read(r.body(), "$.id")).longValue();
	}

	private long version(long club) {
		return jdbc.queryForObject("select version from club where id = ?", Long.class, club);
	}

	private HttpTestClient client(String prefix, Role role) throws Exception {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		accounts.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), prefix, role, Instant.now()));
		return HttpTestClient.login("http://localhost:" + port, loginId, PASSWORD);
	}

}

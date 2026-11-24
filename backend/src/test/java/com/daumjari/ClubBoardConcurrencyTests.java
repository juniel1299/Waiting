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
 * 모임 역할·내부 게시판 경합 (실제 포트·PostgreSQL, 동시 출발, 각 20회).
 * 1) 운영자 권한 회수와 그 운영자의 남의 글 삭제가 겹치면: 삭제가 먼저면 204(글 없음), 회수가 먼저면 403(글 남음). 그 밖은 없다.
 * 2) 모임 삭제와 내부 글 작성이 겹쳐도 지워진 모임의 글이 남지 않는다.
 * 3) 생성자 이전 두 건이 겹치면 하나만 성공하고 생성자는 한 명.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ClubBoardConcurrencyTests {

	static final int REPEAT = 20;

	static final String PASSWORD = "club-board-race-1!";

	@Value("${local.server.port}")
	int port;

	@Autowired
	AccountRepository accounts;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbc;

	record Member(long id, HttpTestClient client) {
	}

	@RepeatedTest(REPEAT)
	void revokingAManagerWhileItDeletesIsAllOrNothing() throws Exception {
		Member creator = member("rv-c", Role.PARTICIPANT);
		long club = approvedClub(creator);
		Member manager = joined(club, "rv-m");
		Member writer = joined(club, "rv-w");
		assertThat(creator.client().send("PUT", "/api/clubs/" + club + "/managers/" + manager.id(), null).statusCode())
			.isEqualTo(200);
		long post = postIn(club, writer, "board");

		List<HttpResponse<String>> r = HttpTestClient.race(
				() -> creator.client().send("DELETE", "/api/clubs/" + club + "/managers/" + manager.id(), null),
				() -> manager.client().send("DELETE", "/api/posts/" + post, null));

		assertThat(r.get(0).statusCode()).as(r.get(0).body()).isEqualTo(200);
		long left = jdbc.queryForObject("select count(*) from board_post where id = ?", Long.class, post);
		if (r.get(1).statusCode() == 204) {
			assertThat(left).isZero();
		}
		else {
			assertThat(r.get(1).statusCode()).as(r.get(1).body()).isEqualTo(403);
			assertThat(left).isEqualTo(1);
		}
		assertThat(jdbc.queryForObject("select role from club_member where club_id = ? and account_id = ?", String.class, club,
				manager.id())).isEqualTo("MEMBER");
	}

	@RepeatedTest(REPEAT)
	void deletingAClubWhileWritingInsideLeavesNoPosts() throws Exception {
		Member creator = member("dw-c", Role.PARTICIPANT);
		long club = approvedClub(creator);
		Member writer = joined(club, "dw-w");

		List<HttpResponse<String>> r = HttpTestClient.race(() -> creator.client().send("DELETE", "/api/clubs/" + club, null),
				() -> writer.client().send("POST", "/api/clubs/" + club + "/boards/board/posts",
						"{\"title\":\"경합 글\",\"body\":\"본문\"}"));

		assertThat(r.get(0).statusCode()).as(r.get(0).body()).isEqualTo(204);
		assertThat(r.get(1).statusCode()).as(r.get(1).body()).isIn(201, 404);
		assertThat(jdbc.queryForObject("select count(*) from board_post where club_id = ?", Long.class, club)).isZero();
	}

	@RepeatedTest(REPEAT)
	void twoTransfersRacingLeaveExactlyOneCreator() throws Exception {
		Member creator = member("tr-c", Role.PARTICIPANT);
		long club = approvedClub(creator);
		Member a = joined(club, "tr-a");
		Member b = joined(club, "tr-b");

		List<HttpResponse<String>> r = HttpTestClient.race(
				() -> creator.client().send("POST", "/api/clubs/" + club + "/transfer", "{\"accountId\":" + a.id() + "}"),
				() -> creator.client().send("POST", "/api/clubs/" + club + "/transfer", "{\"accountId\":" + b.id() + "}"));

		assertThat(r.stream().map(HttpResponse::statusCode).sorted().toList()).isEqualTo(List.of(200, 403));
		long newCreator = jdbc.queryForObject("select creator_account_id from club where id = ?", Long.class, club);
		assertThat(newCreator).isIn(a.id(), b.id());
		assertThat(jdbc.queryForObject("select role from club_member where club_id = ? and account_id = ?", String.class, club,
				newCreator)).isEqualTo("MANAGER");
	}

	private long approvedClub(Member creator) throws Exception {
		HttpResponse<String> applied = creator.client().send("POST", "/api/clubs",
				"{\"name\":\"경합 모임\",\"intro\":\"소개\",\"place\":\"강남\",\"mode\":\"OFFLINE\"}");
		assertThat(applied.statusCode()).as(applied.body()).isEqualTo(201);
		long club = ((Number) JsonPath.read(applied.body(), "$.id")).longValue();
		Member operator = member("op", Role.OPERATOR);
		HttpResponse<String> approved = operator.client().send("POST", "/api/operator/clubs/" + club + "/approve",
				"{\"version\":" + JsonPath.read(applied.body(), "$.version") + "}");
		assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
		return club;
	}

	private Member joined(long club, String prefix) throws Exception {
		Member m = member(prefix, Role.PARTICIPANT);
		assertThat(m.client().send("POST", "/api/clubs/" + club + "/join", null).statusCode()).isEqualTo(200);
		return m;
	}

	private long postIn(long club, Member writer, String board) throws Exception {
		HttpResponse<String> r = writer.client().send("POST", "/api/clubs/" + club + "/boards/" + board + "/posts",
				"{\"title\":\"글\",\"body\":\"본문\"}");
		assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
		return ((Number) JsonPath.read(r.body(), "$.id")).longValue();
	}

	private Member member(String prefix, Role role) throws Exception {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		Account account = accounts.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), prefix, role, Instant.now()));
		return new Member(account.getId(), HttpTestClient.login("http://localhost:" + port, loginId, PASSWORD));
	}

}

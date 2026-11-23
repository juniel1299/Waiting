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
 * 커뮤니티 경합 (실제 포트·PostgreSQL, CyclicBarrier 동시 출발, 각 20회).
 * 1) 같은 계정의 같은 댓글 추천 두 번이 겹쳐도 추천 행은 1개 (PK).
 * 2) 글 삭제와 댓글 작성이 겹쳐도 지워진 글의 댓글이 남지 않는다.
 * 3) 부모 댓글 삭제와 답글 작성·추천이 겹쳐도 고아 답글·추천이 남지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class CommentConcurrencyTests {

	static final int REPEAT = 20;

	static final String PASSWORD = "comment-race-1!";

	@Value("${local.server.port}")
	int port;

	@Autowired
	AccountRepository accounts;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbc;

	@RepeatedTest(REPEAT)
	void doubleLikeFromOneAccountLeavesOneRow() throws Exception {
		HttpTestClient writer = client("race-w");
		HttpTestClient liker = client("race-l");
		long post = postOf(writer);
		long comment = idOf(writer.send("POST", "/api/posts/" + post + "/comments", "{\"body\":\"추천 경합\"}"));

		List<HttpResponse<String>> responses = HttpTestClient.race(
				() -> liker.send("PUT", "/api/comments/" + comment + "/like", null),
				() -> liker.send("PUT", "/api/comments/" + comment + "/like", null));

		assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
		assertThat(jdbc.queryForObject("select count(*) from comment_like where comment_id = ?", Long.class, comment))
			.isEqualTo(1L);
	}

	@RepeatedTest(REPEAT)
	void deletingAPostWhileCommentingLeavesNoComments() throws Exception {
		HttpTestClient writer = client("race-pw");
		HttpTestClient commenter = client("race-pc");
		long post = postOf(writer);

		List<HttpResponse<String>> responses = HttpTestClient.race(
				() -> writer.send("DELETE", "/api/posts/" + post, null),
				() -> commenter.send("POST", "/api/posts/" + post + "/comments", "{\"body\":\"삭제와 경합\"}"));

		assertThat(responses.get(0).statusCode()).as(responses.get(0).body()).isEqualTo(204);
		assertThat(responses.get(1).statusCode()).as(responses.get(1).body()).isIn(201, 404);
		assertThat(jdbc.queryForObject("select count(*) from comment where post_id = ?", Long.class, post)).isZero();
	}

	@RepeatedTest(REPEAT)
	void deletingAParentWhileReplyingOrLikingLeavesNoOrphans() throws Exception {
		HttpTestClient writer = client("race-cw");
		HttpTestClient replier = client("race-cr");
		long post = postOf(writer);
		long parent = idOf(writer.send("POST", "/api/posts/" + post + "/comments", "{\"body\":\"부모\"}"));

		List<HttpResponse<String>> responses = HttpTestClient.race(
				() -> writer.send("DELETE", "/api/comments/" + parent, null),
				() -> replier.send("POST", "/api/posts/" + post + "/comments",
						"{\"body\":\"답글 @[작성자#1]\",\"parentId\":" + parent + "}"));
		HttpResponse<String> like = replier.send("PUT", "/api/comments/" + parent + "/like", null);

		assertThat(responses.get(0).statusCode()).as(responses.get(0).body()).isEqualTo(204);
		assertThat(responses.get(1).statusCode()).as(responses.get(1).body()).isIn(201, 404);
		assertThat(like.statusCode()).isEqualTo(404);
		assertThat(jdbc.queryForObject("select count(*) from comment where parent_id = ? or id = ?", Long.class, parent,
				parent)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from comment_like where comment_id = ?", Long.class, parent))
			.isZero();
		assertThat(jdbc.queryForObject("select count(*) from mention m where m.comment_id is not null "
				+ "and not exists (select 1 from comment c where c.id = m.comment_id)", Long.class)).isZero();
	}

	private long postOf(HttpTestClient writer) throws Exception {
		return idOf(writer.send("POST", "/api/boards/community/posts", "{\"title\":\"경합 글\",\"body\":\"본문\"}"));
	}

	private HttpTestClient client(String prefix) throws Exception {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		accounts.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), prefix, Role.PARTICIPANT, Instant.now()));
		return HttpTestClient.login("http://localhost:" + port, loginId, PASSWORD);
	}

	private static long idOf(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return ((Number) JsonPath.read(response.body(), "$.id")).longValue();
	}

}

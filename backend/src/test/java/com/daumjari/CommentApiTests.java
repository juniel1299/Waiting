package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * 커뮤니티 2단계: 댓글·한 단계 답글·추천·멘션과 공개 닉네임.
 * 조회는 글 조회 범위(공지는 비회원도), 작성·추천은 일반 회원·운영자, 수정은 본인, 삭제는 본인·운영자.
 * 글·부모 댓글 삭제는 답글·추천·멘션까지 물리 삭제. 멘션은 계정 id 에 연결되고 응답에는 닉네임·id 만 나간다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CommentApiTests extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	Account writer;

	MockHttpSession writerSession;

	Account reader;

	MockHttpSession readerSession;

	MockHttpSession operatorSession;

	long postId;

	@BeforeEach
	void setUp() throws Exception {
		writer = newAccount("cmt-writer", Role.PARTICIPANT);
		writerSession = login(writer);
		reader = newAccount("cmt-reader", Role.PARTICIPANT);
		readerSession = login(reader);
		operatorSession = login(newAccount("cmt-op", Role.OPERATOR));
		postId = idOf(mockMvc.perform(withXsrf(post("/api/boards/community/posts"), writerSession)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"댓글 글\",\"body\":\"본문\"}")).andExpect(status().isCreated()));
	}

	@Test
	void membersCommentAndReplyOneLevelDeepAndTheTreeComesBackInOrder() throws Exception {
		long c1 = idOf(comment(readerSession, postId, null, "첫 댓글").andExpect(status().isCreated())
			.andExpect(jsonPath("$.author.label").value("회원" + reader.getId()))
			.andExpect(jsonPath("$.canEdit").value(true)));
		long r1 = idOf(comment(writerSession, postId, c1, "답글").andExpect(status().isCreated())
			.andExpect(jsonPath("$.parentId").value(c1)));
		comment(readerSession, postId, r1, "답글의 답글").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("REPLY_DEPTH_EXCEEDED"));
		long c2 = idOf(comment(writerSession, postId, null, "둘째 댓글").andExpect(status().isCreated()));

		mockMvc.perform(get("/api/posts/{id}/comments", postId).session(readerSession))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].id").value(c1))
			.andExpect(jsonPath("$[0].replies.length()").value(1))
			.andExpect(jsonPath("$[0].replies[0].id").value(r1))
			.andExpect(jsonPath("$[0].replies[0].canEdit").value(false))
			.andExpect(jsonPath("$[1].id").value(c2));
		mockMvc.perform(get("/api/posts/{id}", postId).session(readerSession))
			.andExpect(jsonPath("$.commentCount").value(3));
		// 다른 글의 댓글을 부모로 지정할 수 없다
		long otherPost = idOf(mockMvc.perform(withXsrf(post("/api/boards/community/posts"), writerSession)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"다른 글\",\"body\":\"본문\"}")));
		comment(readerSession, otherPost, c1, "엉뚱한 부모").andExpect(status().isBadRequest());
	}

	@Test
	void onlyAuthorsEditAndAuthorsOrOperatorsDelete() throws Exception {
		long c = idOf(comment(readerSession, postId, null, "내 댓글").andExpect(status().isCreated()));
		edit(writerSession, c, "남의 댓글 수정").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_COMMENT_AUTHOR"));
		edit(operatorSession, c, "운영자 수정").andExpect(status().isForbidden());
		edit(readerSession, c, "고친 댓글").andExpect(status().isOk())
			.andExpect(jsonPath("$.edited").value(true))
			.andExpect(jsonPath("$.body").value("고친 댓글"));
		mockMvc.perform(withXsrf(delete("/api/comments/{id}", c), writerSession)).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/comments/{id}", c), operatorSession)).andExpect(status().isNoContent());
		long mine = idOf(comment(readerSession, postId, null, "지울 댓글"));
		mockMvc.perform(withXsrf(delete("/api/comments/{id}", mine), readerSession)).andExpect(status().isNoContent());
		mockMvc.perform(withXsrf(delete("/api/comments/{id}", mine), readerSession)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));
	}

	@Test
	void likesAreOnePerAccountIdempotentAndCancellable() throws Exception {
		long c = idOf(comment(writerSession, postId, null, "추천 받을 댓글"));
		like(readerSession, c, true).andExpect(status().isOk())
			.andExpect(jsonPath("$.likeCount").value(1))
			.andExpect(jsonPath("$.liked").value(true));
		like(readerSession, c, true).andExpect(jsonPath("$.likeCount").value(1));
		like(writerSession, c, true).andExpect(jsonPath("$.likeCount").value(2));
		mockMvc.perform(get("/api/posts/{id}/comments", postId).session(readerSession))
			.andExpect(jsonPath("$[0].likeCount").value(2))
			.andExpect(jsonPath("$[0].liked").value(true));
		like(readerSession, c, false).andExpect(jsonPath("$.likeCount").value(1))
			.andExpect(jsonPath("$.liked").value(false));
		like(readerSession, c, false).andExpect(jsonPath("$.likeCount").value(1));
		assertThat(count("select count(*) from comment_like where comment_id = ?", c)).isEqualTo(1L);
	}

	@Test
	void anonymousAndDemoVisitorsOnlyReadWithinThePostsVisibility() throws Exception {
		long notice = idOf(mockMvc.perform(withXsrf(post("/api/boards/notice/posts"), operatorSession)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"공지\",\"body\":\"본문\"}")));
		long nc = idOf(comment(readerSession, notice, null, "공지 댓글").andExpect(status().isCreated()));
		long cc = idOf(comment(readerSession, postId, null, "게시판 댓글"));

		mockMvc.perform(get("/api/posts/{id}/comments", notice))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].body").value("공지 댓글"))
			.andExpect(jsonPath("$[0].canEdit").value(false));
		mockMvc.perform(get("/api/posts/{id}/comments", postId)).andExpect(status().isUnauthorized());
		mockMvc.perform(withXsrf(post("/api/posts/{id}/comments", notice), null).contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"비회원\"}")).andExpect(status().isUnauthorized());

		MockHttpSession demo = startDemo();
		mockMvc.perform(get("/api/posts/{id}/comments", postId).session(demo)).andExpect(status().isOk());
		comment(demo, postId, null, "데모 댓글").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("COMMENT_WRITE_FORBIDDEN"));
		like(demo, cc, true).andExpect(status().isForbidden());
		like(demo, nc, true).andExpect(status().isForbidden());
	}

	@Test
	void deletingAParentCommentRemovesRepliesLikesAndMentionsPhysically() throws Exception {
		long parent = idOf(comment(readerSession, postId, null, "부모 @[작성자#" + writer.getId() + "]"));
		long reply = idOf(comment(writerSession, postId, parent, "답글"));
		like(writerSession, parent, true);
		like(readerSession, reply, true);
		mockMvc.perform(get("/api/posts/{id}/comments", postId).session(readerSession))
			.andExpect(jsonPath("$[0].replyCount").value(1));

		mockMvc.perform(withXsrf(delete("/api/comments/{id}", parent), readerSession)).andExpect(status().isNoContent());
		assertThat(count("select count(*) from comment where id in (?, ?)", parent, reply)).isZero();
		assertThat(count("select count(*) from comment_like where comment_id in (?, ?)", parent, reply)).isZero();
		assertThat(count("select count(*) from mention where comment_id in (?, ?)", parent, reply)).isZero();
	}

	@Test
	void deletingAPostRemovesItsCommentsLikesAndMentionsPhysically() throws Exception {
		long c = idOf(comment(readerSession, postId, null, "댓글 @[작성자#" + writer.getId() + "]"));
		long r = idOf(comment(writerSession, postId, c, "답글"));
		like(writerSession, c, true);
		mockMvc.perform(withXsrf(delete("/api/posts/{id}", postId), writerSession)).andExpect(status().isNoContent());
		assertThat(count("select count(*) from comment where id in (?, ?)", c, r)).isZero();
		assertThat(count("select count(*) from comment_like where comment_id in (?, ?)", c, r)).isZero();
		assertThat(count("select count(*) from mention where comment_id in (?, ?)", c, r)).isZero();
		mockMvc.perform(get("/api/posts/{id}/comments", postId).session(readerSession)).andExpect(status().isNotFound());
	}

	@Test
	void mentionsLinkToAccountIdsAndShowTheCurrentNicknameOnly() throws Exception {
		Account target = newAccount("cmt-target", Role.PARTICIPANT);
		MockHttpSession targetSession = login(target);
		nickname(targetSession, "  별빛 코더 ").andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("별빛 코더"));
		String token = "@[예전이름#" + target.getId() + "]";
		long c = idOf(comment(readerSession, postId, null, "안녕 " + token + " 그리고 @[없는사람#999999999]"));

		String body = mockMvc.perform(get("/api/posts/{id}/comments", postId).session(readerSession))
			.andExpect(jsonPath("$[0].body").value("안녕 " + token + " 그리고 @[없는사람#999999999]"))
			.andExpect(jsonPath("$[0].mentions.length()").value(1))
			.andExpect(jsonPath("$[0].mentions[0].id").value(target.getId()))
			.andExpect(jsonPath("$[0].mentions[0].nickname").value("별빛 코더"))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).doesNotContain(target.getLoginId());
		assertThat(count("select count(*) from mention where comment_id = ? and account_id = ?", c, target.getId())).isEqualTo(1L);

		// 닉네임을 바꾸면 기존 멘션도 새 닉네임으로 보인다 (연결은 계정 id)
		nickname(targetSession, "새별").andExpect(status().isOk());
		mockMvc.perform(get("/api/posts/{id}/comments", postId).session(readerSession))
			.andExpect(jsonPath("$[0].mentions[0].nickname").value("새별"));
		// 글 본문 멘션도 같은 방식
		long p = idOf(mockMvc.perform(withXsrf(post("/api/boards/community/posts"), readerSession)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"멘션 글\",\"body\":\"" + token + "\"}")));
		mockMvc.perform(get("/api/posts/{id}", p).session(readerSession))
			.andExpect(jsonPath("$.mentions[0].nickname").value("새별"));
	}

	@Test
	void mentionCandidatesExposeOnlyNicknameAndIdAndSkipDemoAndWithdrawnAccounts() throws Exception {
		String nick = "후보" + UUID.randomUUID().toString().substring(0, 6);
		Account a = newAccount("cand-a", Role.PARTICIPANT);
		nickname(login(a), nick + "가").andExpect(status().isOk());
		Account gone = newAccount("cand-gone", Role.PARTICIPANT);
		MockHttpSession goneSession = login(gone);
		nickname(goneSession, nick + "나").andExpect(status().isOk());
		mockMvc.perform(withXsrf(post("/api/me/withdraw"), goneSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(PASSWORD))).andExpect(status().isNoContent());

		String body = mockMvc.perform(get("/api/mentions/candidates").param("q", nick).session(readerSession))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(a.getId()))
			.andExpect(jsonPath("$[0].nickname").value(nick + "가"))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).doesNotContain(a.getLoginId()).doesNotContain("loginId").doesNotContain("role");
		mockMvc.perform(get("/api/mentions/candidates").param("q", nick)).andExpect(status().isUnauthorized());
	}

	@Test
	void nicknamesAreValidatedAndUsedAsTheAuthorLabel() throws Exception {
		for (String bad : List.of("a", "가".repeat(21), "운영자", "탈퇴한 회원", "이름@골뱅이", "   ")) {
			nickname(readerSession, bad).andExpect(status().isBadRequest());
		}
		mockMvc.perform(get("/api/me/profile").session(readerSession))
			.andExpect(jsonPath("$.nickname").value("회원" + reader.getId()));
		nickname(readerSession, "읽는사람").andExpect(status().isOk());
		comment(readerSession, postId, null, "닉네임 표시").andExpect(jsonPath("$.author.label").value("읽는사람"));
		mockMvc.perform(get("/api/posts/{id}", postId).session(readerSession))
			.andExpect(jsonPath("$.author.label").value("회원" + writer.getId()));
	}

	private ResultActions comment(MockHttpSession session, long post, Long parentId, String body) throws Exception {
		String json = parentId == null ? "{\"body\":%s}".formatted(quote(body))
				: "{\"body\":%s,\"parentId\":%d}".formatted(quote(body), parentId);
		return mockMvc.perform(withXsrf(post("/api/posts/{id}/comments", post), session)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private ResultActions edit(MockHttpSession session, long id, String body) throws Exception {
		return mockMvc.perform(withXsrf(patch("/api/comments/{id}", id), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":%s}".formatted(quote(body))));
	}

	private ResultActions like(MockHttpSession session, long id, boolean on) throws Exception {
		return mockMvc.perform(withXsrf(on ? put("/api/comments/{id}/like", id) : delete("/api/comments/{id}/like", id), session));
	}

	private ResultActions nickname(MockHttpSession session, String value) throws Exception {
		return mockMvc.perform(withXsrf(patch("/api/me/profile"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"nickname\":%s}".formatted(quote(value))));
	}

	private static String quote(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private static long idOf(ResultActions result) throws Exception {
		Number id = JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");
		return id.longValue();
	}

	private MockHttpSession startDemo() throws Exception {
		String ip = "100.66." + ThreadLocalRandom.current().nextInt(1, 255) + "." + ThreadLocalRandom.current().nextInt(1, 255);
		return (MockHttpSession) mockMvc.perform(withXsrf(post("/api/demo/start"), null).with(request -> {
			request.setRemoteAddr(ip);
			return request;
		})).andExpect(status().isCreated()).andReturn().getRequest().getSession(false);
	}

}

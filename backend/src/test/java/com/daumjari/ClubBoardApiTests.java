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
 * 커뮤니티 5단계: 모임 역할(생성자·모임 운영자·회원)과 모임 내부 게시판·모임 공지.
 * 내부 글·댓글은 모임 회원만(서비스 운영자는 조회·삭제만). 회원 글 수정은 작성자, 삭제는 작성자·모임 운영자.
 * 모임 공지는 모임 운영자가 쓰고 고치고 지운다. 운영자 임명·회수·생성자 이전은 생성자만. 매 요청마다 현재 가입·역할을 다시 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ClubBoardApiTests extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	Account creator;

	MockHttpSession creatorSession;

	Account member;

	MockHttpSession memberSession;

	Account other;

	MockHttpSession otherSession;

	MockHttpSession outsiderSession;

	MockHttpSession operatorSession;

	long club;

	@BeforeEach
	void setUp() throws Exception {
		creator = newAccount("cb-creator", Role.PARTICIPANT);
		creatorSession = login(creator);
		member = newAccount("cb-member", Role.PARTICIPANT);
		memberSession = login(member);
		other = newAccount("cb-other", Role.PARTICIPANT);
		otherSession = login(other);
		outsiderSession = login(newAccount("cb-outsider", Role.PARTICIPANT));
		operatorSession = login(newAccount("cb-op", Role.OPERATOR));
		String created = mockMvc.perform(withXsrf(post("/api/clubs"), creatorSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"내부 모임\",\"intro\":\"소개\",\"place\":\"강남\",\"mode\":\"OFFLINE\"}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		club = ((Number) JsonPath.read(created, "$.id")).longValue();
		long version = ((Number) JsonPath.read(created, "$.version")).longValue();
		mockMvc.perform(withXsrf(post("/api/operator/clubs/{id}/approve", club), operatorSession)
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}")).andExpect(status().isOk());
		join(memberSession).andExpect(status().isOk());
		join(otherSession).andExpect(status().isOk());
	}

	@Test
	void onlyMembersSeeTheInternalBoardAndOperatorsOnlyReadAndDelete() throws Exception {
		long p = id(write(memberSession, "board", "회원 글", "본문").andExpect(status().isCreated()));

		mockMvc.perform(get("/api/clubs/{id}/boards/board/posts", club).session(otherSession)).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(p))
			.andExpect(jsonPath("$.canWrite").value(true));
		mockMvc.perform(get("/api/clubs/{id}/boards/board/posts", club).session(outsiderSession))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CLUB_MEMBERS_ONLY"));
		mockMvc.perform(get("/api/posts/{id}", p).session(outsiderSession)).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/posts/{id}/comments", p).session(outsiderSession)).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/posts/{id}", p)).andExpect(status().isUnauthorized());
		write(outsiderSession, "board", "밖에서", "본문").andExpect(status().isForbidden());

		mockMvc.perform(get("/api/posts/{id}", p).session(operatorSession)).andExpect(status().isOk())
			.andExpect(jsonPath("$.canDelete").value(true))
			.andExpect(jsonPath("$.canEdit").value(false));
		write(operatorSession, "board", "운영자", "본문").andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/posts/{id}", p), operatorSession)).andExpect(status().isNoContent());
	}

	@Test
	void membersEditOwnPostsAndClubManagersDeleteAnyAndWriteNotices() throws Exception {
		long p = id(write(memberSession, "board", "회원 글", "본문").andExpect(status().isCreated()));
		mockMvc.perform(withXsrf(patch("/api/posts/{id}", p), otherSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"남의 글\",\"body\":\"본문\"}")).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/posts/{id}", p), otherSession)).andExpect(status().isForbidden());
		write(memberSession, "notice", "회원 공지", "본문").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("BOARD_WRITE_FORBIDDEN"));

		long notice = id(write(creatorSession, "notice", "모임 공지", "본문").andExpect(status().isCreated()));
		appoint(creatorSession, member).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MANAGER"));
		mockMvc.perform(withXsrf(patch("/api/posts/{id}", notice), memberSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"공지 수정\",\"body\":\"본문\"}")).andExpect(status().isOk());
		long otherPost = id(write(otherSession, "board", "다른 회원 글", "본문").andExpect(status().isCreated()));
		mockMvc.perform(withXsrf(delete("/api/posts/{id}", otherPost), memberSession)).andExpect(status().isNoContent());
		mockMvc.perform(withXsrf(patch("/api/posts/{id}", p), creatorSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"운영자도 남의 글은\",\"body\":\"본문\"}")).andExpect(status().isForbidden());
	}

	@Test
	void commentsLikesAndMentionsAreLimitedToMembersAndManagersDeleteComments() throws Exception {
		Account outsider = newAccount("cb-mention-out", Role.PARTICIPANT);
		long p = id(write(memberSession, "board", "댓글 글", "@[밖#%d] @[다른#%d]".formatted(outsider.getId(), other.getId()))
			.andExpect(status().isCreated()));
		assertThat(jdbc.queryForList("select account_id from mention where post_id = ?", Long.class, p))
			.containsExactly(other.getId());
		assertThat(jdbc.queryForObject("select count(*) from notification where recipient_account_id = ?", Long.class,
				outsider.getId())).isZero();

		long c = id(mockMvc.perform(withXsrf(post("/api/posts/{id}/comments", p), otherSession)
			.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"회원 댓글\"}")).andExpect(status().isCreated()));
		mockMvc.perform(withXsrf(post("/api/posts/{id}/comments", p), outsiderSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"밖 댓글\"}")).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(put("/api/comments/{id}/like", c), outsiderSession)).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(put("/api/comments/{id}/like", c), memberSession)).andExpect(status().isOk());
		mockMvc.perform(withXsrf(delete("/api/comments/{id}", c), memberSession)).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/comments/{id}", c), creatorSession)).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/mentions/candidates").session(memberSession).param("q", "회원" + outsider.getId())
			.param("clubId", String.valueOf(club))).andExpect(jsonPath("$.length()").value(0));
		mockMvc.perform(get("/api/mentions/candidates").session(memberSession).param("q", "회원" + other.getId())
			.param("clubId", String.valueOf(club))).andExpect(jsonPath("$.length()").value(1));
		mockMvc.perform(get("/api/mentions/candidates").session(outsiderSession).param("q", "회원")
			.param("clubId", String.valueOf(club))).andExpect(status().isForbidden());
	}

	@Test
	void onlyTheCreatorAppointsAndRevokesAndForgedRequestsAreRefused() throws Exception {
		appoint(memberSession, other).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_CLUB_CREATOR"));
		appoint(creatorSession, member).andExpect(status().isOk());
		// 임명된 운영자도 임명·회수는 못 한다 (본문에 생성자 id 를 넣어도 세션 계정으로만 판단)
		appoint(memberSession, other).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/clubs/{id}/managers/{a}", club, member.getId()), memberSession))
			.andExpect(status().isForbidden());
		Account stranger = newAccount("cb-stranger", Role.PARTICIPANT);
		appoint(creatorSession, stranger).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CLUB_MEMBER_NOT_FOUND"));
		mockMvc.perform(withXsrf(delete("/api/clubs/{id}/managers/{a}", club, creator.getId()), creatorSession))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CREATOR_ROLE_FIXED"));

		mockMvc.perform(withXsrf(delete("/api/clubs/{id}/managers/{a}", club, member.getId()), creatorSession))
			.andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MEMBER"));
		write(memberSession, "notice", "회수 뒤 공지", "본문").andExpect(status().isForbidden());
		mockMvc.perform(get("/api/clubs/{id}/members", club).session(memberSession)).andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.accountId == %d)].role".formatted(creator.getId())).value("MANAGER"))
			.andExpect(jsonPath("$[?(@.accountId == %d)].creator".formatted(creator.getId())).value(true));
		mockMvc.perform(get("/api/clubs/{id}/members", club).session(outsiderSession)).andExpect(status().isForbidden());
	}

	@Test
	void transferringCreatorLetsTheOldCreatorLeave() throws Exception {
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/transfer", club), memberSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"accountId\":" + member.getId() + "}")).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/transfer", club), creatorSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"accountId\":" + member.getId() + "}")).andExpect(status().isOk())
			.andExpect(jsonPath("$.mine").value(false));

		assertThat(jdbc.queryForObject("select creator_account_id from club where id = ?", Long.class, club))
			.isEqualTo(member.getId());
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/leave", club), memberSession)).andExpect(status().isConflict());
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/leave", club), creatorSession)).andExpect(status().isOk());
		mockMvc.perform(get("/api/clubs/{id}/boards/board/posts", club).session(creatorSession))
			.andExpect(status().isForbidden());
	}

	@Test
	void leavingRevokesAccessToPostsAndTheirNotifications() throws Exception {
		long p = id(write(memberSession, "board", "멘션 글", "@[다른#%d]".formatted(other.getId())).andExpect(status().isCreated()));
		long n = jdbc.queryForObject("select id from notification where recipient_account_id = ? and post_id = ?", Long.class,
				other.getId(), p);
		mockMvc.perform(withXsrf(post("/api/me/notifications/{id}/open", n), otherSession)).andExpect(status().isOk())
			.andExpect(jsonPath("$.href").value("/clubs/" + club + "/board/" + p));

		mockMvc.perform(withXsrf(post("/api/clubs/{id}/leave", club), otherSession)).andExpect(status().isOk());
		mockMvc.perform(get("/api/posts/{id}", p).session(otherSession)).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(post("/api/me/notifications/{id}/open", n), otherSession)).andExpect(status().isForbidden());
	}

	@Test
	void deletingTheClubRemovesItsPostsCommentsAndNotifications() throws Exception {
		long p = id(write(memberSession, "board", "지워질 글", "@[다른#%d]".formatted(other.getId())).andExpect(status().isCreated()));
		mockMvc.perform(withXsrf(post("/api/posts/{id}/comments", p), otherSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"댓글\"}")).andExpect(status().isCreated());

		mockMvc.perform(withXsrf(delete("/api/clubs/{id}", club), creatorSession)).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select count(*) from board_post where club_id = ?", Long.class, club)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from comment where post_id = ?", Long.class, p)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from notification where post_id = ?", Long.class, p)).isZero();
	}

	// --- helpers ---

	private ResultActions join(MockHttpSession session) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/clubs/{id}/join", club), session));
	}

	private ResultActions write(MockHttpSession session, String board, String title, String body) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/clubs/{id}/boards/{b}/posts", club, board), session)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"%s\",\"body\":\"%s\"}".formatted(title, body)));
	}

	private ResultActions appoint(MockHttpSession session, Account target) throws Exception {
		return mockMvc.perform(withXsrf(put("/api/clubs/{id}/managers/{a}", club, target.getId()), session)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"role\":\"MANAGER\",\"accountId\":" + creator.getId() + "}"));
	}

	private static long id(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id"))
			.longValue();
	}

}

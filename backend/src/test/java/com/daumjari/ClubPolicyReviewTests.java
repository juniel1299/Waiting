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
 * PR #45 병합 뒤 검토: 이미 로그인한 세션에서도 권한 회수·모임 탈퇴가 바로 반영되는지, 알림 링크가 지금 권한을 다시 보는지,
 * 모임 삭제 중 실패하면 모임과 종속 데이터가 함께 롤백되는지 (실제 PostgreSQL).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ClubPolicyReviewTests extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	MockHttpSession creatorSession;

	Account manager;

	MockHttpSession managerSession;

	Account member;

	MockHttpSession memberSession;

	long club;

	@BeforeEach
	void setUp() throws Exception {
		creatorSession = login(newAccount("rv-creator", Role.PARTICIPANT));
		manager = newAccount("rv-manager", Role.PARTICIPANT);
		managerSession = login(manager);
		member = newAccount("rv-member", Role.PARTICIPANT);
		memberSession = login(member);
		MockHttpSession operator = login(newAccount("rv-op", Role.OPERATOR));
		String created = mockMvc.perform(withXsrf(post("/api/clubs"), creatorSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"검토 모임\",\"intro\":\"소개\",\"place\":\"강남\",\"mode\":\"OFFLINE\"}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		club = ((Number) JsonPath.read(created, "$.id")).longValue();
		mockMvc.perform(withXsrf(post("/api/operator/clubs/{id}/approve", club), operator).contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + JsonPath.read(created, "$.version") + "}")).andExpect(status().isOk());
		for (MockHttpSession s : new MockHttpSession[] { managerSession, memberSession }) {
			mockMvc.perform(withXsrf(post("/api/clubs/{id}/join", club), s)).andExpect(status().isOk());
		}
		mockMvc.perform(withXsrf(put("/api/clubs/{id}/managers/{a}", club, manager.getId()), creatorSession))
			.andExpect(status().isOk());
	}

	@Test
	void revokedManagerIsRefusedNoticeWriteEditAndDeleteInTheSameSession() throws Exception {
		long own = id(write(managerSession, "notice", "운영자 공지", "본문").andExpect(status().isCreated()));
		long creators = id(write(creatorSession, "notice", "생성자 공지", "본문").andExpect(status().isCreated()));
		long memberPost = id(write(memberSession, "board", "회원 글", "본문").andExpect(status().isCreated()));

		mockMvc.perform(withXsrf(delete("/api/clubs/{id}/managers/{a}", club, manager.getId()), creatorSession))
			.andExpect(status().isOk());

		// 다시 로그인하지 않은 같은 세션: 회수가 바로 반영된다
		write(managerSession, "notice", "회수 뒤 공지", "본문").andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(patch("/api/posts/{id}", own), managerSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"회수 뒤 수정\",\"body\":\"본문\"}")).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/posts/{id}", own), managerSession)).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/posts/{id}", creators), managerSession)).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/posts/{id}", memberPost), managerSession)).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/posts/{id}", own).session(managerSession)).andExpect(status().isOk())
			.andExpect(jsonPath("$.canEdit").value(false))
			.andExpect(jsonPath("$.canDelete").value(false));
		assertThat(jdbc.queryForObject("select count(*) from board_post where id in (?, ?, ?)", Long.class, own, creators,
				memberPost)).isEqualTo(3);
	}

	@Test
	void leavingBlocksBoardsNoticesMembersAndEveryDirectApiAtOnce() throws Exception {
		long p = id(write(memberSession, "board", "탈퇴할 사람 글", "본문").andExpect(status().isCreated()));
		long notice = id(write(creatorSession, "notice", "공지", "본문").andExpect(status().isCreated()));
		long c = id(mockMvc.perform(withXsrf(post("/api/posts/{id}/comments", p), memberSession)
			.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"댓글\"}")).andExpect(status().isCreated()));

		mockMvc.perform(withXsrf(post("/api/clubs/{id}/leave", club), memberSession)).andExpect(status().isOk());

		for (String path : new String[] { "/api/clubs/" + club + "/boards/board/posts", "/api/clubs/" + club + "/boards/notice/posts",
				"/api/clubs/" + club + "/members", "/api/posts/" + p, "/api/posts/" + notice, "/api/posts/" + p + "/comments" }) {
			mockMvc.perform(get(path).session(memberSession)).andExpect(status().isForbidden());
		}
		mockMvc.perform(get("/api/mentions/candidates").session(memberSession).param("q", "회원").param("clubId", "" + club))
			.andExpect(status().isForbidden());
		write(memberSession, "board", "탈퇴 뒤 글", "본문").andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(patch("/api/posts/{id}", p), memberSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"탈퇴 뒤 수정\",\"body\":\"본문\"}")).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/posts/{id}", p), memberSession)).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(post("/api/posts/{id}/comments", p), memberSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"탈퇴 뒤 댓글\"}")).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(patch("/api/comments/{id}", c), memberSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"탈퇴 뒤 댓글 수정\"}")).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/comments/{id}", c), memberSession)).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(put("/api/comments/{id}/like", c), memberSession)).andExpect(status().isForbidden());
		// 모임 소개는 승인된 모임이라 여전히 보이고, 가입 상태는 '아님'
		mockMvc.perform(get("/api/clubs/{id}", club).session(memberSession)).andExpect(status().isOk())
			.andExpect(jsonPath("$.joined").value(false))
			.andExpect(jsonPath("$.myRole").doesNotExist());
	}

	@Test
	void notificationLinksRecheckCurrentAccessAfterRevokeAndDeletion() throws Exception {
		long notice = id(write(creatorSession, "notice", "멘션 공지", "@[운영자#%d]".formatted(manager.getId()))
			.andExpect(status().isCreated()));
		long n = jdbc.queryForObject("select id from notification where recipient_account_id = ? and post_id = ?", Long.class,
				manager.getId(), notice);

		// 운영자 → 회원으로 바뀌어도 회원이면 읽을 수 있다 (링크는 열리고, 수정 권한은 없다)
		mockMvc.perform(withXsrf(delete("/api/clubs/{id}/managers/{a}", club, manager.getId()), creatorSession))
			.andExpect(status().isOk());
		mockMvc.perform(withXsrf(post("/api/me/notifications/{id}/open", n), managerSession)).andExpect(status().isOk())
			.andExpect(jsonPath("$.href").value("/clubs/" + club + "/notices/" + notice));
		mockMvc.perform(get("/api/posts/{id}", notice).session(managerSession)).andExpect(jsonPath("$.canEdit").value(false));

		// 글이 지워지면 알림도 함께 지워져 링크가 없다 (404)
		mockMvc.perform(withXsrf(delete("/api/posts/{id}", notice), creatorSession)).andExpect(status().isNoContent());
		mockMvc.perform(withXsrf(post("/api/me/notifications/{id}/open", n), managerSession)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
	}

	@Test
	void aFailureWhileDeletingTheClubRollsBackTheClubAndEverythingInIt() throws Exception {
		long p = id(write(memberSession, "board", "롤백 확인 글", "@[운영자#%d]".formatted(manager.getId()))
			.andExpect(status().isCreated()));
		mockMvc.perform(withXsrf(post("/api/posts/{id}/comments", p), memberSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"rollback-probe\"}")).andExpect(status().isCreated());
		long[] before = counts();
		assertThat(before).doesNotContain(0L);
		// 이 댓글이 지워지려는 순간 DB 가 실패하게 한다 (모임 삭제 CASCADE 의 끝 단계)
		jdbc.execute("""
				create or replace function fail_on_probe_delete() returns trigger language plpgsql as $$
				begin
				    if old.body = 'rollback-probe' then raise exception 'probe: delete failed'; end if;
				    return old;
				end $$""");
		jdbc.execute("create trigger trg_fail_on_probe before delete on comment for each row execute function fail_on_probe_delete()");
		try {
			// 처리하지 않은 DB 오류: MockMvc 는 예외를 그대로 던지고, 실제 서버는 500 으로 답한다. 어느 쪽이든 삭제는 실패다
			int status;
			try {
				status = mockMvc.perform(withXsrf(delete("/api/clubs/{id}", club), creatorSession)).andReturn().getResponse()
					.getStatus();
			}
			catch (Exception e) {
				assertThat(e).hasStackTraceContaining("probe: delete failed");
				status = 500;
			}
			assertThat(status).isGreaterThanOrEqualTo(500);
		}
		finally {
			jdbc.execute("drop trigger trg_fail_on_probe on comment");
			jdbc.execute("drop function fail_on_probe_delete()");
		}
		assertThat(counts()).containsExactly(before);
	}

	/** 이 모임의 [모임, 회원, 글, 댓글, 알림] 행 수. */
	private long[] counts() {
		return new long[] { count("select count(*) from club where id = ?"),
				count("select count(*) from club_member where club_id = ?"),
				count("select count(*) from board_post where club_id = ?"),
				count("select count(*) from comment c join board_post p on p.id = c.post_id where p.club_id = ?"),
				count("select count(*) from notification n join board_post p on p.id = n.post_id where p.club_id = ?") };
	}

	private long count(String sql) {
		return jdbc.queryForObject(sql, Long.class, club);
	}

	private ResultActions write(MockHttpSession session, String board, String title, String body) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/clubs/{id}/boards/{b}/posts", club, board), session)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"%s\",\"body\":\"%s\"}".formatted(title, body)));
	}

	private static long id(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id"))
			.longValue();
	}

}

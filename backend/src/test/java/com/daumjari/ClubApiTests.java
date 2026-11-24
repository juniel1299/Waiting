package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
 * 커뮤니티 4단계: 모임 신청·승인·반려·재신청·취소, 승인 모임 목록·소개·가입·탈퇴, 결과 알림, 생성자 탈퇴.
 * 신청은 일반 회원·운영자, 승인·반려는 서비스 운영자. 데모는 승인 모임 소개 조회만.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ClubApiTests extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	Account creator;

	MockHttpSession creatorSession;

	MockHttpSession memberSession;

	MockHttpSession operatorSession;

	@BeforeEach
	void setUp() throws Exception {
		creator = newAccount("club-creator", Role.PARTICIPANT);
		creatorSession = login(creator);
		memberSession = login(newAccount("club-member", Role.PARTICIPANT));
		operatorSession = login(newAccount("club-op", Role.OPERATOR));
	}

	@Test
	void applicationIsPendingAndOnlyTheCreatorAndOperatorsSeeIt() throws Exception {
		long id = id(apply("독서 모임").andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.mode").value("OFFLINE"))
			.andExpect(jsonPath("$.canEdit").value(true))
			.andExpect(jsonPath("$.canCancel").value(true)));

		mockMvc.perform(get("/api/clubs/{id}", id).session(memberSession)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CLUB_NOT_FOUND"));
		mockMvc.perform(get("/api/clubs/{id}", id).session(operatorSession)).andExpect(status().isOk());
		mockMvc.perform(get("/api/clubs").session(memberSession).param("q", "독서 모임"))
			.andExpect(jsonPath("$.totalElements").value(0));
		mockMvc.perform(get("/api/me/clubs").session(creatorSession))
			.andExpect(jsonPath("$.applications[0].id").value(id))
			.andExpect(jsonPath("$.applications[0].status").value("PENDING"));
		mockMvc.perform(get("/api/operator/clubs").session(operatorSession).param("status", "PENDING"))
			.andExpect(jsonPath("$.items[?(@.id == %d)]".formatted(id)).exists());
	}

	@Test
	void invalidApplicationsAndDemoOrAnonymousAreRefused() throws Exception {
		mockMvc.perform(withXsrf(post("/api/clubs"), creatorSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\" \",\"intro\":\"소개\",\"place\":\"강남\",\"mode\":\"OFFLINE\"}"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(withXsrf(post("/api/clubs"), creatorSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"이름\",\"intro\":\"소개\",\"place\":\"강남\",\"mode\":\"SPACE\"}"))
			.andExpect(status().isBadRequest());
		MockHttpSession demo = startDemo();
		mockMvc.perform(withXsrf(post("/api/clubs"), demo).contentType(MediaType.APPLICATION_JSON).content(body("데모 모임")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CLUB_FORBIDDEN"));
		mockMvc.perform(get("/api/clubs")).andExpect(status().isUnauthorized());
	}

	@Test
	void rejectionShowsTheReasonAndEditingResubmitsThenApprovalNotifiesAndAddsTheCreator() throws Exception {
		long id = id(apply("반려될 모임"));
		reject(id, version(id), "장소를 구체적으로 적어 주세요").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"));
		mockMvc.perform(get("/api/clubs/{id}", id).session(creatorSession))
			.andExpect(jsonPath("$.rejectReason").value("장소를 구체적으로 적어 주세요"));
		assertThat(notifications(creator, "CLUB_REJECTED")).isEqualTo(1);

		edit(creatorSession, id, "반려될 모임", "강남역 2번 출구 스터디카페").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.rejectReason").doesNotExist());
		approve(id, version(id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

		assertThat(notifications(creator, "CLUB_APPROVED")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select role from club_member where club_id = ? and account_id = ?", String.class, id,
				creator.getId())).isEqualTo("MANAGER");
		mockMvc.perform(get("/api/me/notifications").session(creatorSession))
			.andExpect(jsonPath("$.items[0].type").value("CLUB_APPROVED"))
			.andExpect(jsonPath("$.items[0].clubName").value("반려될 모임"));
		long n = jdbc.queryForObject(
				"select id from notification where recipient_account_id = ? and type = 'CLUB_APPROVED'", Long.class,
				creator.getId());
		mockMvc.perform(withXsrf(post("/api/me/notifications/{id}/open", n), creatorSession))
			.andExpect(jsonPath("$.href").value("/clubs/" + id));
	}

	@Test
	void staleVersionOrAlreadyDecidedApplicationsAreConflicts() throws Exception {
		long id = id(apply("경합 모임"));
		long seen = version(id);
		edit(creatorSession, id, "경합 모임 수정", "강남").andExpect(status().isOk());

		approve(id, seen).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLUB_CHANGED"));
		approve(id, version(id)).andExpect(status().isOk());
		reject(id, version(id), "늦은 반려").andExpect(status().isConflict());
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/cancel", id), creatorSession)).andExpect(status().isConflict());
		assertThat(notifications(creator, "CLUB_APPROVED")).isEqualTo(1);
	}

	@Test
	void pendingApplicationCanBeCancelledAndOnlyTheCreatorEdits() throws Exception {
		long id = id(apply("취소할 모임"));
		edit(memberSession, id, "남의 모임", "강남").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_CLUB_CREATOR"));
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/cancel", id), creatorSession)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));
		approve(id, version(id)).andExpect(status().isConflict());
	}

	@Test
	void approvedClubIsListedSearchedJoinedOnceAndLeft() throws Exception {
		String name = "등산 " + UUID.randomUUID().toString().substring(0, 6);
		long id = approved(name);
		mockMvc.perform(get("/api/clubs").session(memberSession).param("q", name))
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.items[0].memberCount").value(1))
			.andExpect(jsonPath("$.items[0].canJoin").value(true));

		join(memberSession, id).andExpect(status().isOk())
			.andExpect(jsonPath("$.joined").value(true))
			.andExpect(jsonPath("$.memberCount").value(2));
		join(memberSession, id).andExpect(status().isOk()).andExpect(jsonPath("$.memberCount").value(2));
		mockMvc.perform(get("/api/me/clubs").session(memberSession)).andExpect(jsonPath("$.joined[0].id").value(id));
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/leave", id), memberSession)).andExpect(status().isOk())
			.andExpect(jsonPath("$.joined").value(false))
			.andExpect(jsonPath("$.memberCount").value(1));
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/leave", id), creatorSession)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CREATOR_CANNOT_LEAVE"));
	}

	@Test
	void approvedClubEditsApplyImmediatelyWithoutReapproval() throws Exception {
		long id = approved("즉시 반영 모임");
		edit(creatorSession, id, "즉시 반영 모임 새 이름", "온라인").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"))
			.andExpect(jsonPath("$.name").value("즉시 반영 모임 새 이름"));
	}

	@Test
	void demoReadsApprovedIntroductionsOnlyAndCannotJoin() throws Exception {
		long id = approved("데모가 볼 모임");
		long pending = id(apply("데모가 못 볼 모임"));
		MockHttpSession demo = startDemo();
		mockMvc.perform(get("/api/clubs/{id}", id).session(demo)).andExpect(status().isOk())
			.andExpect(jsonPath("$.intro").value("함께 해요"))
			.andExpect(jsonPath("$.canJoin").value(false));
		mockMvc.perform(get("/api/clubs/{id}", pending).session(demo)).andExpect(status().isNotFound());
		join(demo, id).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CLUB_FORBIDDEN"));
	}

	@Test
	void deletingAClubRemovesMembersAndNotificationsAndOnlyCreatorOrOperatorDeletes() throws Exception {
		long id = approved("지울 모임");
		join(memberSession, id).andExpect(status().isOk());
		mockMvc.perform(withXsrf(delete("/api/clubs/{id}", id), memberSession)).andExpect(status().isForbidden());

		mockMvc.perform(withXsrf(delete("/api/clubs/{id}", id), operatorSession)).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select count(*) from club_member where club_id = ?", Long.class, id)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from notification where club_id = ?", Long.class, id)).isZero();
		mockMvc.perform(get("/api/clubs/{id}", id).session(creatorSession)).andExpect(status().isNotFound());
	}

	@Test
	void withdrawalRemovesMembershipsAndDeletesTheCreatorsClubs() throws Exception {
		long first = approved("탈퇴자 모임");
		long second = approved("둘째 모임");
		Account joiner = newAccount("club-joiner", Role.PARTICIPANT);
		MockHttpSession joinerSession = login(joiner);
		join(joinerSession, first).andExpect(status().isOk());
		join(joinerSession, second).andExpect(status().isOk());

		withdraw(joinerSession);
		assertThat(jdbc.queryForObject("select count(*) from club_member where account_id = ?", Long.class, joiner.getId()))
			.isZero();
		withdraw(creatorSession);
		assertThat(jdbc.queryForObject("select count(*) from club where id in (?, ?)", Long.class, first, second)).isZero();
	}

	// --- helpers ---

	private void withdraw(MockHttpSession session) throws Exception {
		mockMvc.perform(withXsrf(post("/api/me/withdraw"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(PASSWORD))).andExpect(status().isNoContent());
	}

	private ResultActions apply(String name) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/clubs"), creatorSession).contentType(MediaType.APPLICATION_JSON)
			.content(body(name)));
	}

	private long approved(String name) throws Exception {
		long id = id(apply(name));
		approve(id, version(id)).andExpect(status().isOk());
		return id;
	}

	private static String body(String name) {
		return "{\"name\":\"%s\",\"intro\":\"함께 해요\",\"place\":\"강남\",\"mode\":\"OFFLINE\"}".formatted(name);
	}

	private ResultActions edit(MockHttpSession session, long id, String name, String place) throws Exception {
		return mockMvc.perform(withXsrf(patch("/api/clubs/{id}", id), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"%s\",\"intro\":\"함께 해요\",\"place\":\"%s\",\"mode\":\"MIXED\"}".formatted(name, place)));
	}

	private ResultActions approve(long id, long version) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/operator/clubs/{id}/approve", id), operatorSession)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + version + "}"));
	}

	private ResultActions reject(long id, long version, String reason) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/operator/clubs/{id}/reject", id), operatorSession)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":%d,\"reason\":\"%s\"}".formatted(version, reason)));
	}

	private ResultActions join(MockHttpSession session, long id) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/clubs/{id}/join", id), session));
	}

	private long version(long id) {
		return jdbc.queryForObject("select version from club where id = ?", Long.class, id);
	}

	private long notifications(Account account, String type) {
		return jdbc.queryForObject("select count(*) from notification where recipient_account_id = ? and type = ?",
				Long.class, account.getId(), type);
	}

	private static long id(ResultActions result) throws Exception {
		String json = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		return ((Number) JsonPath.read(json, "$.id")).longValue();
	}

	private MockHttpSession startDemo() throws Exception {
		String ip = "100.67." + ThreadLocalRandom.current().nextInt(1, 255) + "." + ThreadLocalRandom.current().nextInt(1, 255);
		return (MockHttpSession) mockMvc.perform(withXsrf(post("/api/demo/start"), null).with(request -> {
			request.setRemoteAddr(ip);
			return request;
		})).andExpect(status().isCreated()).andReturn().getRequest().getSession(false);
	}

}

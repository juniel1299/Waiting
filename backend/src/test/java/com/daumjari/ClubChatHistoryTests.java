package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * 채팅 이전 기록: before(메시지 id) 커서로 과거를 거꾸로 읽는다. 이어 받기 cursor(seq)는 건드리지 않아 읽음 위치가 뒤로 가지 않는다.
 * 120건에서 여러 페이지를 읽는 동안 새 메시지가 와도 처음 조회 + 과거 페이지 + 이어 받기를 합치면 빠짐·중복이 없다.
 * 모임 탈퇴 직후 403, 계정 정지·탈퇴 직후 401(세션 무효).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ClubChatHistoryTests extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	MockHttpSession creator;

	Account memberAccount;

	MockHttpSession member;

	long club;

	@BeforeEach
	void setUp() throws Exception {
		Account creatorAccount = newAccount("hi-creator", Role.PARTICIPANT);
		creator = login(creatorAccount);
		memberAccount = newAccount("hi-member", Role.PARTICIPANT);
		member = login(memberAccount);
		club = ClubFixtures.approvedClub(this, creator, login(newAccount("hi-op", Role.OPERATOR)), "기록 모임");
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/join", club), member)).andExpect(status().is2xxSuccessful());
		// 속도 제한을 거치지 않게 과거 메시지 120건은 직접 넣는다(보내기 경로와 같은 seq 시퀀스)
		for (int i = 1; i <= 120; i++) {
			jdbc.update("insert into club_chat_message (club_id, author_account_id, body, seq, created_at) "
					+ "values (?, ?, ?, nextval('club_chat_seq'), now() - make_interval(mins => ?))", club, creatorAccount.getId(), "옛 메시지 " + i,
					200 - i);
		}
	}

	@Test
	void pagesBackwardsWhileNewMessagesArriveWithoutGapsOrDuplicates() throws Exception {
		String first = read("");
		List<Long> firstIds = ids(first);
		assertThat(firstIds).hasSize(50).isSorted();
		assertThat((Boolean) JsonPath.read(first, "$.hasMoreBefore")).isTrue();
		long cursor = ((Number) JsonPath.read(first, "$.cursor")).longValue();

		Set<Long> seen = new TreeSet<>(firstIds);
		List<Long> sent = new ArrayList<>();
		long before = firstIds.get(0);
		boolean more = true;
		int pages = 0;
		while (more) {
			// 과거를 읽는 사이사이 새 메시지가 오고, 이어 받기도 돈다
			sent.add(idOf(send(creator, "새 메시지 " + pages)));
			String older = read("?before=" + before);
			List<Long> olderIds = ids(older);
			assertThat(olderIds).isSorted().allMatch(id -> id < firstIds.get(0));
			assertThat(older).doesNotContain("\"cursor\"");
			seen.addAll(olderIds);
			more = JsonPath.read(older, "$.hasMore");
			if (!olderIds.isEmpty()) {
				before = olderIds.get(0);
			}
			String changes = read("?after=" + cursor);
			seen.addAll(ids(changes));
			cursor = ((Number) JsonPath.read(changes, "$.cursor")).longValue();
			pages++;
		}
		assertThat(pages).isEqualTo(2);
		List<Long> all = jdbc.queryForList("select id from club_chat_message where club_id = ? order by id", Long.class, club);
		assertThat(all).hasSize(120 + sent.size());
		assertThat(seen).containsExactlyElementsOf(all);
	}

	@Test
	void historyKeepsDeletionMarksAndRespectsPageSize() throws Exception {
		long oldest = jdbc.queryForObject("select min(id) from club_chat_message where club_id = ?", Long.class, club);
		jdbc.update("update club_chat_message set body = null, deleted_at = now(), seq = nextval('club_chat_seq') where id = ?", oldest);
		String page = read("?before=" + (oldest + 5) + "&limit=10");
		assertThat(ids(page)).containsExactly(oldest, oldest + 1, oldest + 2, oldest + 3, oldest + 4);
		assertThat((Boolean) JsonPath.read(page, "$.items[0].deleted")).isTrue();
		assertThat((Object) JsonPath.read(page, "$.items[0].body")).isNull();
		assertThat((Boolean) JsonPath.read(page, "$.hasMore")).isFalse();

		assertThat(ids(read("?before=" + (oldest + 100) + "&limit=500"))).hasSize(100);
		assertThat(ids(read("?before=" + (oldest + 100) + "&limit=7"))).hasSize(7);
		mockMvc.perform(get("/api/clubs/{id}/chat", club).session(member).param("before", "10").param("after", "1"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void formerMembersAndSuspendedOrWithdrawnAccountsCannotReadHistory() throws Exception {
		long newest = jdbc.queryForObject("select max(id) from club_chat_message where club_id = ?", Long.class, club);
		mockMvc.perform(get("/api/clubs/{id}/chat", club).session(member).param("before", String.valueOf(newest))).andExpect(status().isOk());
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/leave", club), member)).andExpect(status().is2xxSuccessful());
		mockMvc.perform(get("/api/clubs/{id}/chat", club).session(member).param("before", String.valueOf(newest)))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CLUB_MEMBERS_ONLY"));

		Account suspended = newAccount("hi-suspended", Role.PARTICIPANT);
		MockHttpSession suspendedSession = login(suspended);
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/join", club), suspendedSession)).andExpect(status().is2xxSuccessful());
		jdbc.update("update account set suspended_at = now() where id = ?", suspended.getId());
		mockMvc.perform(get("/api/clubs/{id}/chat", club).session(suspendedSession).param("before", String.valueOf(newest)))
			.andExpect(status().isUnauthorized());

		Account leaver = newAccount("hi-leaver", Role.PARTICIPANT);
		MockHttpSession leaverSession = login(leaver);
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/join", club), leaverSession)).andExpect(status().is2xxSuccessful());
		mockMvc.perform(withXsrf(post("/api/me/withdraw"), leaverSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(PASSWORD))).andExpect(status().isNoContent());
		mockMvc.perform(get("/api/clubs/{id}/chat", club).session(leaverSession).param("before", String.valueOf(newest)))
			.andExpect(status().isUnauthorized());
	}

	private String read(String query) throws Exception {
		return mockMvc.perform(get("/api/clubs/" + club + "/chat" + query).session(member)).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
	}

	private org.springframework.test.web.servlet.ResultActions send(MockHttpSession s, String body) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/clubs/{id}/chat", club), s).contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"%s\"}".formatted(body))).andExpect(status().isCreated());
	}

	private static long idOf(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
		return ((Number) JsonPath.read(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
	}

	private static List<Long> ids(String json) {
		List<Number> raw = JsonPath.read(json, "$.items[*].id");
		return raw.stream().map(Number::longValue).toList();
	}

}

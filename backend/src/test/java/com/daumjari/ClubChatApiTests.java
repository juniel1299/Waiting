package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
 * 확장 6단계: 모임 채팅. 승인된 모임의 회원만 읽고 쓴다(요청마다 가입 재확인 → 탈퇴·내보내기 직후 403).
 * 1000자 제한, 계정별 전송 속도 제한(기본 10초 5건), 본인·모임 운영자 삭제는 내용을 지우고 표시만 남긴다.
 * 증분 조회는 cursor(변경 순번) 이후의 새 메시지·삭제를 함께 준다. 모임이 지워지면 메시지도 지워진다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ClubChatApiTests extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	MockHttpSession creator;

	MockHttpSession member;

	Account memberAccount;

	long club;

	@BeforeEach
	void setUp() throws Exception {
		creator = login(newAccount("ch-creator", Role.PARTICIPANT));
		memberAccount = newAccount("ch-member", Role.PARTICIPANT);
		member = login(memberAccount);
		MockHttpSession operator = login(newAccount("ch-op", Role.OPERATOR));
		String created = mockMvc.perform(withXsrf(post("/api/clubs"), creator).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"채팅 모임\",\"intro\":\"소개\",\"place\":\"온라인\",\"mode\":\"ONLINE\"}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		club = ((Number) JsonPath.read(created, "$.id")).longValue();
		mockMvc.perform(withXsrf(post("/api/operator/clubs/{id}/approve", club), operator).contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + JsonPath.read(created, "$.version") + "}")).andExpect(status().isOk());
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/join", club), member)).andExpect(status().is2xxSuccessful());
	}

	@Test
	void membersExchangeMessagesAndIncrementalPollsReturnOnlyNewOnes() throws Exception {
		send(creator, "안녕하세요").andExpect(status().isCreated()).andExpect(jsonPath("$.body").value("안녕하세요"))
			.andExpect(jsonPath("$.mine").value(true));
		String first = read(member, null);
		assertThat(JsonPath.<List<String>>read(first, "$.items[*].body")).containsExactly("안녕하세요");
		long cursor = ((Number) JsonPath.read(first, "$.cursor")).longValue();
		assertThat(JsonPath.<String>read(first, "$.items[0].author.label")).isNotBlank();
		assertThat(first).doesNotContain("loginId");

		send(member, "반가워요").andExpect(status().isCreated());
		send(creator, "  여러 줄\n메시지  ").andExpect(jsonPath("$.body").value("여러 줄\n메시지"));
		String next = read(member, cursor);
		assertThat(JsonPath.<List<String>>read(next, "$.items[*].body")).containsExactly("반가워요", "여러 줄\n메시지");
		long cursor2 = ((Number) JsonPath.read(next, "$.cursor")).longValue();
		assertThat(cursor2).isGreaterThan(cursor);
		assertThat(JsonPath.<List<Object>>read(read(member, cursor2), "$.items")).isEmpty();
	}

	@Test
	void onlyCurrentMembersOfApprovedClubsCanReadOrWrite() throws Exception {
		MockHttpSession outsider = login(newAccount("ch-out", Role.PARTICIPANT));
		mockMvc.perform(get("/api/clubs/{id}/chat", club).session(outsider)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CLUB_MEMBERS_ONLY"));
		send(outsider, "몰래").andExpect(status().isForbidden());
		mockMvc.perform(get("/api/clubs/{id}/chat", club)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/clubs/{id}/chat", 99999999L).session(member)).andExpect(status().isNotFound());

		// 탈퇴 직후: 이미 연 화면의 다음 요청부터 막힌다
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/leave", club), member)).andExpect(status().is2xxSuccessful());
		mockMvc.perform(get("/api/clubs/{id}/chat", club).session(member)).andExpect(status().isForbidden());
		send(member, "나간 뒤").andExpect(status().isForbidden());

		// 내보내기(회원 행 삭제) 직후도 같다
		MockHttpSession another = login(newAccount("ch-another", Role.PARTICIPANT));
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/join", club), another)).andExpect(status().is2xxSuccessful());
		send(another, "들어왔어요").andExpect(status().isCreated());
		jdbc.update("delete from club_member where club_id = ? and account_id = (select account_id from club_member "
				+ "where club_id = ? order by joined_at desc limit 1)", club, club);
		send(another, "내보내진 뒤").andExpect(status().isForbidden());
		mockMvc.perform(get("/api/clubs/{id}/chat", club).session(another)).andExpect(status().isForbidden());
	}

	@Test
	void messagesAreLengthCheckedAndRateLimitedPerAccount() throws Exception {
		send(member, "   ").andExpect(status().isBadRequest());
		send(member, "가".repeat(1001)).andExpect(status().isBadRequest());
		send(member, "가".repeat(1000)).andExpect(status().isCreated());
		for (int i = 0; i < 4; i++) {
			send(member, "연속 " + i).andExpect(status().isCreated());
		}
		send(member, "너무 빨라").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("CHAT_TOO_FAST"));
		send(creator, "다른 계정은 괜찮다").andExpect(status().isCreated());
	}

	@Test
	void authorsAndClubManagersDeleteLeavingOnlyAMarkAndPollsSeeTheDeletion() throws Exception {
		long mine = idOf(send(member, "지울 내 메시지"));
		long others = idOf(send(creator, "운영자 메시지"));
		long cursor = ((Number) JsonPath.read(read(member, null), "$.cursor")).longValue();

		mockMvc.perform(withXsrf(delete("/api/clubs/{id}/chat/{m}", club, others), member)).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/clubs/{id}/chat/{m}", club, mine), member)).andExpect(status().isNoContent());
		mockMvc.perform(withXsrf(delete("/api/clubs/{id}/chat/{m}", club, mine), member)).andExpect(status().isNoContent());
		long memberMsg = idOf(send(member, "운영자가 지울 메시지"));
		mockMvc.perform(withXsrf(delete("/api/clubs/{id}/chat/{m}", club, memberMsg), creator)).andExpect(status().isNoContent());

		String changes = read(member, cursor);
		assertThat(JsonPath.<List<Integer>>read(changes, "$.items[?(@.deleted == true)].id")).contains((int) mine, (int) memberMsg);
		assertThat(JsonPath.<List<Object>>read(changes, "$.items[?(@.deleted == true)].body")).containsOnlyNulls();
		assertThat(jdbc.queryForObject("select body is null and deleted_at is not null from club_chat_message where id = ?",
				Boolean.class, mine)).isTrue();
	}

	@Test
	void deletingTheClubDeletesItsMessages() throws Exception {
		send(member, "사라질 메시지").andExpect(status().isCreated());
		mockMvc.perform(withXsrf(delete("/api/clubs/{id}", club), creator)).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select count(*) from club_chat_message where club_id = ?", Long.class, club)).isZero();
	}

	private ResultActions send(MockHttpSession s, String body) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/clubs/{id}/chat", club), s).contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"%s\"}".formatted(body.replace("\n", "\\n"))));
	}

	private String read(MockHttpSession s, Long after) throws Exception {
		var request = get("/api/clubs/{id}/chat", club).session(s);
		if (after != null) {
			request = request.param("after", String.valueOf(after));
		}
		return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private static long idOf(ResultActions actions) throws Exception {
		return ((Number) JsonPath.read(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
	}

}

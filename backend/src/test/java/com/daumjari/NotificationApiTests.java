package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import com.daumjari.studysession.StudySession;
import com.jayway.jsonpath.JsonPath;

/**
 * 커뮤니티 3단계: 사이트 알림함.
 * 승격 알림(취소·정원 증가), 글·댓글·답글 멘션 알림(자기 멘션 제외, 수정 때는 새로 추가된 사람만),
 * 읽지 않은 수·목록·하나 읽기·모두 읽기, 이동 전 현재 권한 확인, 대상이 지워지면 알림도 지워진다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class NotificationApiTests extends ApiTestSupport {

	static final Instant STARTS_AT = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);

	@Autowired
	JdbcTemplate jdbc;

	Account writer;

	MockHttpSession writerSession;

	Account reader;

	MockHttpSession readerSession;

	@BeforeEach
	void setUp() throws Exception {
		writer = newAccount("ntf-writer", Role.PARTICIPANT);
		writerSession = login(writer);
		reader = newAccount("ntf-reader", Role.PARTICIPANT);
		readerSession = login(reader);
	}

	@Test
	void mentionInAPostNotifiesTheMentionedButNotTheAuthor() throws Exception {
		long post = writePost(writerSession, "@[나#" + writer.getId() + "] 와 @[읽는이#" + reader.getId() + "] 에게");

		mockMvc.perform(get("/api/me/notifications").session(readerSession))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unreadCount").value(1))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].type").value("MENTION"))
			.andExpect(jsonPath("$.items[0].read").value(false))
			.andExpect(jsonPath("$.items[0].actor.label").value("회원" + writer.getId()))
			.andExpect(jsonPath("$.items[0].postTitle").value("알림 글"))
			.andExpect(jsonPath("$.items[0].onComment").value(false));
		unread(writerSession, 0);
		assertThat(count("select count(*) from notification where post_id = ?", post)).isEqualTo(1);
	}

	@Test
	void editingKeepsExistingMentionsAndNotifiesOnlyNewlyAddedPeople() throws Exception {
		Account third = newAccount("ntf-third", Role.PARTICIPANT);
		long post = writePost(writerSession, "@[읽는이#" + reader.getId() + "]");

		mockMvc.perform(withXsrf(patch("/api/posts/{id}", post), writerSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"알림 글\",\"body\":\"@[읽는이#%d] @[셋째#%d] 수정\"}".formatted(reader.getId(), third.getId())))
			.andExpect(status().isOk());

		unread(readerSession, 1);
		unread(login(third), 1);
		assertThat(count("select count(*) from notification where post_id = ?", post)).isEqualTo(2);
	}

	@Test
	void commentAndReplyMentionsNotifyAndDeletingTheTargetRemovesTheNotification() throws Exception {
		long post = writePost(writerSession, "본문");
		long parent = comment(writerSession, post, null, "@[읽는이#" + reader.getId() + "] 댓글");
		long reply = comment(writerSession, post, parent, "@[읽는이#" + reader.getId() + "] 답글");
		mockMvc.perform(get("/api/me/notifications").session(readerSession))
			.andExpect(jsonPath("$.unreadCount").value(2))
			.andExpect(jsonPath("$.items[0].onComment").value(true));

		mockMvc.perform(withXsrf(delete("/api/comments/{id}", reply), writerSession)).andExpect(status().isNoContent());
		unread(readerSession, 1);
		mockMvc.perform(withXsrf(delete("/api/posts/{id}", post), writerSession)).andExpect(status().isNoContent());
		unread(readerSession, 0);
		assertThat(count("select count(*) from notification where recipient_account_id = ?", reader.getId())).isZero();
	}

	@Test
	void readOneReadAllAndOpenWithAnAnchorToTheComment() throws Exception {
		long post = writePost(writerSession, "@[읽는이#" + reader.getId() + "]");
		long c = comment(writerSession, post, null, "@[읽는이#" + reader.getId() + "]");
		long[] ids = notificationIds(readerSession);

		mockMvc.perform(withXsrf(post("/api/me/notifications/{id}/open", ids[0]), readerSession))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.href").value("/board/" + post + "#comment-" + c));
		unread(readerSession, 1);
		mockMvc.perform(withXsrf(post("/api/me/notifications/{id}/read", ids[1]), readerSession))
			.andExpect(status().isNoContent());
		unread(readerSession, 0);

		writePost(writerSession, "@[읽는이#" + reader.getId() + "] 하나 더");
		unread(readerSession, 1);
		mockMvc.perform(withXsrf(post("/api/me/notifications/read-all"), readerSession)).andExpect(status().isNoContent());
		mockMvc.perform(get("/api/me/notifications").session(readerSession))
			.andExpect(jsonPath("$.unreadCount").value(0))
			.andExpect(jsonPath("$.items[0].read").value(true));
	}

	@Test
	void someoneElsesNotificationIsNotFoundAndAnonymousIsUnauthorized() throws Exception {
		writePost(writerSession, "@[읽는이#" + reader.getId() + "]");
		long id = notificationIds(readerSession)[0];

		mockMvc.perform(withXsrf(post("/api/me/notifications/{id}/read", id), writerSession))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
		mockMvc.perform(withXsrf(post("/api/me/notifications/{id}/open", id), writerSession))
			.andExpect(status().isNotFound());
		unread(readerSession, 1);
		mockMvc.perform(get("/api/me/notifications/unread-count")).andExpect(status().isUnauthorized());
	}

	@Test
	void cancellationPromotesTheWaiterWithOneNotificationThatOpensTheSession() throws Exception {
		StudySession session = newSession("승격 회차", STARTS_AT, 1);
		long held = idOf(mockMvc.perform(withXsrf(post("/api/sessions/{id}/reservations", session.getId()), writerSession))
			.andExpect(status().isCreated()));
		long waiting = idOf(mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", session.getId()), readerSession))
			.andExpect(status().isCreated()));

		mockMvc.perform(withXsrf(post("/api/reservations/{id}/cancel", held), writerSession)).andExpect(status().isOk());
		mockMvc.perform(withXsrf(post("/api/reservations/{id}/cancel", held), writerSession)).andExpect(status().isOk());

		assertThat(count("select count(*) from notification where event_key = ?", "PROMOTED:" + waiting)).isEqualTo(1);
		mockMvc.perform(get("/api/me/notifications").session(readerSession))
			.andExpect(jsonPath("$.unreadCount").value(1))
			.andExpect(jsonPath("$.items[0].type").value("PROMOTED"))
			.andExpect(jsonPath("$.items[0].sessionTitle").value("승격 회차"));
		long id = notificationIds(readerSession)[0];
		mockMvc.perform(withXsrf(post("/api/me/notifications/{id}/open", id), readerSession))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.href").value("/sessions/" + session.getId()));
	}

	@Test
	void capacityIncreasePromotesWithNotificationsAndDeletingTheSessionRemovesThem() throws Exception {
		long session = idOf(mockMvc.perform(withXsrf(post("/api/sessions"), writerSession)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"title":"정원 회차","startsAt":"%s","location":"강남","capacity":1}
					""".formatted(STARTS_AT))).andExpect(status().isCreated()));
		MockHttpSession otherSession = login(newAccount("ntf-other", Role.PARTICIPANT));
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/reservations", session), otherSession))
			.andExpect(status().isCreated());
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", session), readerSession))
			.andExpect(status().isCreated());

		mockMvc.perform(withXsrf(patch("/api/sessions/{id}/capacity", session), writerSession)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"capacity\":2}")).andExpect(status().isOk());
		unread(readerSession, 1);

		mockMvc.perform(withXsrf(delete("/api/sessions/{id}", session), writerSession)).andExpect(status().isNoContent());
		unread(readerSession, 0);
	}

	@Test
	void withdrawalRemovesTheWithdrawnMembersNotifications() throws Exception {
		writePost(writerSession, "@[읽는이#" + reader.getId() + "]");

		mockMvc.perform(withXsrf(post("/api/me/withdraw"), readerSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(PASSWORD))).andExpect(status().isNoContent());

		assertThat(count("select count(*) from notification where recipient_account_id = ?", reader.getId())).isZero();
	}

	// --- helpers ---

	private long writePost(MockHttpSession session, String body) throws Exception {
		return idOf(mockMvc.perform(withXsrf(post("/api/boards/community/posts"), session)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"알림 글\",\"body\":\"%s\"}".formatted(body))).andExpect(status().isCreated()));
	}

	private long comment(MockHttpSession session, long post, Long parentId, String body) throws Exception {
		String json = parentId == null ? "{\"body\":\"%s\"}".formatted(body)
				: "{\"body\":\"%s\",\"parentId\":%d}".formatted(body, parentId);
		return idOf(mockMvc.perform(withXsrf(post("/api/posts/{id}/comments", post), session)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json)).andExpect(status().isCreated()));
	}

	private void unread(MockHttpSession session, int expected) throws Exception {
		mockMvc.perform(get("/api/me/notifications/unread-count").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unreadCount").value(expected));
	}

	/** 최신순 알림 id. */
	private long[] notificationIds(MockHttpSession session) throws Exception {
		String json = mockMvc.perform(get("/api/me/notifications").session(session))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		List<Number> ids = JsonPath.read(json, "$.items[*].id");
		return ids.stream().mapToLong(Number::longValue).toArray();
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private static long idOf(ResultActions result) throws Exception {
		Number id = JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");
		return id.longValue();
	}

}

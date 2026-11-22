package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;
import com.daumjari.reservation.Reservation;
import com.daumjari.studysession.StudySession;
import com.jayway.jsonpath.JsonPath;

/**
 * 회차 검색: 전체 회차·내가 만든 회차 공통. 제목·장소 부분 일치, 서울 기준 날짜 범위(종료일 포함), 모집 상태 필터,
 * 정렬 7종(보조 정렬 id), 서버 페이지네이션. DB 를 테스트끼리 공유하므로 테스트마다 고유 검색어로 자기 회차만 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SessionSearchApiTests extends ApiTestSupport {

	static final Instant NOW = Instant.now();

	@Autowired
	JdbcTemplate jdbc;

	String token;

	MockHttpSession viewer;

	Account viewerAccount;

	@BeforeEach
	void setUp() throws Exception {
		token = "srch" + UUID.randomUUID().toString().substring(0, 8);
		viewerAccount = newAccount("search-viewer", Role.PARTICIPANT);
		viewer = login(viewerAccount);
	}

	@Test
	void keywordMatchesTitleOrLocationPartiallyAndCaseInsensitively() throws Exception {
		save(token + " Spring 스터디", inDays(1), "강남", 3);
		save("알고리즘 " + token, inDays(2), "판교", 3);
		save("독서 모임", inDays(3), "잠실 " + token.toUpperCase(), 3);

		assertThat(titles("q=" + token)).hasSize(3);
		assertThat(titles("q=" + token + "&field=TITLE")).containsExactly(token + " Spring 스터디", "알고리즘 " + token);
		assertThat(titles("q=" + token + "&field=LOCATION")).containsExactly("독서 모임");
		assertThat(titles("q=" + token + " spring")).containsExactly(token + " Spring 스터디");
		assertThat(titles("q=  " + token + "  &field=TITLE")).hasSize(2);
	}

	@Test
	void likeWildcardsInTheKeywordAreMatchedLiterally() throws Exception {
		save(token + " 100% 참여", inDays(1), "강남", 3);
		save(token + " 1000 참여", inDays(1), "강남", 3);
		save(token + " a_b", inDays(1), "강남", 3);
		save(token + " axb", inDays(1), "강남", 3);
		save(token + " c\\d", inDays(1), "강남", 3);

		assertThat(titles("q=" + token + " 100%")).containsExactly(token + " 100% 참여");
		assertThat(titles("q=" + token + " a_b")).containsExactly(token + " a_b");
		assertThat(titles("q=" + token + " c\\d")).containsExactly(token + " c\\d");
	}

	@Test
	void dateRangeUsesSeoulDaysAndIncludesTheEndDate() throws Exception {
		// 서울 2031-03-10 00:00 = UTC 03-09 15:00, 서울 03-12 23:59 = UTC 03-12 14:59
		save("시작일 첫 순간", Instant.parse("2031-03-09T15:00:00Z"), token, 3);
		save("시작일 전날 끝", Instant.parse("2031-03-09T14:59:00Z"), token, 3);
		save("종료일 마지막", Instant.parse("2031-03-12T14:59:00Z"), token, 3);
		save("종료일 다음날", Instant.parse("2031-03-12T15:00:00Z"), token, 3);

		assertThat(titles("q=" + token + "&from=2031-03-10&to=2031-03-12")).containsExactly("시작일 첫 순간", "종료일 마지막");
		assertThat(titles("q=" + token + "&from=2031-03-13")).containsExactly("종료일 다음날");
		assertThat(titles("q=" + token + "&to=2031-03-09")).containsExactly("시작일 전날 끝");
		assertThat(titles("q=" + token + "&from=2031-03-12&to=2031-03-12")).containsExactly("종료일 마지막");
	}

	@Test
	void statusFiltersDefaultToUpcomingSessions() throws Exception {
		save("빈자리", inDays(1), token, 2);
		StudySession full = save("마감", inDays(2), token, 1);
		confirmDirectly(full, newAccount("search-p", Role.PARTICIPANT));
		StudySession queued = save("대기자 있음", inDays(3), token, 2);
		reservations.save(Reservation.waitlist(queued, newAccount("search-w", Role.PARTICIPANT), Instant.now()));
		save("끝난 회차", NOW.minus(1, ChronoUnit.HOURS), token, 2);

		assertThat(titles("q=" + token)).containsExactly("빈자리", "마감", "대기자 있음");
		assertThat(titles("q=" + token + "&status=UPCOMING")).containsExactly("빈자리", "마감", "대기자 있음");
		assertThat(titles("q=" + token + "&status=OPEN")).containsExactly("빈자리");
		assertThat(titles("q=" + token + "&status=WAITLIST")).containsExactly("마감", "대기자 있음");
		assertThat(titles("q=" + token + "&status=ENDED")).containsExactly("끝난 회차");
		assertThat(titles("q=" + token + "&status=ALL")).containsExactly("끝난 회차", "빈자리", "마감", "대기자 있음");
	}

	@Test
	void sortsByEachOptionWithIdAsTheTieBreaker() throws Exception {
		Instant t = inDays(5);
		StudySession b = save("나 회차", t, token, 5); // 남은 5, 확정 0
		StudySession a = save("가 회차", t.plus(1, ChronoUnit.HOURS), token, 3); // 확정 1 → 남은 2
		confirmDirectly(a, newAccount("search-s", Role.PARTICIPANT));
		StudySession c = save("다 회차", t, token, 5); // b 와 시작·남은 자리·확정 같음 → id 로
		setCreatedAt(c, NOW.minus(3, ChronoUnit.DAYS));
		setCreatedAt(b, NOW.minus(2, ChronoUnit.DAYS));
		setCreatedAt(a, NOW.minus(1, ChronoUnit.DAYS));

		assertThat(titles("q=" + token + "&sort=SOON")).containsExactly("나 회차", "다 회차", "가 회차");
		assertThat(titles("q=" + token + "&sort=LATE")).containsExactly("가 회차", "나 회차", "다 회차");
		assertThat(titles("q=" + token + "&sort=NEWEST")).containsExactly("가 회차", "나 회차", "다 회차");
		assertThat(titles("q=" + token + "&sort=OLDEST")).containsExactly("다 회차", "나 회차", "가 회차");
		assertThat(titles("q=" + token + "&sort=SEATS")).containsExactly("나 회차", "다 회차", "가 회차");
		assertThat(titles("q=" + token + "&sort=POPULAR")).containsExactly("가 회차", "나 회차", "다 회차");
		assertThat(titles("q=" + token + "&sort=TITLE")).containsExactly("가 회차", "나 회차", "다 회차");
	}

	@Test
	void paginatesOnTheServerWithTotals() throws Exception {
		for (int i = 1; i <= 5; i++) {
			save("회차 " + i, inDays(i), token, 3);
		}

		mockMvc.perform(search("q=" + token + "&size=2&page=2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.page").value(2))
			.andExpect(jsonPath("$.size").value(2))
			.andExpect(jsonPath("$.totalElements").value(5))
			.andExpect(jsonPath("$.totalPages").value(3))
			.andExpect(jsonPath("$.items[0].title").value("회차 3"))
			.andExpect(jsonPath("$.items[1].title").value("회차 4"))
			.andExpect(jsonPath("$.items[0].canManage").value(false));
		assertThat(titles("q=" + token + "&size=2&page=3")).containsExactly("회차 5");
		mockMvc.perform(search("q=" + token + "&size=2&page=9"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0))
			.andExpect(jsonPath("$.totalElements").value(5));
	}

	@Test
	void mineScopeShowsOnlySessionsTheViewerCreated() throws Exception {
		StudySession mine = save("내 회차", inDays(1), token, 3);
		mine.assignCreator(viewerAccount.getId());
		sessions.save(mine);
		StudySession ended = save("내 끝난 회차", NOW.minus(1, ChronoUnit.DAYS), token, 3);
		ended.assignCreator(viewerAccount.getId());
		sessions.save(ended);
		save("남의 회차", inDays(1), token, 3);

		assertThat(titles("scope=mine")).containsExactly("내 회차");
		assertThat(titles("scope=mine&status=ALL&sort=LATE")).containsExactly("내 회차", "내 끝난 회차");
		mockMvc.perform(search("scope=mine"))
			.andExpect(jsonPath("$.items[0].canManage").value(true));
	}

	@Test
	void rejectsInvalidConditions() throws Exception {
		for (String query : List.of("status=NOPE", "sort=NOPE", "field=NOPE", "scope=NOPE", "from=2031-13-01",
				"from=2031-03-12&to=2031-03-10", "page=0", "page=x", "size=0", "size=51", "q=" + "가".repeat(101))) {
			mockMvc.perform(search(query))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
	}

	@Test
	void requiresLogin() throws Exception {
		mockMvc.perform(get("/api/sessions/search")).andExpect(status().isUnauthorized());
	}

	private StudySession save(String title, Instant startsAt, String location, int capacity) {
		return sessions.save(StudySession.create(title, startsAt, location, capacity, Instant.now()));
	}

	private void setCreatedAt(StudySession session, Instant at) {
		jdbc.update("update study_session set created_at = ? where id = ?", Timestamp.from(at), session.getId());
	}

	private static Instant inDays(int days) {
		return NOW.plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
	}

	/** query 는 a=b&c=d 형식의 인코딩하지 않은 값 (MockMvc 파라미터로 그대로 넣는다). */
	private MockHttpServletRequestBuilder search(String query) {
		MockHttpServletRequestBuilder request = get("/api/sessions/search").session(viewer);
		for (String pair : query.split("&")) {
			int i = pair.indexOf('=');
			if (i > 0) {
				request.param(pair.substring(0, i), pair.substring(i + 1));
			}
		}
		return request;
	}

	private List<String> titles(String query) throws Exception {
		String body = mockMvc.perform(search(query))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		return JsonPath.read(body, "$.items[*].title");
	}

}

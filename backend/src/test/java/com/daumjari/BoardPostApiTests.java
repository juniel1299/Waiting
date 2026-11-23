package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * 커뮤니티 1단계: 회원 게시판(회원·운영자 조회·작성, 데모 조회만, 비회원 불가)과 서비스 공지(누구나 조회, 운영자만 쓰기).
 * 작성자만 수정, 작성자·운영자(회원 게시판 전체) 삭제. 제목·본문 검색과 쪽. 본문은 원문 텍스트로 저장·응답(화면이 텍스트로만 그린다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class BoardPostApiTests extends ApiTestSupport {

	Account author;

	MockHttpSession authorSession;

	MockHttpSession otherSession;

	Account operator;

	MockHttpSession operatorSession;

	String token;

	@BeforeEach
	void setUp() throws Exception {
		author = newAccount("board-author", Role.PARTICIPANT);
		authorSession = login(author);
		otherSession = login(newAccount("board-other", Role.PARTICIPANT));
		operator = newAccount("board-op", Role.OPERATOR);
		operatorSession = login(operator);
		token = "brd" + UUID.randomUUID().toString().substring(0, 8);
	}

	@Test
	void memberWritesReadsEditsAndDeletesOwnCommunityPost() throws Exception {
		long id = idOf(write(authorSession, "community", token + " 첫 글", "줄1\n줄2").andExpect(status().isCreated())
			.andExpect(jsonPath("$.board").value("COMMUNITY"))
			.andExpect(jsonPath("$.author.label").value("회원" + author.getId()))
			.andExpect(jsonPath("$.edited").value(false))
			.andExpect(jsonPath("$.commentCount").value(0))
			.andExpect(jsonPath("$.canEdit").value(true))
			.andExpect(jsonPath("$.canDelete").value(true)));

		mockMvc.perform(get("/api/posts/{id}", id).session(otherSession))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.body").value("줄1\n줄2"))
			.andExpect(jsonPath("$.canEdit").value(false))
			.andExpect(jsonPath("$.canDelete").value(false));

		edit(authorSession, id, token + " 고친 글", "본문").andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value(token + " 고친 글"))
			.andExpect(jsonPath("$.edited").value(true))
			.andExpect(jsonPath("$.updatedAt").isNotEmpty());

		mockMvc.perform(withXsrf(delete("/api/posts/{id}", id), authorSession)).andExpect(status().isNoContent());
		mockMvc.perform(get("/api/posts/{id}", id).session(authorSession))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
	}

	@Test
	void othersCannotEditOrDeleteButOperatorCanDeleteAnyCommunityPost() throws Exception {
		long id = idOf(write(authorSession, "community", token, "본문").andExpect(status().isCreated()));

		edit(otherSession, id, "탈취", "x").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_POST_AUTHOR"));
		mockMvc.perform(withXsrf(delete("/api/posts/{id}", id), otherSession))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_POST_AUTHOR"));
		// 운영자도 남의 글을 고칠 수는 없다 (삭제만)
		edit(operatorSession, id, "운영자 수정", "x").andExpect(status().isForbidden());
		mockMvc.perform(get("/api/posts/{id}", id).session(operatorSession))
			.andExpect(jsonPath("$.canEdit").value(false))
			.andExpect(jsonPath("$.canDelete").value(true));
		mockMvc.perform(withXsrf(delete("/api/posts/{id}", id), operatorSession)).andExpect(status().isNoContent());
	}

	@Test
	void anonymousCannotReachTheCommunityBoardButCanReadNotices() throws Exception {
		long community = idOf(write(authorSession, "community", token, "회원 글").andExpect(status().isCreated()));
		long notice = idOf(write(operatorSession, "notice", token + " 공지", "모두에게").andExpect(status().isCreated())
			.andExpect(jsonPath("$.author.label").value("운영자")));

		mockMvc.perform(get("/api/boards/community/posts")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/posts/{id}", community))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(get("/api/boards/notice/posts").param("q", token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.canWrite").value(false));
		mockMvc.perform(get("/api/posts/{id}", notice))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.body").value("모두에게"))
			.andExpect(jsonPath("$.canDelete").value(false));
		mockMvc.perform(withXsrf(post("/api/boards/notice/posts"), null).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"t\",\"body\":\"b\"}")).andExpect(status().isUnauthorized());
	}

	@Test
	void onlyOperatorsWriteEditAndDeleteNotices() throws Exception {
		write(authorSession, "notice", token, "회원 공지").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("BOARD_WRITE_FORBIDDEN"));
		long notice = idOf(write(operatorSession, "notice", token, "공지").andExpect(status().isCreated()));
		edit(authorSession, notice, "x", "y").andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/posts/{id}", notice), authorSession)).andExpect(status().isForbidden());

		// 공지는 서비스 운영자들이 함께 관리한다: 다른 운영자도 고치고 지울 수 있다
		MockHttpSession otherOperator = login(newAccount("board-op2", Role.OPERATOR));
		edit(otherOperator, notice, token + " 고침", "공지2").andExpect(status().isOk());
		mockMvc.perform(withXsrf(delete("/api/posts/{id}", notice), otherOperator)).andExpect(status().isNoContent());
	}

	@Test
	void demoVisitorsOnlyRead() throws Exception {
		long id = idOf(write(authorSession, "community", token, "본문").andExpect(status().isCreated()));
		MockHttpSession demo = startDemo();

		mockMvc.perform(get("/api/boards/community/posts").param("q", token).session(demo))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.canWrite").value(false));
		mockMvc.perform(get("/api/posts/{id}", id).session(demo)).andExpect(status().isOk());
		write(demo, "community", "데모 글", "x").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("BOARD_WRITE_FORBIDDEN"));
		mockMvc.perform(get("/api/boards/community/posts").session(authorSession))
			.andExpect(jsonPath("$.canWrite").value(true));
	}

	@Test
	void validatesTitleAndBodyAndStoresBodyAsPlainText() throws Exception {
		write(authorSession, "community", "  ", "본문").andExpect(status().isBadRequest());
		write(authorSession, "community", "가".repeat(101), "본문").andExpect(status().isBadRequest());
		write(authorSession, "community", "제목", "  ").andExpect(status().isBadRequest());
		write(authorSession, "community", "제목", "가".repeat(5001)).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/boards/nope/posts").session(authorSession)).andExpect(status().isNotFound());

		// 화면이 텍스트로만 그리므로 서버는 입력을 바꾸지 않고 그대로 돌려준다 (이스케이프는 화면 몫)
		String script = "<script>alert('x')</script><img src=x onerror=alert(1)>";
		long id = idOf(write(authorSession, "community", token + " " + script, script).andExpect(status().isCreated()));
		mockMvc.perform(get("/api/posts/{id}", id).session(authorSession))
			.andExpect(jsonPath("$.title").value(token + " " + script))
			.andExpect(jsonPath("$.body").value(script));
	}

	@Test
	void searchesTitleOrBodyNewestFirstWithServerPaging() throws Exception {
		write(authorSession, "community", token + " 스프링", "본문 a").andExpect(status().isCreated());
		write(authorSession, "community", "리액트", "본문에 " + token).andExpect(status().isCreated());
		write(authorSession, "community", token + " 마지막", "본문 c").andExpect(status().isCreated());
		write(operatorSession, "notice", token + " 공지", "공지").andExpect(status().isCreated());

		assertThat(titles("community", "q=" + token)).containsExactly(token + " 마지막", "리액트", token + " 스프링");
		assertThat(titles("community", "q=" + token + "&field=TITLE")).containsExactly(token + " 마지막", token + " 스프링");
		assertThat(titles("community", "q=" + token + "&field=BODY")).containsExactly("리액트");
		assertThat(titles("community", "q=" + token + "&size=2&page=2")).containsExactly(token + " 스프링");
		assertThat(titles("notice", "q=" + token)).containsExactly(token + " 공지");
		mockMvc.perform(search("community", "q=" + token + "&size=2"))
			.andExpect(jsonPath("$.totalElements").value(3))
			.andExpect(jsonPath("$.totalPages").value(2))
			.andExpect(jsonPath("$.items[0].body").doesNotExist())
			.andExpect(jsonPath("$.items[0].commentCount").value(0));
		for (String bad : List.of("field=NOPE", "page=0", "size=51")) {
			mockMvc.perform(search("community", bad)).andExpect(status().isBadRequest());
		}
	}

	@Test
	void withdrawnAuthorsPostsStayAndShowAnonymously() throws Exception {
		long id = idOf(write(authorSession, "community", token, "남는 글").andExpect(status().isCreated()));
		mockMvc.perform(withXsrf(post("/api/me/withdraw"), authorSession).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(PASSWORD))).andExpect(status().isNoContent());
		mockMvc.perform(get("/api/posts/{id}", id).session(otherSession))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.author.label").value("탈퇴한 회원"));
	}

	private ResultActions write(MockHttpSession session, String board, String title, String body) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/boards/{board}/posts", board), session)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json(title, body)));
	}

	private ResultActions edit(MockHttpSession session, long id, String title, String body) throws Exception {
		return mockMvc.perform(withXsrf(patch("/api/posts/{id}", id), session).contentType(MediaType.APPLICATION_JSON)
			.content(json(title, body)));
	}

	private static String json(String title, String body) {
		return "{\"title\":" + quote(title) + ",\"body\":" + quote(body) + "}";
	}

	private static String quote(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	private static long idOf(ResultActions result) throws Exception {
		Number id = JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");
		return id.longValue();
	}

	private MockHttpServletRequestBuilder search(String board, String query) {
		MockHttpServletRequestBuilder request = get("/api/boards/{board}/posts", board).session(authorSession);
		for (String pair : query.split("&")) {
			int i = pair.indexOf('=');
			request.param(pair.substring(0, i), pair.substring(i + 1));
		}
		return request;
	}

	private List<String> titles(String board, String query) throws Exception {
		String body = mockMvc.perform(search(board, query))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		return JsonPath.read(body, "$.items[*].title");
	}

	private MockHttpSession startDemo() throws Exception {
		String ip = "100.65." + ThreadLocalRandom.current().nextInt(1, 255) + "." + ThreadLocalRandom.current().nextInt(1, 255);
		return (MockHttpSession) mockMvc.perform(withXsrf(post("/api/demo/start"), null).with(request -> {
			request.setRemoteAddr(ip);
			return request;
		})).andExpect(status().isCreated()).andReturn().getRequest().getSession(false);
	}

}

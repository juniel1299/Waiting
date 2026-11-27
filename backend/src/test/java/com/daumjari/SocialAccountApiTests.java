package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;

/**
 * 확장 5단계: 카카오·네이버 계정 연결. 아이디·비밀번호로 로그인한 상태에서만 연결하고, 소셜 로그인은 연결된 계정에만 된다.
 * state 는 서버 세션에 일회용·10분. 위조·재사용·만료는 거부. 한 소셜 계정은 한 회원에만, 한 회원은 공급자마다 하나.
 * 해제는 비밀번호 재확인, 탈퇴하면 연결 행이 지워진다. 액세스 토큰은 저장하지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SocialAccountApiTests extends ApiTestSupport {

	@DynamicPropertySource
	static void oauth(DynamicPropertyRegistry registry) throws IOException {
		FakeOAuthServer.register(registry);
	}

	@Autowired
	JdbcTemplate jdbc;

	Account me;

	MockHttpSession session;

	@BeforeEach
	void setUp() throws Exception {
		me = newAccount("so-me", Role.PARTICIPANT);
		session = login(me);
	}

	@Test
	void providersAreListedAndLinkingRedirectsToTheProviderWithOneTimeState() throws Exception {
		mockMvc.perform(get("/api/auth/oauth/providers")).andExpect(status().isOk())
			.andExpect(jsonPath("$.kakao").value(true)).andExpect(jsonPath("$.naver").value(true));
		String location = start(session, "kakao", "link");
		Map<String, String> q = UriComponentsBuilder.fromUriString(location).build().getQueryParams().toSingleValueMap();
		assertThat(location).contains("/kakao/authorize");
		assertThat(q).containsEntry("client_id", "test-kakao-client").containsEntry("response_type", "code")
			.containsEntry("redirect_uri", "http://localhost:3100/api/auth/oauth/kakao/callback");
		assertThat(q.get("state")).hasSizeGreaterThanOrEqualTo(32);
		assertThat(location).doesNotContain("scope=").doesNotContain("secret");
	}

	@Test
	void linkingStoresOnlyTheProviderIdAndShowsInMySocialAccounts() throws Exception {
		String state = state(start(session, "kakao", "link"));
		assertThat(callback(session, "kakao", "u2001", state)).isEqualTo("/me/accounts?linked=kakao");
		assertThat(jdbc.queryForMap("select provider, provider_user_id from social_account where account_id = ?", me.getId()))
			.containsEntry("provider", "KAKAO").containsEntry("provider_user_id", "2001");
		mockMvc.perform(get("/api/me/social").session(session)).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].provider").value("KAKAO"))
			.andExpect(jsonPath("$.items[0].linkedAt").isNotEmpty())
			.andExpect(jsonPath("$.providers.naver").value(true));
		String naverState = state(start(session, "naver", "link"));
		assertThat(callback(session, "naver", "u2001", naverState)).isEqualTo("/me/accounts?linked=naver");
		assertThat(jdbc.queryForObject("select provider_user_id from social_account where account_id = ? and provider = 'NAVER'",
				String.class, me.getId())).isEqualTo("n-u2001");
		// 토큰을 저장할 자리가 없다
		assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'social_account' "
				+ "and column_name like '%token%'", Long.class)).isZero();
	}

	@Test
	void forgedReusedAndExpiredStatesAreRejected() throws Exception {
		start(session, "kakao", "link");
		assertThat(callback(session, "kakao", "u2101", "forged-state-value-0000000000000000")).isEqualTo("/me/accounts?error=OAUTH_STATE_INVALID");
		String state = state(start(session, "kakao", "link"));
		assertThat(callback(session, "kakao", "u2101", state)).isEqualTo("/me/accounts?linked=kakao");
		jdbc.update("delete from social_account where account_id = ?", me.getId());
		assertThat(callback(session, "kakao", "u2101", state)).isEqualTo("/me/accounts?error=OAUTH_STATE_INVALID");
		assertThat(count("select count(*) from social_account where account_id = ?", me.getId())).isZero();

		// 다른 공급자로 받은 state 를 쓰면 거부
		String naverState = state(start(session, "naver", "link"));
		assertThat(callback(session, "kakao", "u2101", naverState)).isEqualTo("/me/accounts?error=OAUTH_STATE_INVALID");

		// 10분이 지난 state
		String old = state(start(session, "kakao", "link"));
		@SuppressWarnings("unchecked")
		Map<String, Object> states = (Map<String, Object>) session.getAttribute("daumjari.oauth.states");
		states.replaceAll((k, v) -> v == null ? null : ((com.daumjari.auth.OAuthState) v).expiredCopy());
		assertThat(callback(session, "kakao", "u2101", old)).isEqualTo("/me/accounts?error=OAUTH_STATE_INVALID");

		// 공급자가 거부(사용자가 동의 취소)하거나 토큰 교환이 실패하면 연결하지 않는다
		String denied = state(start(session, "kakao", "link"));
		MvcResult r = mockMvc.perform(get("/api/auth/oauth/kakao/callback").session(session).param("error", "access_denied")
			.param("state", denied)).andExpect(status().isFound()).andReturn();
		assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/me/accounts?error=OAUTH_DENIED");
		assertThat(callback(session, "kakao", "bad", state(start(session, "kakao", "link")))).isEqualTo("/me/accounts?error=OAUTH_FAILED");
		assertThat(count("select count(*) from social_account where account_id = ?", me.getId())).isZero();
	}

	@Test
	void aSocialAccountBelongsToOneMemberAndAMemberHasOnePerProvider() throws Exception {
		assertThat(callback(session, "kakao", "u2201", state(start(session, "kakao", "link")))).isEqualTo("/me/accounts?linked=kakao");
		MockHttpSession other = login(newAccount("so-other", Role.PARTICIPANT));
		assertThat(callback(other, "kakao", "u2201", state(start(other, "kakao", "link")))).isEqualTo("/me/accounts?error=SOCIAL_ALREADY_LINKED");
		assertThat(callback(session, "kakao", "u2202", state(start(session, "kakao", "link")))).isEqualTo("/me/accounts?error=SOCIAL_PROVIDER_ALREADY_LINKED");
		assertThat(count("select count(*) from social_account where provider = 'KAKAO' and provider_user_id in ('2201', '2202')")).isEqualTo(1);
	}

	@Test
	void socialLoginOnlyWorksForLinkedActiveAccounts() throws Exception {
		assertThat(callback(session, "naver", "u2301", state(start(session, "naver", "link")))).isEqualTo("/me/accounts?linked=naver");

		MockHttpSession browser = new MockHttpSession();
		assertThat(callback(browser, "naver", "u2301", state(start(browser, "naver", "login")))).isEqualTo("/");
		mockMvc.perform(get("/api/auth/me").session(browser)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(me.getId()));

		MockHttpSession stranger = new MockHttpSession();
		assertThat(callback(stranger, "naver", "u2399", state(start(stranger, "naver", "login")))).isEqualTo("/login?error=SOCIAL_NOT_LINKED");
		mockMvc.perform(get("/api/auth/me").session(stranger)).andExpect(status().isUnauthorized());

		jdbc.update("update account set suspended_at = now() where id = ?", me.getId());
		MockHttpSession suspended = new MockHttpSession();
		assertThat(callback(suspended, "naver", "u2301", state(start(suspended, "naver", "login")))).isEqualTo("/login?error=ACCOUNT_SUSPENDED");
		mockMvc.perform(get("/api/auth/me").session(suspended)).andExpect(status().isUnauthorized());
	}

	@Test
	void linkingNeedsAPasswordLoginAndDemoCannotLink() throws Exception {
		MockHttpSession anonymous = new MockHttpSession();
		assertThat(start(anonymous, "kakao", "link")).isEqualTo("/login?error=LOGIN_REQUIRED");
		MockHttpSession demo = (MockHttpSession) mockMvc.perform(withXsrf(post("/api/demo/start"), null).with(r -> {
			r.setRemoteAddr("100.71.5." + (int) (Math.random() * 200 + 1));
			return r;
		})).andExpect(status().isCreated()).andReturn().getRequest().getSession(false);
		assertThat(start(demo, "kakao", "link")).isEqualTo("/me/accounts?error=SOCIAL_FORBIDDEN");
		mockMvc.perform(get("/api/auth/oauth/line/link/start").session(session)).andExpect(status().isNotFound());
	}

	@Test
	void unlinkingNeedsThePasswordAndWithdrawalRemovesLinks() throws Exception {
		callback(session, "kakao", "u2401", state(start(session, "kakao", "link")));
		mockMvc.perform(withXsrf(delete("/api/me/social/kakao"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"wrong-password\"}")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
		assertThat(count("select count(*) from social_account where account_id = ?", me.getId())).isEqualTo(1);
		mockMvc.perform(withXsrf(delete("/api/me/social/kakao"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(PASSWORD))).andExpect(status().isNoContent());
		assertThat(count("select count(*) from social_account where account_id = ?", me.getId())).isZero();
		mockMvc.perform(withXsrf(delete("/api/me/social/kakao"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(PASSWORD))).andExpect(status().isNotFound());

		callback(session, "kakao", "u2402", state(start(session, "kakao", "link")));
		mockMvc.perform(withXsrf(post("/api/me/withdraw"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(PASSWORD))).andExpect(status().isNoContent());
		assertThat(count("select count(*) from social_account where account_id = ?", me.getId())).isZero();

		Account forced = newAccount("so-forced", Role.PARTICIPANT);
		MockHttpSession forcedSession = login(forced);
		callback(forcedSession, "naver", "u2403", state(start(forcedSession, "naver", "link")));
		MockHttpSession operator = login(newAccount("so-op", Role.OPERATOR));
		mockMvc.perform(withXsrf(post("/api/operator/accounts/{id}/withdraw", forced.getId()), operator)
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"정리 확인\"}")).andExpect(status().isOk());
		assertThat(count("select count(*) from social_account where account_id = ?", forced.getId())).isZero();
	}

	private String start(MockHttpSession s, String provider, String mode) throws Exception {
		return mockMvc.perform(get("/api/auth/oauth/{p}/{mode}/start", provider, mode).session(s)).andExpect(status().isFound())
			.andReturn().getResponse().getRedirectedUrl();
	}

	private static String state(String location) {
		return UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
	}

	private String callback(MockHttpSession s, String provider, String code, String state) throws Exception {
		return mockMvc.perform(get("/api/auth/oauth/{p}/callback", provider).session(s).param("code", code).param("state", state))
			.andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

}

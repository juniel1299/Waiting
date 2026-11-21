package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * prod: 세션은 DB(spring_session)에 저장돼 재시작·절전 뒤에도 남고, 쿠키는 Secure·HttpOnly·SameSite=Lax.
 * 실제 포트로, 프록시 토큰을 붙여 호출한다. Secure 쿠키는 http 클라이언트가 보내지 않으므로 Cookie 헤더를 직접 붙인다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = "daumjari.proxy.shared-secret=prod-session-test-secret-123")
@ActiveProfiles("prod")
@Testcontainers(disabledWithoutDocker = true)
class ProdSessionTests {

	static final String SECRET = "prod-session-test-secret-123";

	@Value("${local.server.port}")
	int port;

	@Autowired
	JdbcTemplate jdbc;

	final HttpClient http = HttpClient.newHttpClient();

	@Test
	void demoSessionIsStoredInTheDatabaseWithSecureCookies() throws Exception {
		HttpResponse<String> csrf = send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET(), null);
		String xsrf = cookieValue(csrf, "XSRF-TOKEN");
		assertThat(setCookie(csrf, "XSRF-TOKEN")).contains("Secure");

		HttpResponse<String> start = send(HttpRequest.newBuilder(uri("/api/demo/start"))
			.header("X-XSRF-TOKEN", xsrf)
			.POST(BodyPublishers.noBody()), "XSRF-TOKEN=" + xsrf);
		assertThat(start.statusCode()).as(start.body()).isEqualTo(201);
		String session = setCookie(start, "JSESSIONID");
		assertThat(session).contains("Secure").contains("HttpOnly").containsIgnoringCase("SameSite=Lax");

		assertThat(jdbc.queryForObject("select count(*) from spring_session", Long.class)).isPositive();
		HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/auth/me")).GET(),
				"JSESSIONID=" + cookieValue(start, "JSESSIONID"));
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(me.body()).contains("\"demo\":true");
	}

	/**
	 * 회귀: 세션 저장소의 principal 이름은 varchar(100) 이다. 예전에는 SessionUser.toString() 전체가 들어가
	 * 아이디가 11자 이상이면(가입 허용 4~20자) 로그인이 500 이었다 (2026-11-21 공개 배포에서 발견).
	 */
	@Test
	void signedUpMemberWithTheLongestAllowedLoginIdCanLogIn() throws Exception {
		String loginId = ("lm" + UUID.randomUUID().toString().replace("-", "")).substring(0, 20);
		String password = "prod-login-pass-1!";
		String clientIp = "10.9." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(1, 255);
		String xsrf = cookieValue(send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET(), null), "XSRF-TOKEN");
		String body = "{\"loginId\":\"%s\",\"password\":\"%s\",\"passwordConfirm\":\"%s\"}".formatted(loginId, password,
				password);
		HttpResponse<String> signup = send(HttpRequest.newBuilder(uri("/api/auth/signup"))
			.header("X-XSRF-TOKEN", xsrf)
			.header("X-Daumjari-Client-IP", clientIp)
			.header("Content-Type", "application/json")
			.POST(BodyPublishers.ofString(body)), "XSRF-TOKEN=" + xsrf);
		assertThat(signup.statusCode()).as(signup.body()).isEqualTo(201);

		HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/api/auth/login"))
			.header("X-XSRF-TOKEN", xsrf)
			.header("X-Daumjari-Client-IP", clientIp)
			.header("Content-Type", "application/json")
			.POST(BodyPublishers.ofString("{\"loginId\":\"%s\",\"password\":\"%s\"}".formatted(loginId, password))),
				"XSRF-TOKEN=" + xsrf);
		assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
		assertThat(jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Long.class,
				loginId))
			.isEqualTo(1L);
		HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/auth/me")).GET(),
				"JSESSIONID=" + cookieValue(login, "JSESSIONID"));
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(me.body()).contains("\"loginId\":\"" + loginId + "\"");
	}

	private HttpResponse<String> send(HttpRequest.Builder request, String cookie) throws Exception {
		request.header("X-Daumjari-Proxy-Token", SECRET);
		if (cookie != null) {
			request.header("Cookie", cookie);
		}
		return http.send(request.build(), BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private static String setCookie(HttpResponse<String> response, String name) {
		List<String> all = response.headers().allValues("Set-Cookie");
		return all.stream().filter(c -> c.startsWith(name + "=")).findFirst().orElseThrow(
				() -> new AssertionError(name + " Set-Cookie 없음: " + all));
	}

	private static String cookieValue(HttpResponse<String> response, String name) {
		String header = setCookie(response, name);
		return header.substring(name.length() + 1, header.indexOf(';'));
	}

}

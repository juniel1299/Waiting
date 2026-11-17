package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;

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

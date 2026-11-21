package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 같은 아이디로 동시에 가입해도 DB unique 제약으로 정확히 하나만 성공하고, 나머지는 500 이 아닌 409 를 받는다.
 * 실제 포트·PostgreSQL, 요청마다 별도 트랜잭션, CyclicBarrier 동시 출발.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "daumjari.signup.max-per-ip=100000")
@Testcontainers(disabledWithoutDocker = true)
class SignupConcurrencyTests {

	static final int REQUESTS = 8;

	@Value("${local.server.port}")
	int port;

	@RepeatedTest(5)
	void concurrentSignupsWithTheSameLoginIdCreateExactlyOneAccount() throws Exception {
		String baseUrl = "http://localhost:" + port;
		String loginId = SignupApiTests.uniqueId();
		CyclicBarrier start = new CyclicBarrier(REQUESTS);
		ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < REQUESTS; i++) {
				results.add(pool.submit(() -> {
					SignupClient client = SignupClient.prepare(baseUrl);
					start.await();
					return client.signup(loginId);
				}));
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get(30, TimeUnit.SECONDS));
			}
			assertThat(statuses).containsOnly(201, 409);
			assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
		}
		finally {
			pool.shutdownNow();
		}
	}

	/** 쿠키(XSRF)를 미리 받아 둔 새 브라우저. */
	private record SignupClient(String baseUrl, HttpClient http, java.net.CookieManager cookies) {

		static SignupClient prepare(String baseUrl) throws Exception {
			java.net.CookieManager cookies = new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL);
			HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).cookieHandler(cookies).build();
			http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/csrf")).GET().build(),
					BodyHandlers.discarding());
			return new SignupClient(baseUrl, http, cookies);
		}

		int signup(String loginId) throws Exception {
			String token = cookies.getCookieStore()
				.getCookies()
				.stream()
				.filter(c -> c.getName().equals("XSRF-TOKEN"))
				.findFirst()
				.orElseThrow()
				.getValue();
			HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/signup"))
				.header("Content-Type", "application/json")
				.header("X-XSRF-TOKEN", token)
				.POST(BodyPublishers.ofString("""
						{"loginId":"%s","password":"%s","passwordConfirm":"%s"}
						""".formatted(loginId, ApiTestSupport.PASSWORD, ApiTestSupport.PASSWORD)))
				.build();
			return http.send(request, BodyHandlers.discarding()).statusCode();
		}

	}

}

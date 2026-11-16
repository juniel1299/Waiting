package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * 같은 계정·같은 IP 로 실패 요청 10개가 동시에 들어와도 집계가 빠지지 않는지.
 * 실제 포트·PostgreSQL, 요청마다 별도 트랜잭션·커넥션, CyclicBarrier 동시 출발.
 * 시도를 인증 전에 원자적으로 세므로 정확히 5개만 인증까지 가고(401) 나머지 5개는 429 여야 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = "daumjari.login-throttle.max-per-ip=100000")
@Testcontainers(disabledWithoutDocker = true)
class LoginThrottleConcurrencyTests {

	static final int REQUESTS = 10;

	static final int MAX_PER_ACCOUNT_IP = 5;

	@Value("${local.server.port}")
	int port;

	@RepeatedTest(10)
	void concurrentFailuresAreCountedExactly() throws Exception {
		String loginId = "race-" + UUID.randomUUID().toString().substring(0, 8);
		String base = "http://localhost:" + port;
		CyclicBarrier start = new CyclicBarrier(REQUESTS);
		ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
		List<Integer> statuses = new ArrayList<>();
		try {
			List<Future<HttpResponse<String>>> futures = new ArrayList<>();
			for (int i = 0; i < REQUESTS; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return HttpTestClient.attemptLogin(base, loginId, "wrong", null);
				}));
			}
			for (Future<HttpResponse<String>> f : futures) {
				statuses.add(f.get(30, TimeUnit.SECONDS).statusCode());
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(statuses).filteredOn(s -> s == 401).hasSize(MAX_PER_ACCOUNT_IP);
		assertThat(statuses).filteredOn(s -> s == 429).hasSize(REQUESTS - MAX_PER_ACCOUNT_IP);
	}

}

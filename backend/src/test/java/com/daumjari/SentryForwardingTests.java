package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sun.net.httpserver.HttpServer;

/**
 * Sentry 연동은 DSN 환경변수가 있을 때만 켜진다. 켜지면 가린 메시지만 보내고, 같은 오류는 1시간에 한 번만 보낸다(무료 한도 보호).
 * 실제 Sentry 대신 로컬 HTTP 서버로 받는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SentryForwardingTests extends ApiTestSupport {

	static final List<String> received = new CopyOnWriteArrayList<>();

	static final List<String> auth = new CopyOnWriteArrayList<>();

	static HttpServer sentry;

	@DynamicPropertySource
	static void dsn(DynamicPropertyRegistry registry) throws IOException {
		sentry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		sentry.createContext("/api/42/envelope/", exchange -> {
			received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			auth.add(exchange.getRequestHeaders().getFirst("X-Sentry-Auth"));
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		sentry.start();
		registry.add("daumjari.sentry.dsn", () -> "http://publickey123@127.0.0.1:" + sentry.getAddress().getPort() + "/42");
	}

	@AfterAll
	static void stop() {
		sentry.stop(0);
	}

	@Test
	void maskedEventsAreForwardedOncePerHourPerError() throws Exception {
		String unique = "센트리 " + ErrorLogApiTests.marker();
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(withXsrf(post("/api/errors"), null).contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"%s password=hunter2\",\"path\":\"/board\"}".formatted(unique)))
				.andExpect(status().isAccepted());
		}
		long until = System.currentTimeMillis() + 5000;
		while (received.stream().noneMatch(b -> b.contains(unique)) && System.currentTimeMillis() < until) {
			Thread.sleep(50);
		}
		Thread.sleep(300);
		List<String> mine = received.stream().filter(b -> b.contains(unique)).toList();
		assertThat(mine).hasSize(1);
		assertThat(mine.get(0)).doesNotContain("hunter2").contains("\"fingerprint\"", "\"release\"");
		assertThat(auth).allSatisfy(h -> assertThat(h).contains("sentry_key=publickey123"));
	}

}

package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;
import com.daumjari.reservation.Reservation;
import com.daumjari.reservation.ReservationRepository;
import com.daumjari.studysession.StudySession;
import com.daumjari.studysession.StudySessionRepository;

/**
 * 실제 톰캣 포트 + 실제 PostgreSQL. 테스트를 트랜잭션으로 감싸지 않으므로 각 HTTP 요청은
 * 서버에서 자기 트랜잭션·커넥션으로 처리된다. 두 요청은 CyclicBarrier 로 동시에 출발한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ReservationConcurrencyTests {

	static final int REPEAT = 30;

	static final String PASSWORD = "concurrency-pass-1!";

	static ApiClient alice;

	static ApiClient bob;

	static Account aliceAccount;

	static Account bobAccount;

	static Account filler;

	@Value("${local.server.port}")
	int port;

	@Autowired
	AccountRepository accounts;

	@Autowired
	StudySessionRepository sessions;

	@Autowired
	ReservationRepository reservations;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void loginOnce() throws Exception {
		if (alice != null) {
			return;
		}
		aliceAccount = newAccount("c-alice");
		bobAccount = newAccount("c-bob");
		filler = newAccount("c-filler");
		alice = ApiClient.login("http://localhost:" + port, aliceAccount.getLoginId(), PASSWORD);
		bob = ApiClient.login("http://localhost:" + port, bobAccount.getLoginId(), PASSWORD);
	}

	@RepeatedTest(REPEAT)
	void lastSeatRaceBetweenTwoAccountsConfirmsExactlyOne() throws Exception {
		StudySession session = lastSeatSession();

		List<HttpResponse<String>> responses = race(() -> alice.reserve(session.getId()),
				() -> bob.reserve(session.getId()));

		assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
		assertThat(responses).filteredOn(r -> r.statusCode() == 409)
			.singleElement()
			.satisfies(r -> assertThat(r.body()).contains("\"code\":\"SESSION_FULL\""));
		assertThat(confirmedCount(session.getId(), aliceAccount, bobAccount)).isEqualTo(1);
		assertThat(confirmedTotal(session.getId())).isEqualTo(session.getCapacity());
	}

	@RepeatedTest(REPEAT)
	void sameAccountDuplicateRaceKeepsOneActiveReservation() throws Exception {
		StudySession session = sessions.save(StudySession.create("동시 중복 회차", tomorrow(), "테스트룸", 5, Instant.now()));

		List<HttpResponse<String>> responses = race(() -> alice.reserve(session.getId()),
				() -> alice.reserve(session.getId()));

		assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 200);
		assertThat(idOf(responses.get(0).body())).isEqualTo(idOf(responses.get(1).body()));
		assertThat(activeCount(session.getId(), aliceAccount)).isEqualTo(1);
	}

	/** 정원 2, 다른 계정이 이미 1석 확정 → 마지막 1석. */
	private StudySession lastSeatSession() {
		StudySession session = sessions.save(StudySession.create("마지막 1석", tomorrow(), "테스트룸", 2, Instant.now()));
		reservations.save(Reservation.confirm(session, filler, Instant.now()));
		return session;
	}

	private static List<HttpResponse<String>> race(Callable<HttpResponse<String>> first,
			Callable<HttpResponse<String>> second) throws Exception {
		CyclicBarrier start = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<HttpResponse<String>> a = pool.submit(() -> {
				start.await();
				return first.call();
			});
			Future<HttpResponse<String>> b = pool.submit(() -> {
				start.await();
				return second.call();
			});
			return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
		}
		finally {
			pool.shutdownNow();
		}
	}

	private long confirmedCount(Long sessionId, Account... among) {
		return jdbc.queryForObject("""
				select count(*) from reservation
				where study_session_id = ? and status = 'CONFIRMED' and account_id in (?, ?)
				""", Long.class, sessionId, among[0].getId(), among[1].getId());
	}

	private long confirmedTotal(Long sessionId) {
		return jdbc.queryForObject(
				"select count(*) from reservation where study_session_id = ? and status = 'CONFIRMED'", Long.class,
				sessionId);
	}

	private long activeCount(Long sessionId, Account account) {
		return jdbc.queryForObject("""
				select count(*) from reservation
				where study_session_id = ? and account_id = ? and status <> 'CANCELLED'
				""", Long.class, sessionId, account.getId());
	}

	private Account newAccount(String prefix) {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		return accounts
			.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), prefix, Role.PARTICIPANT, Instant.now()));
	}

	private static Instant tomorrow() {
		return Instant.now().plus(1, ChronoUnit.DAYS);
	}

	private static Long idOf(String json) {
		return Long.valueOf(json.replaceAll("^\\{\"id\":(\\d+).*", "$1").trim());
	}

	/** 사용자 한 명의 브라우저 역할: 자기 쿠키(JSESSIONID, XSRF-TOKEN)를 들고 요청한다. */
	static final class ApiClient {

		private final String baseUrl;

		private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

		private final HttpClient http;

		private ApiClient(String baseUrl) {
			this.baseUrl = baseUrl;
			this.http = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.cookieHandler(cookies)
				.connectTimeout(Duration.ofSeconds(5))
				.build();
		}

		static ApiClient login(String baseUrl, String loginId, String password) throws Exception {
			ApiClient client = new ApiClient(baseUrl);
			client.refreshXsrf();
			HttpResponse<String> response = client.post("/api/auth/login",
					"{\"loginId\":\"%s\",\"password\":\"%s\"}".formatted(loginId, password));
			assertThat(response.statusCode()).as("login %s: %s", loginId, response.body()).isEqualTo(200);
			// 로그인하면 토큰이 재발급되므로 다시 받는다
			client.refreshXsrf();
			return client;
		}

		HttpResponse<String> reserve(Long sessionId) throws Exception {
			return post("/api/sessions/" + sessionId + "/reservations", "");
		}

		private void refreshXsrf() throws Exception {
			http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/csrf")).GET().build(),
					BodyHandlers.discarding());
		}

		private HttpResponse<String> post(String path, String json) throws Exception {
			HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
				.header("Content-Type", "application/json")
				.header("X-XSRF-TOKEN", xsrfToken())
				.POST(BodyPublishers.ofString(json))
				.build();
			return http.send(request, BodyHandlers.ofString());
		}

		private String xsrfToken() {
			return cookies.getCookieStore()
				.getCookies()
				.stream()
				.filter(c -> c.getName().equals("XSRF-TOKEN"))
				.map(HttpCookie::getValue)
				.findFirst()
				.orElseThrow();
		}

	}

}

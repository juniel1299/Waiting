package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

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

	static HttpTestClient alice;

	static HttpTestClient bob;

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
		alice = HttpTestClient.login("http://localhost:" + port, aliceAccount.getLoginId(), PASSWORD);
		bob = HttpTestClient.login("http://localhost:" + port, bobAccount.getLoginId(), PASSWORD);
	}

	@RepeatedTest(REPEAT)
	void lastSeatRaceBetweenTwoAccountsConfirmsExactlyOne() throws Exception {
		StudySession session = lastSeatSession();

		List<HttpResponse<String>> responses = HttpTestClient.race(() -> alice.reserve(session.getId()),
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

		List<HttpResponse<String>> responses = HttpTestClient.race(() -> alice.reserve(session.getId()),
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

}

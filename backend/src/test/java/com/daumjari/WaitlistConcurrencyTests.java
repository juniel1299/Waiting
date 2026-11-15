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
import com.daumjari.studysession.StudySession;
import com.daumjari.studysession.StudySessionRepository;

/**
 * 확정자 취소(→ 같은 트랜잭션에서 승격)와 신규 요청이 동시에 들어와도 기존 대기자가 먼저 확정되고
 * 정원을 넘지 않는지. 실제 포트·PostgreSQL, 요청마다 별도 트랜잭션, CyclicBarrier 동시 출발.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class WaitlistConcurrencyTests {

	static final int REPEAT = 30;

	static final String PASSWORD = "waitlist-pass-1!";

	/** 확정했다가 취소하는 사람, 먼저 대기 중인 사람, 동시에 새로 들어오는 사람, 다른 확정자. */
	static HttpTestClient canceller;

	static HttpTestClient waiter;

	static HttpTestClient newcomer;

	static HttpTestClient filler;

	static Account waiterAccount;

	static Account newcomerAccount;

	@Value("${local.server.port}")
	int port;

	@Autowired
	AccountRepository accounts;

	@Autowired
	StudySessionRepository sessions;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void loginOnce() throws Exception {
		if (canceller != null) {
			return;
		}
		String base = "http://localhost:" + port;
		canceller = HttpTestClient.login(base, newAccount("w-canceller").getLoginId(), PASSWORD);
		waiterAccount = newAccount("w-waiter");
		waiter = HttpTestClient.login(base, waiterAccount.getLoginId(), PASSWORD);
		newcomerAccount = newAccount("w-newcomer");
		newcomer = HttpTestClient.login(base, newcomerAccount.getLoginId(), PASSWORD);
		filler = HttpTestClient.login(base, newAccount("w-filler").getLoginId(), PASSWORD);
	}

	@RepeatedTest(REPEAT)
	void cancelRacingANewReservationPromotesTheExistingWaiter() throws Exception {
		Prepared p = fullSessionWithOneWaiter();

		List<HttpResponse<String>> responses = HttpTestClient.race(() -> canceller.cancel(p.cancellerReservationId),
				() -> newcomer.reserve(p.sessionId));

		assertThat(responses.get(0).statusCode()).isEqualTo(200);
		assertThat(responses.get(1).statusCode()).as(responses.get(1).body()).isEqualTo(409);
		assertThat(responses.get(1).body()).contains("\"code\":\"SESSION_FULL\"");
		assertThat(statusOf(p.sessionId, waiterAccount)).isEqualTo("CONFIRMED");
		assertThat(activeCount(p.sessionId, newcomerAccount)).isZero();
		assertThat(statusCount(p.sessionId, "CONFIRMED")).isEqualTo(2);
	}

	@RepeatedTest(REPEAT)
	void cancelRacingANewWaitlistRequestKeepsQueueOrder() throws Exception {
		Prepared p = fullSessionWithOneWaiter();

		List<HttpResponse<String>> responses = HttpTestClient.race(() -> canceller.cancel(p.cancellerReservationId),
				() -> newcomer.waitlist(p.sessionId));

		assertThat(responses.get(0).statusCode()).isEqualTo(200);
		assertThat(responses.get(1).statusCode()).as(responses.get(1).body()).isEqualTo(201);
		assertThat(responses.get(1).body()).contains("\"status\":\"WAITING\"");
		assertThat(statusOf(p.sessionId, waiterAccount)).isEqualTo("CONFIRMED");
		assertThat(statusOf(p.sessionId, newcomerAccount)).isEqualTo("WAITING");
		assertThat(statusCount(p.sessionId, "CONFIRMED")).isEqualTo(2);
		assertThat(statusCount(p.sessionId, "WAITING")).isEqualTo(1);
	}

	private record Prepared(Long sessionId, Long cancellerReservationId) {
	}

	/** 정원 2: filler·canceller 확정, waiter 대기 1번. 모두 API 로 만든다. */
	private Prepared fullSessionWithOneWaiter() throws Exception {
		StudySession session = sessions.save(StudySession.create("대기 경합", Instant.now().plus(1, ChronoUnit.DAYS),
				"테스트룸", 2, Instant.now()));
		assertThat(filler.reserve(session.getId()).statusCode()).isEqualTo(201);
		HttpResponse<String> mine = canceller.reserve(session.getId());
		assertThat(mine.statusCode()).isEqualTo(201);
		HttpResponse<String> waiting = waiter.waitlist(session.getId());
		assertThat(waiting.statusCode()).as(waiting.body()).isEqualTo(201);
		return new Prepared(session.getId(), idOf(mine.body()));
	}

	private String statusOf(Long sessionId, Account account) {
		return jdbc.queryForObject("""
				select status from reservation
				where study_session_id = ? and account_id = ? and status <> 'CANCELLED'
				""", String.class, sessionId, account.getId());
	}

	private long activeCount(Long sessionId, Account account) {
		return jdbc.queryForObject("""
				select count(*) from reservation
				where study_session_id = ? and account_id = ? and status <> 'CANCELLED'
				""", Long.class, sessionId, account.getId());
	}

	private long statusCount(Long sessionId, String status) {
		return jdbc.queryForObject("select count(*) from reservation where study_session_id = ? and status = ?",
				Long.class, sessionId, status);
	}

	private Account newAccount(String prefix) {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		return accounts
			.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), prefix, Role.PARTICIPANT, Instant.now()));
	}

	private static Long idOf(String json) {
		return Long.valueOf(json.replaceAll("^\\{\"id\":(\\d+).*", "$1").trim());
	}

}

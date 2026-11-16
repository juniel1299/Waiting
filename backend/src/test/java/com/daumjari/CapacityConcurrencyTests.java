package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
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
 * 운영자 증설과 신규 예약이 동시에 들어와도 기존 대기자가 먼저 확정되고 정원을 넘지 않는지.
 * 실제 포트·PostgreSQL, 요청마다 별도 트랜잭션, CyclicBarrier 동시 출발.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class CapacityConcurrencyTests {

	static final int REPEAT = 30;

	static final String PASSWORD = "capacity-pass-1!";

	static final AtomicInteger newcomerConfirmed = new AtomicInteger();

	static final AtomicInteger newcomerRejected = new AtomicInteger();

	static HttpTestClient operator;

	static HttpTestClient filler1;

	static HttpTestClient filler2;

	static HttpTestClient waiter1;

	static HttpTestClient waiter2;

	static HttpTestClient newcomer;

	static Account waiter1Account;

	static Account waiter2Account;

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
		if (operator != null) {
			return;
		}
		String base = "http://localhost:" + port;
		operator = HttpTestClient.login(base, newAccount("c-op", Role.OPERATOR).getLoginId(), PASSWORD);
		filler1 = HttpTestClient.login(base, newAccount("c-f1", Role.PARTICIPANT).getLoginId(), PASSWORD);
		filler2 = HttpTestClient.login(base, newAccount("c-f2", Role.PARTICIPANT).getLoginId(), PASSWORD);
		waiter1Account = newAccount("c-w1", Role.PARTICIPANT);
		waiter1 = HttpTestClient.login(base, waiter1Account.getLoginId(), PASSWORD);
		waiter2Account = newAccount("c-w2", Role.PARTICIPANT);
		waiter2 = HttpTestClient.login(base, waiter2Account.getLoginId(), PASSWORD);
		newcomerAccount = newAccount("c-new", Role.PARTICIPANT);
		newcomer = HttpTestClient.login(base, newcomerAccount.getLoginId(), PASSWORD);
	}

	@AfterAll
	static void report() {
		System.out.printf("[capacity race] 신규 예약 확정 %d회, 마감 거절 %d회 (총 %d회)%n", newcomerConfirmed.get(),
				newcomerRejected.get(), REPEAT);
	}

	@RepeatedTest(REPEAT)
	void increaseRacingANewReservationPromotesWaitersFirstAndNeverOverbooks() throws Exception {
		// 정원 2: filler 2명 확정, waiter1·waiter2 대기 1·2번
		StudySession session = sessions.save(StudySession.create("증설 경합", Instant.now().plus(1, ChronoUnit.DAYS),
				"테스트룸", 2, Instant.now()));
		Long id = session.getId();
		assertThat(filler1.reserve(id).statusCode()).isEqualTo(201);
		assertThat(filler2.reserve(id).statusCode()).isEqualTo(201);
		assertThat(waiter1.waitlist(id).statusCode()).isEqualTo(201);
		assertThat(waiter2.waitlist(id).statusCode()).isEqualTo(201);

		// 신규 예약 출발을 0~5ms 흔들어 "예약 먼저"·"증설 먼저" 두 순서가 모두 나오게 한다
		long jitterMs = ThreadLocalRandom.current().nextLong(6);
		List<HttpResponse<String>> responses = HttpTestClient.race(() -> operator.changeCapacity(id, 5), () -> {
			Thread.sleep(jitterMs);
			return newcomer.reserve(id);
		});

		assertThat(responses.get(0).statusCode()).as(responses.get(0).body()).isEqualTo(200);
		assertThat(activeStatus(id, waiter1Account)).isEqualTo("CONFIRMED");
		assertThat(activeStatus(id, waiter2Account)).isEqualTo("CONFIRMED");
		assertThat(statusCount(id, "WAITING")).isZero();
		long confirmed = statusCount(id, "CONFIRMED");
		assertThat(confirmed).isLessThanOrEqualTo(5);

		HttpResponse<String> reserve = responses.get(1);
		if (reserve.statusCode() == 201) {
			// 증설·승격이 먼저 끝난 뒤 남은 1석을 얻은 경우
			newcomerConfirmed.incrementAndGet();
			assertThat(confirmed).isEqualTo(5);
		}
		else {
			// 증설 전에 도착해 마감으로 거절된 경우 (대기자 자리를 가져가지 못함)
			newcomerRejected.incrementAndGet();
			assertThat(reserve.statusCode()).as(reserve.body()).isEqualTo(409);
			assertThat(reserve.body()).contains("\"code\":\"SESSION_FULL\"");
			assertThat(confirmed).isEqualTo(4);
		}
	}

	private String activeStatus(Long sessionId, Account account) {
		return jdbc.queryForObject("""
				select status from reservation
				where study_session_id = ? and account_id = ? and status <> 'CANCELLED'
				""", String.class, sessionId, account.getId());
	}

	private long statusCount(Long sessionId, String status) {
		return jdbc.queryForObject("select count(*) from reservation where study_session_id = ? and status = ?",
				Long.class, sessionId, status);
	}

	private Account newAccount(String prefix, Role role) {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		return accounts.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), prefix, role, Instant.now()));
	}

}

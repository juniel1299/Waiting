package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

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
 * 탈퇴와 같은 계정의 예약·대기 요청이 동시에 들어와도 탈퇴된 계정에 활성 신청이 남지 않고, 대기자 승격도 정확히 한 번.
 * 실제 포트·PostgreSQL, 요청마다 별도 트랜잭션, CyclicBarrier 동시 출발.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class WithdrawalConcurrencyTests {

	static final int REPEAT = 20;

	static final String PASSWORD = "withdraw-race-1!";

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

	@RepeatedTest(REPEAT)
	void withdrawalRacingAReservationLeavesNoActiveReservation() throws Exception {
		Account member = newAccount("wc-member");
		Account waiter = newAccount("wc-waiter");
		StudySession full = sessions.save(StudySession.create("탈퇴 경합 마감", tomorrow(), "테스트룸", 1, Instant.now()));
		StudySession open = sessions.save(StudySession.create("탈퇴 경합 여유", tomorrow(), "테스트룸", 5, Instant.now()));
		reservations.save(Reservation.confirm(full, member, Instant.now()));
		Reservation waiting = reservations.save(Reservation.waitlist(full, waiter, Instant.now()));
		HttpTestClient client = HttpTestClient.login("http://localhost:" + port, member.getLoginId(), PASSWORD);

		List<HttpResponse<String>> responses = HttpTestClient.race(() -> client.withdraw(PASSWORD),
				() -> client.reserve(open.getId()));

		assertThat(responses.get(0).statusCode()).as(responses.get(0).body()).isEqualTo(204);
		assertThat(responses.get(1).statusCode()).as(responses.get(1).body()).isIn(201, 401);
		assertThat(activeCount(member)).isZero();
		assertThat(jdbc.queryForObject("select deleted_at is not null from account where id = ?", Boolean.class,
				member.getId()))
			.isTrue();
		assertThat(jdbc.queryForObject("select status from reservation where id = ?", String.class, waiting.getId()))
			.isEqualTo("CONFIRMED");
		assertThat(jdbc.queryForObject(
				"select count(*) from reservation where study_session_id = ? and status = 'CONFIRMED'", Long.class,
				full.getId()))
			.isEqualTo(1L);
	}

	private long activeCount(Account account) {
		return jdbc.queryForObject("select count(*) from reservation where account_id = ? and status <> 'CANCELLED'",
				Long.class, account.getId());
	}

	private Account newAccount(String prefix) {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		return accounts
			.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), prefix, Role.PARTICIPANT, Instant.now()));
	}

	private static Instant tomorrow() {
		return Instant.now().plus(1, ChronoUnit.DAYS);
	}

}

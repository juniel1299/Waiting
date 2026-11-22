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
 * 운영 조치 경합 (실제 포트·PostgreSQL, CyclicBarrier 동시 출발).
 * 1) 강제탈퇴와 대상의 예약이 겹쳐도 탈퇴된 계정에 활성 신청이 남지 않고 대기자 승격은 정확히 한 번.
 * 2) 활성 운영자 둘이 서로를 동시에 정지해도 운영자가 0명이 되지 않는다 (한쪽만 성공).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ModerationConcurrencyTests {

	static final int REPEAT = 20;

	static final String PASSWORD = "moderate-race-1!";

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
	void forcedWithdrawalRacingTheTargetsReservationLeavesNoActiveReservation() throws Exception {
		Account operator = newAccount("mc-op", Role.OPERATOR);
		Account member = newAccount("mc-member", Role.PARTICIPANT);
		Account waiter = newAccount("mc-waiter", Role.PARTICIPANT);
		StudySession full = sessions.save(StudySession.create("강제탈퇴 경합 마감", tomorrow(), "테스트룸", 1, Instant.now()));
		StudySession open = sessions.save(StudySession.create("강제탈퇴 경합 여유", tomorrow(), "테스트룸", 5, Instant.now()));
		reservations.save(Reservation.confirm(full, member, Instant.now()));
		Reservation waiting = reservations.save(Reservation.waitlist(full, waiter, Instant.now()));
		HttpTestClient op = HttpTestClient.login(baseUrl(), operator.getLoginId(), PASSWORD);
		HttpTestClient client = HttpTestClient.login(baseUrl(), member.getLoginId(), PASSWORD);

		List<HttpResponse<String>> responses = HttpTestClient.race(() -> op.moderate(member.getId(), "withdraw", "경합"),
				() -> client.reserve(open.getId()));

		assertThat(responses.get(0).statusCode()).as(responses.get(0).body()).isEqualTo(200);
		assertThat(responses.get(1).statusCode()).as(responses.get(1).body()).isIn(201, 401);
		assertThat(jdbc.queryForObject("select count(*) from reservation where account_id = ? and status <> 'CANCELLED'",
				Long.class, member.getId())).isZero();
		assertThat(jdbc.queryForObject("select status from reservation where id = ?", String.class, waiting.getId()))
			.isEqualTo("CONFIRMED");
		assertThat(jdbc.queryForObject(
				"select count(*) from reservation where study_session_id = ? and status = 'CONFIRMED'", Long.class,
				full.getId())).isEqualTo(1L);
	}

	@RepeatedTest(REPEAT)
	void twoOperatorsSuspendingEachOtherLeaveOneActiveOperator() throws Exception {
		Account a = newAccount("mc-opa", Role.OPERATOR);
		Account b = newAccount("mc-opb", Role.OPERATOR);
		HttpTestClient clientA = HttpTestClient.login(baseUrl(), a.getLoginId(), PASSWORD);
		HttpTestClient clientB = HttpTestClient.login(baseUrl(), b.getLoginId(), PASSWORD);
		// DB 를 테스트끼리 공유하므로, 다른 활성 운영자를 잠시 정지해 a·b 만 남긴 뒤 되돌린다
		List<Long> others = jdbc.queryForList("""
				update account set suspended_at = now()
				where role = 'OPERATOR' and deleted_at is null and suspended_at is null and id not in (?, ?)
				returning id
				""", Long.class, a.getId(), b.getId());
		try {
			List<HttpResponse<String>> responses = HttpTestClient.race(
					() -> clientA.moderate(b.getId(), "suspend", "경합"), () -> clientB.moderate(a.getId(), "suspend", "경합"));

			long succeeded = responses.stream().filter(r -> r.statusCode() == 200).count();
			assertThat(succeeded).as(responses.get(0).body() + " / " + responses.get(1).body()).isEqualTo(1);
			assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isIn(200, 401, 409));
			assertThat(jdbc.queryForObject(
					"select count(*) from account where id in (?, ?) and suspended_at is null", Long.class, a.getId(),
					b.getId())).isEqualTo(1L);
			assertThat(jdbc.queryForObject(
					"select count(*) from moderation_action where operator_account_id in (?, ?)", Long.class, a.getId(),
					b.getId())).isEqualTo(1L);
		}
		finally {
			for (Long id : others) {
				jdbc.update("update account set suspended_at = null where id = ?", id);
			}
		}
	}

	private String baseUrl() {
		return "http://localhost:" + port;
	}

	private Account newAccount(String prefix, Role role) {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		return accounts.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), prefix, role, Instant.now()));
	}

	private static Instant tomorrow() {
		return Instant.now().plus(1, ChronoUnit.DAYS);
	}

}

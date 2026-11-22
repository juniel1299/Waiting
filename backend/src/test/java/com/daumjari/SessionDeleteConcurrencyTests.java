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
 * 회차 삭제가 예약·대기·정원 변경 요청과 동시에 들어와도 고아 신청이나 일부만 지워진 상태가 남지 않는다.
 * 실제 포트·PostgreSQL, 요청마다 별도 트랜잭션, CyclicBarrier 동시 출발.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class SessionDeleteConcurrencyTests {

	static final int REPEAT = 20;

	static final String PASSWORD = "delete-race-1!";

	static HttpTestClient creator;

	static HttpTestClient member;

	static Account creatorAccount;

	static Account holder;

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
		if (creator != null) {
			return;
		}
		creatorAccount = newAccount("d-creator");
		holder = newAccount("d-holder");
		creator = HttpTestClient.login("http://localhost:" + port, creatorAccount.getLoginId(), PASSWORD);
		member = HttpTestClient.login("http://localhost:" + port, newAccount("d-member").getLoginId(), PASSWORD);
	}

	@RepeatedTest(REPEAT)
	void deleteRacingAReservationLeavesNoOrphans() throws Exception {
		StudySession session = ownedSession(3);

		List<HttpResponse<String>> responses = HttpTestClient.race(() -> creator.deleteSession(session.getId()),
				() -> member.reserve(session.getId()));

		assertThat(responses.get(0).statusCode()).as(responses.get(0).body()).isEqualTo(204);
		assertThat(responses.get(1).statusCode()).as(responses.get(1).body()).isIn(201, 404);
		assertGone(session);
	}

	@RepeatedTest(REPEAT)
	void deleteRacingAWaitlistOnAFullSessionLeavesNoOrphans() throws Exception {
		StudySession session = ownedSession(1);
		reservations.save(Reservation.confirm(session, holder, Instant.now()));

		List<HttpResponse<String>> responses = HttpTestClient.race(() -> creator.deleteSession(session.getId()),
				() -> member.waitlist(session.getId()));

		assertThat(responses.get(0).statusCode()).isEqualTo(204);
		assertThat(responses.get(1).statusCode()).as(responses.get(1).body()).isIn(201, 404);
		assertGone(session);
	}

	@RepeatedTest(REPEAT)
	void deleteRacingACapacityIncreaseLeavesNoOrphans() throws Exception {
		StudySession session = ownedSession(1);
		reservations.save(Reservation.confirm(session, holder, Instant.now()));
		reservations.save(Reservation.waitlist(session, newAccount("d-w"), Instant.now()));

		List<HttpResponse<String>> responses = HttpTestClient.race(() -> creator.deleteSession(session.getId()),
				() -> creator.changeOwnCapacity(session.getId(), 3));

		assertThat(responses.get(0).statusCode()).isEqualTo(204);
		assertThat(responses.get(1).statusCode()).as(responses.get(1).body()).isIn(200, 404);
		assertGone(session);
	}

	private void assertGone(StudySession session) {
		assertThat(jdbc.queryForObject("select count(*) from study_session where id = ?", Long.class, session.getId()))
			.isZero();
		assertThat(jdbc.queryForObject("select count(*) from reservation where study_session_id = ?", Long.class,
				session.getId()))
			.isZero();
	}

	private StudySession ownedSession(int capacity) {
		StudySession session = sessions.save(StudySession.create("삭제 경합", Instant.now().plus(1, ChronoUnit.DAYS),
				"테스트룸", capacity, Instant.now()));
		jdbc.update("update study_session set creator_account_id = ? where id = ?", creatorAccount.getId(),
				session.getId());
		return session;
	}

	private Account newAccount(String prefix) {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		return accounts
			.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), prefix, Role.PARTICIPANT, Instant.now()));
	}

}

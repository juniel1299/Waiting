package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;
import com.daumjari.auth.SessionUser;
import com.daumjari.auth.WithdrawalService;
import com.daumjari.reservation.ReservationService;
import com.daumjari.reservation.WaitlistPromoter;
import com.daumjari.studysession.StudySession;
import com.daumjari.studysession.StudySessionRepository;

/** 탈퇴 도중(대기자 승격 단계) 실패하면 계정 익명화·신청 취소가 모두 롤백되는지. 결과는 SQL 로 확인한다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WithdrawalRollbackTests {

	static final String PASSWORD = "rollback-pass-1!";

	@MockitoSpyBean
	WaitlistPromoter promoter;

	@Autowired
	WithdrawalService withdrawals;

	@Autowired
	ReservationService reservationService;

	@Autowired
	AccountRepository accounts;

	@Autowired
	StudySessionRepository sessions;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbc;

	@AfterEach
	void restoreRealPromoter() {
		reset(spyBehindProxy());
	}

	@Test
	void failureWhilePromotingRollsBackTheWholeWithdrawal() {
		Account member = newAccount("wrb-member");
		Account waiter = newAccount("wrb-waiter");
		StudySession first = sessions.save(StudySession.create("롤백 회차 1", Instant.now().plus(1, ChronoUnit.DAYS),
				"테스트룸", 3, Instant.now()));
		StudySession full = sessions.save(StudySession.create("롤백 회차 2", Instant.now().plus(2, ChronoUnit.DAYS),
				"테스트룸", 1, Instant.now()));
		Long cancelledFirst = reservationService.reserve(first.getId(), member.getId()).reservation().id();
		Long mine = reservationService.reserve(full.getId(), member.getId()).reservation().id();
		Long waiting = reservationService.waitlist(full.getId(), waiter.getId()).reservation().id();
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("승격 저장 실패 주입");
		}).when(spyBehindProxy()).promoteNext(full.getId());

		assertThatThrownBy(() -> withdrawals.withdraw(userOf(member), PASSWORD, "10.0.0.1"))
			.isInstanceOf(IllegalStateException.class);

		assertThat(jdbc.queryForMap("select login_id, deleted_at from account where id = ?", member.getId()))
			.containsEntry("login_id", member.getLoginId())
			.containsEntry("deleted_at", null);
		assertThat(status(cancelledFirst)).isEqualTo("CONFIRMED");
		assertThat(status(mine)).isEqualTo("CONFIRMED");
		assertThat(status(waiting)).isEqualTo("WAITING");

		reset(spyBehindProxy());
		withdrawals.withdraw(userOf(member), PASSWORD, "10.0.0.1");
		assertThat(status(cancelledFirst)).isEqualTo("CANCELLED");
		assertThat(status(mine)).isEqualTo("CANCELLED");
		assertThat(status(waiting)).isEqualTo("CONFIRMED");
		assertThat(jdbc.queryForMap("select deleted_at from account where id = ?", member.getId()).get("deleted_at"))
			.isNotNull();
	}

	private String status(Long reservationId) {
		return (String) jdbc.queryForMap("select status from reservation where id = ?", reservationId).get("status");
	}

	private WaitlistPromoter spyBehindProxy() {
		return AopTestUtils.getUltimateTargetObject(promoter);
	}

	private static SessionUser userOf(Account account) {
		return new SessionUser(account.getId(), account.getLoginId(), account.getDisplayName(), account.getRole(), null);
	}

	private Account newAccount(String prefix) {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		return accounts.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), prefix, Role.PARTICIPANT,
				Instant.now()));
	}

}

package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;
import com.daumjari.reservation.ReservationService;
import com.daumjari.reservation.WaitlistPromoter;
import com.daumjari.studysession.StudySession;
import com.daumjari.studysession.StudySessionRepository;

/**
 * 승격 단계에서 실패하면 확정자 취소도 함께 롤백되는지. 실패는 주입된 협력 객체(WaitlistPromoter) 스파이로 만든다.
 * 테스트를 트랜잭션으로 감싸지 않으므로 서비스 호출이 실제로 커밋·롤백되고, 결과는 SQL 로 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PromotionRollbackTests {

	@MockitoSpyBean
	WaitlistPromoter promoter;

	@Autowired
	ReservationService service;

	@Autowired
	AccountRepository accounts;

	@Autowired
	StudySessionRepository sessions;

	@Autowired
	JdbcTemplate jdbc;

	@AfterEach
	void restoreRealPromoter() {
		reset(spyBehindProxy());
	}

	@Test
	void failureAfterPromotingRollsBackTheCancellationToo() {
		Account canceller = newAccount("rb-canceller");
		Account waiter = newAccount("rb-waiter");
		StudySession session = sessions.save(StudySession.create("롤백 회차", Instant.now().plus(1, ChronoUnit.DAYS),
				"테스트룸", 1, Instant.now()));
		Long confirmedId = service.reserve(session.getId(), canceller.getId()).reservation().id();
		Long waitingId = service.waitlist(session.getId(), waiter.getId()).reservation().id();
		// 대기자를 확정 상태로 바꾼 직후 실패시킨다
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("승격 저장 실패 주입");
		}).when(spyBehindProxy()).promoteNext(anyLong());

		assertThatThrownBy(() -> service.cancel(confirmedId, canceller.getId()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("승격 저장 실패 주입");

		assertThat(row(confirmedId)).containsEntry("status", "CONFIRMED").containsEntry("cancelled_at", null);
		assertThat(row(waitingId)).containsEntry("status", "WAITING");
		// 승격 알림은 승격과 같은 트랜잭션: 롤백되면 알림도 없다
		assertThat(promotedNotifications(waitingId)).isZero();

		reset(spyBehindProxy());
		service.cancel(confirmedId, canceller.getId());
		assertThat(row(confirmedId)).containsEntry("status", "CANCELLED");
		assertThat(row(waitingId)).containsEntry("status", "CONFIRMED");
		assertThat(promotedNotifications(waitingId)).isEqualTo(1L);
	}

	private long promotedNotifications(Long reservationId) {
		return jdbc.queryForObject("select count(*) from notification where event_key = ?", Long.class,
				"PROMOTED:" + reservationId);
	}

	@Test
	void promoterRefusesToRunOutsideTheCancellingTransaction() {
		assertThatThrownBy(() -> promoter.promoteNext(1L)).isInstanceOf(IllegalTransactionStateException.class);
	}

	/** 스파이는 트랜잭션 프록시 안쪽에 있다. 스텁은 프록시(MANDATORY 검사)를 거치지 않고 등록한다. */
	private WaitlistPromoter spyBehindProxy() {
		return AopTestUtils.getUltimateTargetObject(promoter);
	}

	private Map<String, Object> row(Long reservationId) {
		return jdbc.queryForMap("select status, cancelled_at from reservation where id = ?", reservationId);
	}

	private Account newAccount(String prefix) {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		return accounts.save(Account.create(loginId, "{noop}unused", prefix, Role.PARTICIPANT, Instant.now()));
	}

}

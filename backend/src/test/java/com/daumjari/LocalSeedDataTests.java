package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;
import com.daumjari.reservation.ReservationRepository;
import com.daumjari.reservation.ReservationStatus;
import com.daumjari.seed.LocalSeedData;
import com.daumjari.studysession.StudySession;
import com.daumjari.studysession.StudySessionRepository;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("local")
@Testcontainers(disabledWithoutDocker = true)
class LocalSeedDataTests {

	@Autowired
	LocalSeedData seed;

	@Autowired
	AccountRepository accounts;

	@Autowired
	StudySessionRepository sessions;

	@Autowired
	ReservationRepository reservations;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Test
	void seedsOperatorParticipantsAndSessions() {
		List<Account> all = accounts.findAll();

		assertThat(all).filteredOn(a -> a.getRole() == Role.OPERATOR).hasSize(1);
		assertThat(all).filteredOn(a -> a.getRole() == Role.PARTICIPANT).hasSizeGreaterThanOrEqualTo(2);
		assertThat(all).allMatch(a -> passwordEncoder.matches(LocalSeedData.PASSWORD, a.getPasswordHash()));

		List<Long> remaining = sessions.findAll().stream().map(this::remainingSeats).toList();
		assertThat(remaining).anyMatch(r -> r > 1);
		assertThat(remaining).contains(1L);
	}

	@Test
	void rerunDoesNotDuplicate() throws Exception {
		long accountsBefore = accounts.count();
		long sessionsBefore = sessions.count();

		seed.run(new DefaultApplicationArguments());

		assertThat(accounts.count()).isEqualTo(accountsBefore);
		assertThat(sessions.count()).isEqualTo(sessionsBefore);
	}

	private long remainingSeats(StudySession session) {
		return session.getCapacity()
				- reservations.countByStudySessionIdAndStatus(session.getId(), ReservationStatus.CONFIRMED);
	}

}

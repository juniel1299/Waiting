package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;
import com.daumjari.reservation.Reservation;
import com.daumjari.reservation.ReservationRepository;
import com.daumjari.reservation.ReservationStatus;
import com.daumjari.studysession.StudySession;
import com.daumjari.studysession.StudySessionRepository;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class SchemaConstraintTests {

	@Autowired
	AccountRepository accounts;

	@Autowired
	StudySessionRepository sessions;

	@Autowired
	ReservationRepository reservations;

	@Autowired
	JdbcTemplate jdbc;

	Account participant;

	StudySession session;

	@BeforeEach
	void setUp() {
		Instant now = Instant.now();
		participant = accounts.save(Account.create("schema-user", "{noop}x", "스키마", Role.PARTICIPANT, now));
		session = sessions.saveAndFlush(StudySession.create("스키마 회차", now.plus(1, ChronoUnit.DAYS), "강남", 2, now));
	}

	@Test
	void entityMapsToSchema() {
		reservations.saveAndFlush(Reservation.confirm(session, participant, Instant.now()));

		assertThat(reservations.countByStudySessionIdAndStatus(session.getId(), ReservationStatus.CONFIRMED))
			.isEqualTo(1);
	}

	@Test
	void secondActiveReservationForSameAccountIsRejected() {
		insertReservation("CONFIRMED");

		assertThatThrownBy(() -> insertReservation("CONFIRMED"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ux_reservation_active");
	}

	@Test
	void cancelledReservationsDoNotBlockNewActiveOne() {
		insertReservation("CANCELLED");
		insertReservation("CANCELLED");

		insertReservation("CONFIRMED");

		assertThat(reservations.countByStudySessionIdAndStatus(session.getId(), ReservationStatus.CONFIRMED))
			.isEqualTo(1);
	}

	@Test
	void nonPositiveCapacityIsRejected() {
		assertThatThrownBy(() -> jdbc.update(
				"insert into study_session (title, starts_at, location, capacity) values ('x', now(), 'y', 0)"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void insertReservation(String status) {
		jdbc.update("""
				insert into reservation (study_session_id, account_id, status, cancelled_at)
				values (?, ?, ?, case when ? = 'CANCELLED' then now() end)
				""", session.getId(), participant.getId(), status, status);
	}

}

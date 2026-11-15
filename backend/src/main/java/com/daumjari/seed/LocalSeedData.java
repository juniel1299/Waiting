package com.daumjari.seed;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;
import com.daumjari.reservation.Reservation;
import com.daumjari.reservation.ReservationRepository;
import com.daumjari.studysession.StudySession;
import com.daumjari.studysession.StudySessionRepository;

/**
 * 로컬 개발용 시드. local 프로필에서만 동작하고, 계정이 하나라도 있으면 건너뛴다.
 * 계정·비밀번호는 README "시드 계정" 표와 맞춘다.
 */
@Component
@Profile("local")
public class LocalSeedData implements ApplicationRunner {

	public static final String PASSWORD = "daumjari-local-1!";

	private static final Logger log = LoggerFactory.getLogger(LocalSeedData.class);

	private final AccountRepository accounts;

	private final StudySessionRepository sessions;

	private final ReservationRepository reservations;

	private final PasswordEncoder passwordEncoder;

	public LocalSeedData(AccountRepository accounts, StudySessionRepository sessions,
			ReservationRepository reservations, PasswordEncoder passwordEncoder) {
		this.accounts = accounts;
		this.sessions = sessions;
		this.reservations = reservations;
		this.passwordEncoder = passwordEncoder;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (accounts.count() > 0) {
			log.info("local seed skipped: accounts already exist");
			return;
		}
		Instant now = Instant.now();
		String hash = passwordEncoder.encode(PASSWORD);

		accounts.save(Account.create("operator", hash, "운영자", Role.OPERATOR, now));
		Account alice = accounts.save(Account.create("alice", hash, "앨리스", Role.PARTICIPANT, now));
		accounts.save(Account.create("bob", hash, "밥", Role.PARTICIPANT, now));
		Account carol = accounts.save(Account.create("carol", hash, "캐롤", Role.PARTICIPANT, now));

		// 19:00 KST = 10:00 UTC
		Instant today1900Kst = now.truncatedTo(ChronoUnit.DAYS).plus(10, ChronoUnit.HOURS);

		// 빈자리 회차: 정원 5, 확정 1
		StudySession openSession = sessions.save(StudySession.create("Spring 트랜잭션 스터디",
				today1900Kst.plus(7, ChronoUnit.DAYS), "강남 스터디룸 A", 5, now));
		reservations.save(Reservation.confirm(openSession, alice, now));

		// 마지막 1석 회차: 정원 2, 확정 1 (alice·bob 이 남은 자리를 두고 경쟁 가능)
		StudySession lastSeatSession = sessions.save(StudySession.create("PostgreSQL 잠금 스터디",
				today1900Kst.plus(3, ChronoUnit.DAYS), "판교 스터디룸 B", 2, now));
		reservations.save(Reservation.confirm(lastSeatSession, carol, now));

		log.info("local seed created: 4 accounts, 2 study sessions");
	}

}

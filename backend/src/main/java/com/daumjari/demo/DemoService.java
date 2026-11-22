package com.daumjari.demo;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.daumjari.usage.Metric;
import com.daumjari.usage.UsageCounter;
import org.springframework.transaction.annotation.Transactional;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.auth.LoginThrottle;
import com.daumjari.auth.SessionUser;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;
import com.daumjari.reservation.Reservation;
import com.daumjari.reservation.ReservationDtos.ReservationResponse;
import com.daumjari.reservation.ReservationRepository;
import com.daumjari.reservation.ReservationService;
import com.daumjari.studysession.StudySession;
import com.daumjari.studysession.StudySessionRepository;

/**
 * 가입 없는 데모 체험. 체험마다 방문자·샘플 참가자 2명 계정과 정원 2 회차(샘플 2명 확정)를 만든다.
 * 체험 데이터는 demo_trial_id 로 묶여 다른 체험·일반 회차와 섞이지 않는다 (StudySession#isVisibleTo).
 */
@Service
public class DemoService {

	static final int SAMPLE_SEATS = 2;

	public record DemoStart(SessionUser visitor, long sessionId, Instant expiresAt) {
	}

	private final DemoTrials trials;

	private final AccountRepository accounts;

	private final StudySessionRepository sessions;

	private final ReservationRepository reservations;

	private final ReservationService reservationService;

	private final LoginThrottle throttle;

	private final DemoProperties properties;

	private final UsageCounter usage;

	private final Clock clock;

	public DemoService(DemoTrials trials, AccountRepository accounts, StudySessionRepository sessions,
			ReservationRepository reservations, ReservationService reservationService, LoginThrottle throttle,
			DemoProperties properties, UsageCounter usage, Clock clock) {
		this.usage = usage;
		this.trials = trials;
		this.accounts = accounts;
		this.sessions = sessions;
		this.reservations = reservations;
		this.reservationService = reservationService;
		this.throttle = throttle;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public DemoStart start(String clientIp) {
		throttle.countDemoStart(clientIp, properties.maxStartsPerIp());
		Instant now = Instant.now(clock);
		Instant expiresAt = now.plus(properties.ttl());
		long trialId = trials.create(now, expiresAt);
		String tag = UUID.randomUUID().toString().substring(0, 8);

		Account visitor = accounts.save(Account.demo("demo-" + tag, "체험 방문자", trialId, now));
		// 시작 시각은 충분히 뒤로 둬 체험 중에 마감(시작) 규칙에 걸리지 않게 한다
		StudySession session = sessions.save(StudySession.createDemo("체험 회차 · 마감된 스터디",
				now.plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS), "온라인 체험룸", SAMPLE_SEATS, trialId,
				now));
		Reservation first = null;
		for (int i = 1; i <= SAMPLE_SEATS; i++) {
			Account sample = accounts.save(Account.demo("demo-" + tag + "-s" + i, "샘플 참가자 " + i, trialId, now));
			Reservation reservation = reservations.save(Reservation.confirm(session, sample, now));
			if (first == null) {
				first = reservation;
			}
		}
		trials.designateSample(trialId, first.getId());

		SessionUser user = new SessionUser(visitor.getId(), visitor.getLoginId(), visitor.getDisplayName(),
				visitor.getRole(), trialId);
		usage.increment(Metric.DEMO_STARTED, true);
		return new DemoStart(user, session.getId(), expiresAt);
	}

	/**
	 * 방문자 체험의 지정 샘플 예약만 기존 취소 로직(회차 잠금·같은 트랜잭션 승격)으로 취소한다.
	 * 대상은 세션의 체험에서 정해지므로 다른 체험·임의 예약을 가리킬 수 없다. 이미 취소됐으면 그대로 돌려준다.
	 */
	public ReservationResponse cancelSample(SessionUser visitor) {
		if (!visitor.isDemo()) {
			throw new ApiException(ErrorCode.DEMO_NOT_FOUND);
		}
		DemoTrials.Sample sample = trials.sample(visitor.demoTrialId())
			.orElseThrow(() -> new ApiException(ErrorCode.DEMO_NOT_FOUND));
		return reservationService.cancel(sample.reservationId(), sample.accountId());
	}

	/** 만료 체험 정리. 10분마다 (여러 인스턴스가 함께 돌아도 결과는 같다). */
	@Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
	@Transactional
	public void purgeExpired() {
		trials.purgeExpired(Instant.now(clock));
	}

}

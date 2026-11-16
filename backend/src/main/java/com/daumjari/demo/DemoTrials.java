package com.daumjari.demo;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** demo_trial 테이블 접근. */
@Component
public class DemoTrials {

	/** 회차 응답에 붙는 체험 정보. */
	public record DemoInfo(Instant expiresAt, boolean sampleReservationActive) {
	}

	/** 지정 샘플 예약과 그 예약의 계정. */
	public record Sample(long reservationId, long accountId) {
	}

	private final JdbcTemplate jdbc;

	public DemoTrials(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public long create(Instant now, Instant expiresAt) {
		return jdbc.queryForObject(
				"insert into demo_trial (created_at, expires_at) values (?, ?) returning id", Long.class, utc(now),
				utc(expiresAt));
	}

	public void designateSample(long trialId, long reservationId) {
		jdbc.update("update demo_trial set sample_reservation_id = ? where id = ?", reservationId, trialId);
	}

	public boolean isActive(long trialId, Instant now) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"select exists (select 1 from demo_trial where id = ? and expires_at > ?)", Boolean.class, trialId,
				utc(now)));
	}

	public Optional<DemoInfo> info(long trialId) {
		List<DemoInfo> rows = jdbc.query("""
				select t.expires_at, coalesce(r.status = 'CONFIRMED', false)
				from demo_trial t left join reservation r on r.id = t.sample_reservation_id
				where t.id = ?
				""", (rs, n) -> new DemoInfo(rs.getObject(1, OffsetDateTime.class).toInstant(), rs.getBoolean(2)),
				trialId);
		return rows.stream().findFirst();
	}

	public Optional<Sample> sample(long trialId) {
		List<Sample> rows = jdbc.query("""
				select r.id, r.account_id from demo_trial t join reservation r on r.id = t.sample_reservation_id
				where t.id = ?
				""", (rs, n) -> new Sample(rs.getLong(1), rs.getLong(2)), trialId);
		return rows.stream().findFirst();
	}

	/** 만료 체험의 예약·회차·계정·체험을 지운다. 여러 인스턴스가 동시에 돌아도 같은 결과다. */
	public int purgeExpired(Instant now) {
		OffsetDateTime cutoff = utc(now);
		String expired = "select id from demo_trial where expires_at <= ?";
		jdbc.update("delete from reservation where study_session_id in (select id from study_session where demo_trial_id in ("
				+ expired + ")) or account_id in (select id from account where demo_trial_id in (" + expired + "))",
				cutoff, cutoff);
		jdbc.update("delete from study_session where demo_trial_id in (" + expired + ")", cutoff);
		jdbc.update("delete from account where demo_trial_id in (" + expired + ")", cutoff);
		return jdbc.update("delete from demo_trial where expires_at <= ?", cutoff);
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}

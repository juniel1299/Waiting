package com.daumjari.usage;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;

/**
 * 운영자 사용량 대시보드 (OPERATOR 전용, /api/operator/**). 두 종류를 나눠 준다.
 * - 현재 데이터: 지금 남아 있는 행 수. 기존 데이터에서 언제든 다시 셀 수 있고, 삭제되면 줄어든다.
 * - 누적 사용량: usage_daily 의 성공 횟수 (V8 이후 측정). 원본을 지워도 남는다. 집계 시작 전 값은 만들지 않는다.
 * 둘 다 일반·데모를 나눈다. 누적은 서울 날짜 범위(최대 366일, 기본 오늘까지 30일)의 합계와 일별 값.
 */
@RestController
public class UsageDashboard {

	static final int DEFAULT_DAYS = 30;

	static final int MAX_DAYS = 366;

	public record Current(long accounts, long operators, long suspended, long sessions, long confirmed, long waiting) {
	}

	public record CurrentData(Current regular, Current demo) {
	}

	public record MetricTotal(Metric metric, long regular, long demo) {
	}

	public record DailyCount(LocalDate day, Metric metric, long regular, long demo) {
	}

	public record Response(Instant countingStartedAt, LocalDate from, LocalDate to, CurrentData current,
			List<MetricTotal> totals, List<DailyCount> daily) {
	}

	private final JdbcTemplate jdbc;

	private final Clock clock;

	public UsageDashboard(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@GetMapping("/api/operator/usage")
	@Transactional(readOnly = true)
	public Response usage(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
		LocalDate end = date(to, LocalDate.now(clock.withZone(UsageCounter.SEOUL)));
		LocalDate start = date(from, end.minusDays(DEFAULT_DAYS - 1));
		if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		Timestamp startedAt = jdbc.queryForObject(
				"select value from usage_meta where key = 'counting_started_at'", Timestamp.class);
		List<DailyCount> daily = daily(start, end);
		return new Response(startedAt == null ? null : startedAt.toInstant(), start, end,
				new CurrentData(current(false), current(true)), totals(daily), daily);
	}

	private Current current(boolean demo) {
		String trial = demo ? "is not null" : "is null";
		return jdbc.queryForObject("""
				select
				  (select count(*) from account where demo_trial_id %1$s and deleted_at is null and role = 'PARTICIPANT') as accounts,
				  (select count(*) from account where demo_trial_id %1$s and deleted_at is null and role = 'OPERATOR') as operators,
				  (select count(*) from account where demo_trial_id %1$s and deleted_at is null and suspended_at is not null) as suspended,
				  (select count(*) from study_session where demo_trial_id %1$s) as sessions,
				  (select count(*) from reservation r join study_session s on s.id = r.study_session_id
				     where s.demo_trial_id %1$s and r.status = 'CONFIRMED') as confirmed,
				  (select count(*) from reservation r join study_session s on s.id = r.study_session_id
				     where s.demo_trial_id %1$s and r.status = 'WAITING') as waiting
				""".formatted(trial), (rs, n) -> new Current(rs.getLong("accounts"), rs.getLong("operators"),
				rs.getLong("suspended"), rs.getLong("sessions"), rs.getLong("confirmed"), rs.getLong("waiting")));
	}

	/** 날짜·지표별 일반/데모 횟수. 기록이 있는 날짜만 (없는 날은 0). 날짜순, 같은 날은 지표 정의 순서. */
	private List<DailyCount> daily(LocalDate start, LocalDate end) {
		return jdbc.query("""
				select day, metric,
				  coalesce(sum(count) filter (where not demo), 0) as regular,
				  coalesce(sum(count) filter (where demo), 0) as demo
				from usage_daily where day between ? and ?
				group by day, metric
				""", (rs, n) -> new DailyCount(rs.getDate("day").toLocalDate(), Metric.valueOf(rs.getString("metric")),
				rs.getLong("regular"), rs.getLong("demo")), start, end)
			.stream()
			.sorted(Comparator.comparing(DailyCount::day).thenComparing(DailyCount::metric))
			.toList();
	}

	/** 모든 지표를 정의 순서대로 (기록이 없으면 0). */
	private static List<MetricTotal> totals(List<DailyCount> daily) {
		return Arrays.stream(Metric.values())
			.map(m -> new MetricTotal(m,
					daily.stream().filter(d -> d.metric() == m).mapToLong(DailyCount::regular).sum(),
					daily.stream().filter(d -> d.metric() == m).mapToLong(DailyCount::demo).sum()))
			.toList();
	}

	private static LocalDate date(String raw, LocalDate fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			return LocalDate.parse(raw.strip());
		}
		catch (DateTimeParseException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
	}

}

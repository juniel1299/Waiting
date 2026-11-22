package com.daumjari.usage;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 누적 사용량을 1 늘린다. 호출한 동작의 트랜잭션 안에서만 동작해(MANDATORY), 동작이 롤백되면 집계도 함께 롤백된다.
 * 날짜는 서울 기준이라 기간별(날짜 단위) 조회가 서비스 화면의 날짜와 맞는다.
 */
@Component
public class UsageCounter {

	static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final String INCREMENT = """
			insert into usage_daily (day, metric, demo, count) values (?, ?, ?, 1)
			on conflict (day, metric, demo) do update set count = usage_daily.count + 1
			""";

	private final JdbcTemplate jdbc;

	private final Clock clock;

	public UsageCounter(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void increment(Metric metric, boolean demo) {
		jdbc.update(INCREMENT, LocalDate.now(clock.withZone(SEOUL)), metric.name(), demo);
	}

}

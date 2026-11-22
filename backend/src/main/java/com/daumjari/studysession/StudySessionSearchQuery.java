package com.daumjari.studysession;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.daumjari.studysession.SessionSearch.Scope;

/**
 * 회차 검색 SQL. 조건 조합이 많아 JPQL 대신 바인딩 변수만 쓰는 SQL 을 조립한다 (정렬·조건 조각은 enum 에서만 고른다).
 * 확정·대기 수는 회차별 lateral 집계로 ix_reservation_session_status 를 탄다. 결과는 id 와 전체 건수만 돌려주고,
 * 응답은 기존 목록과 같은 변환을 쓴다.
 */
@Repository
class StudySessionSearchQuery {

	record Result(List<Long> ids, long total) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	StudySessionSearchQuery(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** trialId: 조회자의 데모 체험 (일반 계정은 null). 전체 회차는 같은 체험 값의 회차만 보인다. */
	Result search(SessionSearch search, Long viewerId, Long trialId, Instant now) {
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("now", at(now));
		String base = """
				with s as (
				  select ss.id, ss.title, ss.starts_at, ss.created_at, ss.capacity, c.confirmed, c.waiting
				  from study_session ss
				  cross join lateral (
				    select count(*) filter (where r.status = 'CONFIRMED') as confirmed,
				           count(*) filter (where r.status = 'WAITING') as waiting
				    from reservation r
				    where r.study_session_id = ss.id and r.status <> 'CANCELLED'
				  ) c
				  where %s
				)
				select %%s from s where %s
				""".formatted(String.join(" and ", rowConditions(search, viewerId, trialId, params)),
				statusCondition(search.status()));
		long total = jdbc.queryForObject(base.formatted("count(*)"), params, Long.class);
		if (total == 0) {
			return new Result(List.of(), 0);
		}
		params.addValue("limit", search.size()).addValue("offset", (long) (search.page() - 1) * search.size());
		List<Long> ids = jdbc.queryForList(
				base.formatted("id") + " order by " + order(search.sort()) + ", id limit :limit offset :offset",
				params, Long.class);
		return new Result(ids, total);
	}

	private static List<String> rowConditions(SessionSearch search, Long viewerId, Long trialId,
			MapSqlParameterSource params) {
		List<String> where = new ArrayList<>();
		if (search.scope() == Scope.MINE) {
			where.add("ss.creator_account_id = :viewerId");
			params.addValue("viewerId", viewerId);
		}
		else if (trialId == null) {
			where.add("ss.demo_trial_id is null");
		}
		else {
			where.add("ss.demo_trial_id = :trialId");
			params.addValue("trialId", trialId);
		}
		if (!search.keyword().isEmpty()) {
			params.addValue("pattern", "%" + escapeLike(search.keyword()) + "%");
			where.add(switch (search.field()) {
				case TITLE -> "ss.title ilike :pattern escape '\\'";
				case LOCATION -> "ss.location ilike :pattern escape '\\'";
				case ALL -> "(ss.title ilike :pattern escape '\\' or ss.location ilike :pattern escape '\\')";
			});
		}
		if (search.startsFrom() != null) {
			where.add("ss.starts_at >= :startsFrom");
			params.addValue("startsFrom", at(search.startsFrom()));
		}
		if (search.startsBefore() != null) {
			where.add("ss.starts_at < :startsBefore");
			params.addValue("startsBefore", at(search.startsBefore()));
		}
		return where;
	}

	/** SessionStatus 계산(StudySessionService.statusOf)과 같은 규칙: 시작 = now >= starts_at, 대기자가 있으면 마감. */
	private static String statusCondition(SessionSearch.StatusFilter status) {
		return switch (status) {
			case ALL -> "true";
			case UPCOMING -> "starts_at > :now";
			case ENDED -> "starts_at <= :now";
			case OPEN -> "starts_at > :now and confirmed < capacity and waiting = 0";
			case WAITLIST -> "starts_at > :now and (confirmed >= capacity or waiting > 0)";
		};
	}

	private static String order(SessionSearch.Sort sort) {
		return switch (sort) {
			case SOON -> "starts_at asc";
			case LATE -> "starts_at desc";
			case NEWEST -> "created_at desc";
			case OLDEST -> "created_at asc";
			case SEATS -> "greatest(capacity - confirmed, 0) desc";
			case POPULAR -> "confirmed desc";
			case TITLE -> "title asc";
		};
	}

	/** LIKE 의 특수 문자(\ % _)를 글자 그대로 찾게 한다. */
	static String escapeLike(String keyword) {
		return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static Object at(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}

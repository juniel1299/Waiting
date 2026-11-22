package com.daumjari.moderation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.daumjari.account.Role;
import com.daumjari.moderation.ModerationDtos.AccountStatus;
import com.daumjari.moderation.ModerationDtos.AccountSummary;
import com.daumjari.moderation.ModerationDtos.Action;
import com.daumjari.moderation.ModerationDtos.ActionEntry;
import com.daumjari.moderation.ModerationDtos.Paging;

/** 운영자 계정 관리의 읽기 쿼리 (목록·영향 범위·조치 기록). 데모 체험 계정은 대상이 아니라 빼고 본다. */
@Repository
class AccountAdminQuery {

	record Found<T>(List<T> items, long total) {
	}

	record Counts(long upcomingConfirmed, long startedConfirmedKept, long waiting, long promotions, long ownedSessions,
			long ownedSessionReservations) {
	}

	private static final String SUMMARY_COLUMNS = """
			a.id, a.login_id, a.display_name, a.role, a.created_at, a.suspended_at, a.deleted_at,
			(select count(*) from reservation r where r.account_id = a.id and r.status <> 'CANCELLED') as active_reservations,
			(select count(*) from study_session s where s.creator_account_id = a.id) as created_sessions
			""";

	private final NamedParameterJdbcTemplate jdbc;

	AccountAdminQuery(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 아이디·이름 부분 일치(탈퇴 계정은 익명 값이라 검색되지 않는다), 상태 필터, id 순. */
	Found<AccountSummary> search(String keyword, AccountStatus status, Paging paging) {
		MapSqlParameterSource params = new MapSqlParameterSource();
		List<String> where = new ArrayList<>(List.of("a.demo_trial_id is null"));
		if (!keyword.isEmpty()) {
			params.addValue("pattern", "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
			where.add("a.deleted_at is null and (a.login_id ilike :pattern escape '\\' or a.display_name ilike :pattern escape '\\')");
		}
		if (status != null) {
			where.add(switch (status) {
				case ACTIVE -> "a.deleted_at is null and a.suspended_at is null";
				case SUSPENDED -> "a.deleted_at is null and a.suspended_at is not null";
				case WITHDRAWN -> "a.deleted_at is not null";
			});
		}
		String condition = String.join(" and ", where);
		long total = jdbc.queryForObject("select count(*) from account a where " + condition, params, Long.class);
		params.addValue("limit", paging.size()).addValue("offset", paging.offset());
		return new Found<>(jdbc.query("select " + SUMMARY_COLUMNS + " from account a where " + condition
				+ " order by a.id limit :limit offset :offset", params, AccountAdminQuery::summary), total);
	}

	Optional<AccountSummary> summary(Long id) {
		return jdbc.query("select " + SUMMARY_COLUMNS + " from account a where a.id = :id and a.demo_trial_id is null",
				new MapSqlParameterSource("id", id), AccountAdminQuery::summary).stream().findFirst();
	}

	/** 강제탈퇴 영향 범위 (ReservationService.releaseAllOf 와 같은 규칙으로 센다). */
	Counts impact(Long id, Instant now) {
		MapSqlParameterSource params = new MapSqlParameterSource("id", id).addValue("now", now.atOffset(ZoneOffset.UTC));
		return jdbc.queryForObject("""
				with mine as (
				  select r.status, s.id as session_id, s.starts_at
				  from reservation r join study_session s on s.id = r.study_session_id
				  where r.account_id = :id and r.status <> 'CANCELLED'
				    and s.creator_account_id is distinct from :id
				)
				select
				  (select count(*) from mine where status = 'CONFIRMED' and starts_at > :now) as upcoming_confirmed,
				  (select count(*) from mine where status = 'CONFIRMED' and starts_at <= :now) as started_kept,
				  (select count(*) from mine where status = 'WAITING') as waiting,
				  (select count(*) from mine m where m.status = 'CONFIRMED' and m.starts_at > :now
				     and exists (select 1 from reservation w where w.study_session_id = m.session_id and w.status = 'WAITING')) as promotions,
				  (select count(*) from study_session s where s.creator_account_id = :id) as owned_sessions,
				  (select count(*) from reservation r join study_session s on s.id = r.study_session_id
				     where s.creator_account_id = :id and r.status <> 'CANCELLED') as owned_reservations
				""", params, (rs, n) -> new Counts(rs.getLong("upcoming_confirmed"), rs.getLong("started_kept"),
				rs.getLong("waiting"), rs.getLong("promotions"), rs.getLong("owned_sessions"),
				rs.getLong("owned_reservations")));
	}

	void record(Long operatorId, Long targetId, Action action, String reason, int cancelled, int deletedSessions,
			Instant now) {
		jdbc.update("""
				insert into moderation_action (operator_account_id, target_account_id, action, reason,
				  cancelled_reservations, deleted_sessions, created_at)
				values (:operator, :target, :action, :reason, :cancelled, :deleted, :now)
				""", new MapSqlParameterSource("operator", operatorId).addValue("target", targetId)
			.addValue("action", action.name())
			.addValue("reason", reason)
			.addValue("cancelled", cancelled)
			.addValue("deleted", deletedSessions)
			.addValue("now", now.atOffset(ZoneOffset.UTC)));
	}

	/** 최근 조치부터. targetId 가 있으면 그 계정만. 라벨은 지금 계정 행에서 만든다(탈퇴했으면 익명). */
	Found<ActionEntry> actions(Long targetId, Paging paging) {
		MapSqlParameterSource params = new MapSqlParameterSource("target", targetId);
		String condition = targetId == null ? "true" : "m.target_account_id = :target";
		long total = jdbc.queryForObject("select count(*) from moderation_action m where " + condition, params,
				Long.class);
		params.addValue("limit", paging.size()).addValue("offset", paging.offset());
		List<ActionEntry> items = jdbc.query("""
				select m.id, m.action, m.reason, m.created_at, m.cancelled_reservations, m.deleted_sessions,
				  o.id as operator_id, o.login_id as operator_login, o.deleted_at as operator_deleted,
				  t.id as target_id, t.login_id as target_login, t.deleted_at as target_deleted
				from moderation_action m
				join account o on o.id = m.operator_account_id
				join account t on t.id = m.target_account_id
				where %s
				order by m.created_at desc, m.id desc
				limit :limit offset :offset
				""".formatted(condition), params, (rs, n) -> new ActionEntry(rs.getLong("id"),
				Action.valueOf(rs.getString("action")), rs.getString("reason"), instant(rs, "created_at"),
				rs.getLong("operator_id"), label(rs.getLong("operator_id"), rs.getString("operator_login"),
						rs.getTimestamp("operator_deleted")),
				rs.getLong("target_id"), label(rs.getLong("target_id"), rs.getString("target_login"),
						rs.getTimestamp("target_deleted")),
				rs.getInt("cancelled_reservations"), rs.getInt("deleted_sessions")));
		return new Found<>(items, total);
	}

	static String label(Long id, String loginId, Object deletedAt) {
		return deletedAt != null ? "탈퇴한 회원 #" + id : loginId;
	}

	private static AccountSummary summary(ResultSet rs, int row) throws SQLException {
		Instant deletedAt = instant(rs, "deleted_at");
		Instant suspendedAt = instant(rs, "suspended_at");
		AccountStatus status = deletedAt != null ? AccountStatus.WITHDRAWN
				: suspendedAt != null ? AccountStatus.SUSPENDED : AccountStatus.ACTIVE;
		return new AccountSummary(rs.getLong("id"), deletedAt != null ? null : rs.getString("login_id"),
				rs.getString("display_name"), Role.valueOf(rs.getString("role")), status, instant(rs, "created_at"),
				suspendedAt, deletedAt, rs.getLong("active_reservations"), rs.getLong("created_sessions"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}

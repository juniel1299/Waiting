package com.daumjari.role;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.daumjari.account.AccountRepository;
import com.daumjari.auth.SessionUser;
import com.daumjari.board.BoardDtos.Search;
import com.daumjari.board.Mentions;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;

/**
 * 서비스 역할 관리. 운영자 여부는 account.role, 더해진 역할(개발자)은 account_role 에 있다.
 * - 운영자만 부여·회수하고, 자기 자신의 역할은 바꾸지 못한다(자기 권한 상승·자기 회수 금지).
 * - 활성 운영자 행을 id 순으로 잠근 뒤 처리자가 지금도 운영자인지 다시 본다. 운영자 둘이 서로를 동시에 회수해도
 *   뒤 요청은 앞선 회수로 운영자가 아니게 되어 403 이므로 운영자가 0명이 되지 않는다. 마지막 활성 운영자 회수는 409.
 * - 바뀐 것만 기록한다(사유·처리자·시각). 같은 요청을 다시 보내면 그대로 200.
 */
@Service
public class ServiceRoleService {

	public enum ServiceRole {
		OPERATOR, DEVELOPER
	}

	public record AccountRoles(Long accountId, String loginId, String label, List<ServiceRole> roles) {
	}

	public record RolePage(List<AccountRoles> items, int page, int size, long totalElements, int totalPages) {
	}

	public record Change(Long id, String target, ServiceRole role, String action, String reason, String actor, Instant createdAt) {
	}

	public record ChangePage(List<Change> items) {
	}

	public record ChangeRequest(String reason) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	private final AccountRepository accounts;

	private final Clock clock;

	public ServiceRoleService(NamedParameterJdbcTemplate jdbc, AccountRepository accounts, Clock clock) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.clock = clock;
	}

	/** 이 계정의 서비스 역할 (운영자 먼저). */
	@Transactional(readOnly = true)
	public List<ServiceRole> rolesOf(Long accountId) {
		return accounts.accessState(accountId)
			.map(s -> {
				List<ServiceRole> roles = new ArrayList<>();
				if ("OPERATOR".equals(s.getRole())) {
					roles.add(ServiceRole.OPERATOR);
				}
				if (s.getDeveloper()) {
					roles.add(ServiceRole.DEVELOPER);
				}
				return List.copyOf(roles);
			})
			.orElse(List.of());
	}

	/** 계정별 역할: 탈퇴·데모 제외, 아이디·닉네임 검색, 역할 있는 계정 먼저. */
	@Transactional(readOnly = true)
	public RolePage list(String q, String page, String size) {
		Search search = Search.parse(q, null, page, size);
		MapSqlParameterSource params = new MapSqlParameterSource();
		String where = "a.deleted_at is null and a.demo_trial_id is null";
		if (!search.keyword().isEmpty()) {
			params.addValue("pattern", "%" + search.keyword().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
			where += " and (a.login_id ilike :pattern escape '\\' or coalesce(a.nickname, '') ilike :pattern escape '\\')";
		}
		long total = jdbc.queryForObject("select count(*) from account a where " + where, params, Long.class);
		params.addValue("limit", search.size()).addValue("offset", search.offset());
		List<AccountRoles> items = jdbc.query("""
				select a.id, a.login_id, a.role, %s as label,
				       exists (select 1 from account_role r where r.account_id = a.id and r.role = 'DEVELOPER') as developer
				from account a where %s
				order by (a.role = 'OPERATOR' or exists (select 1 from account_role r where r.account_id = a.id)) desc, a.id
				limit :limit offset :offset
				""".formatted(Mentions.labelSql("a"), where), params, (rs, n) -> {
			List<ServiceRole> roles = new ArrayList<>();
			if ("OPERATOR".equals(rs.getString("role"))) {
				roles.add(ServiceRole.OPERATOR);
			}
			if (rs.getBoolean("developer")) {
				roles.add(ServiceRole.DEVELOPER);
			}
			return new AccountRoles(rs.getLong("id"), rs.getString("login_id"), rs.getString("label"), List.copyOf(roles));
		});
		return new RolePage(items, search.page(), search.size(), total, (int) ((total + search.size() - 1) / search.size()));
	}

	@Transactional(readOnly = true)
	public ChangePage changes() {
		return new ChangePage(jdbc.query("""
				select c.id, c.role, c.action, c.reason, c.created_at, %s as target, %s as actor
				from account_role_change c join account t on t.id = c.target_account_id join account o on o.id = c.actor_account_id
				order by c.created_at desc, c.id desc limit 100
				""".formatted(Mentions.labelSql("t"), Mentions.labelSql("o")), new MapSqlParameterSource(),
				(rs, n) -> new Change(rs.getLong("id"), rs.getString("target"), ServiceRole.valueOf(rs.getString("role")),
						rs.getString("action"), rs.getString("reason"), rs.getString("actor"),
						rs.getTimestamp("created_at").toInstant())));
	}

	@Transactional
	public AccountRoles change(Long targetId, String roleName, boolean grant, ChangeRequest request, SessionUser actor) {
		ServiceRole role = parse(roleName);
		String reason = request == null || request.reason() == null ? "" : request.reason().strip();
		if (reason.isEmpty() || reason.length() > 200) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		List<Long> operators = accounts.lockActiveOperatorIds();
		if (!operators.contains(actor.id())) {
			throw new ApiException(ErrorCode.ROLE_CHANGE_FORBIDDEN);
		}
		if (targetId.equals(actor.id())) {
			throw new ApiException(ErrorCode.SELF_ROLE_CHANGE);
		}
		MapSqlParameterSource params = new MapSqlParameterSource("id", targetId).addValue("role", role.name());
		Boolean eligible = jdbc.query("select deleted_at is null and demo_trial_id is null from account where id = :id for update", params,
				(rs, n) -> rs.getBoolean(1)).stream().findFirst().orElse(false);
		if (!eligible) {
			throw new ApiException(ErrorCode.ACCOUNT_NOT_FOUND);
		}
		boolean changed;
		if (role == ServiceRole.OPERATOR) {
			if (!grant && operators.size() == 1 && operators.contains(targetId)) {
				throw new ApiException(ErrorCode.LAST_OPERATOR);
			}
			changed = jdbc.update("update account set role = :next where id = :id and role <> :next",
					params.addValue("next", grant ? "OPERATOR" : "PARTICIPANT")) == 1;
		}
		else {
			changed = (grant
					? jdbc.update("insert into account_role (account_id, role) values (:id, :role) on conflict do nothing", params)
					: jdbc.update("delete from account_role where account_id = :id and role = :role", params)) == 1;
		}
		if (changed) {
			jdbc.update("""
					insert into account_role_change (target_account_id, role, action, reason, actor_account_id, created_at)
					values (:id, :role, :action, :reason, :actor, :now)
					""", params.addValue("action", grant ? "GRANT" : "REVOKE")
				.addValue("reason", reason)
				.addValue("actor", actor.id())
				.addValue("now", Instant.now(clock).atOffset(ZoneOffset.UTC)));
		}
		return new AccountRoles(targetId, null, null, rolesOf(targetId));
	}

	private static ServiceRole parse(String raw) {
		try {
			return ServiceRole.valueOf(raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
	}

}

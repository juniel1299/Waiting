package com.daumjari.favorite;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.SessionUser;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;

/**
 * 즐겨찾기(모임·회차). 계정·대상별 한 행(DB 유일, on conflict do nothing)이라 PUT·DELETE 는 몇 번이든 같은 결과다.
 * 지금 볼 수 있는 대상만 담고(승인된 모임, 데모가 아닌 회차), 목록도 매번 같은 조건으로 다시 거른다.
 * 대상·계정이 지워지면 FK on delete cascade 로 함께 지워진다.
 */
@RestController
public class FavoriteController {

	record FavoriteState(boolean favorite) {
	}

	record SessionItem(Long id, String title, Instant startsAt, String location) {
	}

	record ClubItem(Long id, String name, String place) {
	}

	record Favorites(List<SessionItem> sessions, List<ClubItem> clubs) {
	}

	static final int LIST_LIMIT = 100;

	private static final String VISIBLE_SESSION = "select id from study_session where id = :target and demo_trial_id is null";

	private static final String VISIBLE_CLUB = "select id from club where id = :target and status = 'APPROVED'";

	private final NamedParameterJdbcTemplate jdbc;

	private final Clock clock;

	public FavoriteController(NamedParameterJdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@PutMapping("/api/sessions/{id}/favorite")
	@Transactional
	public FavoriteState addSession(@PathVariable Long id, @AuthenticationPrincipal SessionUser user) {
		return add("session_id", VISIBLE_SESSION, id, user, ErrorCode.SESSION_NOT_FOUND);
	}

	@DeleteMapping("/api/sessions/{id}/favorite")
	@Transactional
	public FavoriteState removeSession(@PathVariable Long id, @AuthenticationPrincipal SessionUser user) {
		return remove("session_id", id, user);
	}

	@PutMapping("/api/clubs/{id}/favorite")
	@Transactional
	public FavoriteState addClub(@PathVariable Long id, @AuthenticationPrincipal SessionUser user) {
		return add("club_id", VISIBLE_CLUB, id, user, ErrorCode.CLUB_NOT_FOUND);
	}

	@DeleteMapping("/api/clubs/{id}/favorite")
	@Transactional
	public FavoriteState removeClub(@PathVariable Long id, @AuthenticationPrincipal SessionUser user) {
		return remove("club_id", id, user);
	}

	@GetMapping("/api/me/favorites")
	@Transactional(readOnly = true)
	public Favorites list(@AuthenticationPrincipal SessionUser user) {
		MapSqlParameterSource params = new MapSqlParameterSource("me", user.id()).addValue("limit", LIST_LIMIT);
		List<SessionItem> sessions = jdbc.query("select s.id, s.title, s.starts_at, s.location from favorite f "
				+ "join study_session s on s.id = f.session_id and s.demo_trial_id is null "
				+ "where f.account_id = :me order by f.created_at desc, f.id desc limit :limit", params,
				(rs, n) -> new SessionItem(rs.getLong("id"), rs.getString("title"),
						rs.getTimestamp("starts_at").toInstant(), rs.getString("location")));
		List<ClubItem> clubs = jdbc.query("select c.id, c.name, c.place from favorite f "
				+ "join club c on c.id = f.club_id and c.status = 'APPROVED' "
				+ "where f.account_id = :me order by f.created_at desc, f.id desc limit :limit", params,
				(rs, n) -> new ClubItem(rs.getLong("id"), rs.getString("name"), rs.getString("place")));
		return new Favorites(sessions, clubs);
	}

	/** 대상 행을 공유 잠금으로 읽어 같은 트랜잭션 안에서 지워지지 않게 한 뒤 넣는다. */
	private FavoriteState add(String column, String visibleSql, Long target, SessionUser user, ErrorCode notFound) {
		if (user.isDemo()) {
			throw new ApiException(ErrorCode.FAVORITE_FORBIDDEN);
		}
		MapSqlParameterSource params = new MapSqlParameterSource("target", target).addValue("me", user.id())
			.addValue("now", java.sql.Timestamp.from(Instant.now(clock)));
		if (jdbc.queryForList(visibleSql + " for share", params, Long.class).isEmpty()) {
			throw new ApiException(notFound);
		}
		jdbc.update("insert into favorite (account_id, " + column + ", created_at) values (:me, :target, :now) "
				+ "on conflict do nothing", params);
		return new FavoriteState(true);
	}

	private FavoriteState remove(String column, Long target, SessionUser user) {
		jdbc.update("delete from favorite where account_id = :me and " + column + " = :target",
				new MapSqlParameterSource("me", user.id()).addValue("target", target));
		return new FavoriteState(false);
	}

}

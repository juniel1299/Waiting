package com.daumjari.board;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 멘션: 본문의 `@[이름#계정id]` 토큰을 계정 id 로 연결한다. 이름 부분은 쓸 때의 표시일 뿐이고, 화면은 연결된 계정의 현재 닉네임을 쓴다.
 * 탈퇴·데모·없는 계정은 연결하지 않는다. 응답에는 계정 id 와 공개 라벨만 나간다 (로그인 아이디 없음).
 */
@Component
public class Mentions {

	static final Pattern TOKEN = Pattern.compile("@\\[[^\\]\\n#]{0,40}#(\\d{1,18})\\]");

	static final int MAX_PER_TEXT = 20;

	/** 공개 라벨: 탈퇴 → '탈퇴한 회원', 운영자 → '운영자', 그 밖에는 닉네임 또는 '회원{id}'. */
	static String labelSql(String alias) {
		return ("case when %1$s.deleted_at is not null then '탈퇴한 회원' when %1$s.role = 'OPERATOR' then '운영자' "
				+ "else coalesce(%1$s.nickname, '회원' || %1$s.id) end").formatted(alias);
	}

	public record MentionView(Long id, String nickname) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	public Mentions(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	static Set<Long> parse(String text) {
		Set<Long> ids = new LinkedHashSet<>();
		Matcher m = TOKEN.matcher(text);
		while (m.find() && ids.size() < MAX_PER_TEXT) {
			ids.add(Long.valueOf(m.group(1)));
		}
		return ids;
	}

	/**
	 * 글·댓글의 멘션을 본문에 맞춘다: 사라진 멘션은 연결을 지우고, 이미 있던 멘션은 그대로 두고, 새로 생긴 멘션만 넣는다.
	 * 반환값은 새로 연결된 계정 id (알림은 이들에게만 보낸다, 3단계).
	 */
	List<Long> sync(String column, long sourceId, String text, Instant now) {
		Set<Long> wanted = parse(text);
		MapSqlParameterSource params = new MapSqlParameterSource("source", sourceId);
		List<Long> valid = wanted.isEmpty() ? List.of()
				: jdbc.queryForList("select id from account where id in (:ids) and deleted_at is null and demo_trial_id is null",
						new MapSqlParameterSource("ids", wanted), Long.class);
		List<Long> existing = jdbc.queryForList("select account_id from mention where " + column + " = :source", params,
				Long.class);
		List<Long> removed = existing.stream().filter(id -> !valid.contains(id)).toList();
		if (!removed.isEmpty()) {
			jdbc.update("delete from mention where " + column + " = :source and account_id in (:ids)",
					new MapSqlParameterSource("source", sourceId).addValue("ids", removed));
		}
		List<Long> added = new ArrayList<>();
		for (Long id : valid) {
			if (!existing.contains(id)) {
				jdbc.update("insert into mention (" + column + ", account_id, created_at) values (:source, :id, :now)"
						+ " on conflict do nothing", new MapSqlParameterSource("source", sourceId).addValue("id", id)
					.addValue("now", now.atOffset(ZoneOffset.UTC)));
				added.add(id);
			}
		}
		return added;
	}

	/** 원본 id 별 멘션 (계정 id 순). */
	Map<Long, List<MentionView>> load(String column, Collection<Long> sourceIds) {
		Map<Long, List<MentionView>> result = new HashMap<>();
		if (sourceIds.isEmpty()) {
			return result;
		}
		jdbc.query("select m." + column + " as source, a.id, " + labelSql("a") + " as label from mention m "
				+ "join account a on a.id = m.account_id where m." + column + " in (:ids) order by a.id",
				new MapSqlParameterSource("ids", sourceIds), rs -> {
					result.computeIfAbsent(rs.getLong("source"), k -> new ArrayList<>())
						.add(new MentionView(rs.getLong("id"), rs.getString("label")));
				});
		return result;
	}

	/** 멘션 후보: 닉네임(없으면 '회원{id}') 부분 일치, 탈퇴·데모 제외, 최대 10명. 응답은 id·닉네임만. */
	List<MentionView> candidates(String q) {
		String keyword = q == null ? "" : q.strip();
		if (keyword.isEmpty() || keyword.length() > 20) {
			return List.of();
		}
		String pattern = "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
		return jdbc.query("select a.id, " + labelSql("a") + " as label from account a "
				+ "where a.deleted_at is null and a.demo_trial_id is null and a.suspended_at is null "
				+ "and coalesce(a.nickname, '회원' || a.id) ilike :pattern escape '\\' order by a.id limit 10",
				new MapSqlParameterSource("pattern", pattern), (rs, n) -> new MentionView(rs.getLong("id"), rs.getString("label")));
	}

}

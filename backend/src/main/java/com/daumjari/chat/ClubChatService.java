package com.daumjari.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.daumjari.auth.SessionUser;
import com.daumjari.board.Mentions;
import com.daumjari.club.ClubRoles;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;
import com.daumjari.common.FieldValidationException;

/**
 * 모임 채팅. 승인된 모임의 지금 회원만(요청마다 ClubRoles 로 재확인) 읽고 쓴다.
 * 쓰기(보내기·지우기)는 모임 행을 공유 잠금(탈퇴·모임 삭제가 기다림)한 뒤 모임 단위 advisory 잠금으로 한 줄로 세워
 * id·seq 를 커밋 순서대로 받게 한다. 읽기는 seq(변경 순번) 기준으로 이어 받는다.
 */
@Service
public class ClubChatService {

	public record Author(Long id, String label) {
	}

	public record Message(Long id, Author author, String body, boolean deleted, boolean mine, boolean canDelete,
			Instant createdAt) {
	}

	/**
	 * cursor 는 다음 이어 받기의 after 값. hasMore 면 바로 한 번 더 조회한다.
	 * hasMoreBefore 는 처음 조회에서만 의미가 있다(그보다 오래된 메시지가 있는지).
	 */
	public record Page(List<Message> items, long cursor, boolean hasMore, boolean hasMoreBefore) {
	}

	/** 이전 기록 한 쪽. 이어 받기 cursor 를 주지 않아 화면의 읽음 위치(after)를 되돌리지 않는다. */
	public record History(List<Message> items, boolean hasMore) {
	}

	static final int BODY_MAX = 1000;

	static final int RECENT = 50;

	static final int CHANGES_MAX = 200;

	static final int HISTORY_DEFAULT = 50;

	static final int HISTORY_MAX = 100;

	/** 모임 채팅 advisory 잠금의 첫 키(다른 잠금과 섞이지 않게). */
	private static final int LOCK_NAMESPACE = 6001;

	private static final String COLUMNS = "m.id, m.author_account_id, m.body, m.seq, m.created_at, m.deleted_at, "
			+ Mentions.labelSql("a") + " as label";

	private final NamedParameterJdbcTemplate jdbc;

	private final ClubRoles clubRoles;

	private final Clock clock;

	private final int maxPerWindow;

	private final Duration window;

	public ClubChatService(NamedParameterJdbcTemplate jdbc, ClubRoles clubRoles, Clock clock,
			@Value("${daumjari.chat.max-per-window:5}") int maxPerWindow, @Value("${daumjari.chat.window:10s}") Duration window) {
		this.jdbc = jdbc;
		this.clubRoles = clubRoles;
		this.clock = clock;
		this.maxPerWindow = maxPerWindow;
		this.window = window;
	}

	/** after 가 없으면 최근 50건과 지금 cursor, 있으면 그 뒤의 변경(새 메시지·삭제). 한 스냅숏에서 읽는다. */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Page read(Long clubId, Long after, SessionUser user) {
		String role = member(clubId, user, false);
		MapSqlParameterSource params = new MapSqlParameterSource("club", clubId).addValue("after", after);
		if (after == null) {
			List<Message> recent = jdbc.query("select " + COLUMNS + " from club_chat_message m join account a on a.id = m.author_account_id "
					+ "where m.club_id = :club order by m.id desc limit " + RECENT, params, (rs, n) -> message(rs, user, role));
			Long cursor = jdbc.queryForObject("select coalesce(max(seq), 0) from club_chat_message where club_id = :club", params, Long.class);
			List<Message> ordered = recent.stream().sorted(Comparator.comparing(Message::id)).toList();
			boolean older = !ordered.isEmpty() && Boolean.TRUE.equals(jdbc.queryForObject(
					"select exists (select 1 from club_chat_message where club_id = :club and id < :oldest)",
					params.addValue("oldest", ordered.get(0).id()), Boolean.class));
			return new Page(ordered, cursor, false, older);
		}
		List<Map.Entry<Long, Message>> changed = jdbc.query("select " + COLUMNS + " from club_chat_message m "
				+ "join account a on a.id = m.author_account_id where m.club_id = :club and m.seq > :after order by m.seq limit "
				+ (CHANGES_MAX + 1), params, (rs, n) -> Map.entry(rs.getLong("seq"), message(rs, user, role)));
		boolean hasMore = changed.size() > CHANGES_MAX;
		List<Map.Entry<Long, Message>> page = hasMore ? changed.subList(0, CHANGES_MAX) : changed;
		long cursor = page.isEmpty() ? after : page.get(page.size() - 1).getKey();
		return new Page(page.stream().map(Map.Entry::getValue).sorted(Comparator.comparing(Message::id)).toList(), cursor, hasMore, false);
	}

	/** before(메시지 id)보다 오래된 메시지를 최신 쪽부터 limit 건(최대 100), id 오름차순으로. 삭제 표시도 그대로 준다. */
	@Transactional(readOnly = true)
	public History history(Long clubId, long before, Integer limit, SessionUser user) {
		String role = member(clubId, user, false);
		int size = limit == null ? HISTORY_DEFAULT : Math.max(1, Math.min(HISTORY_MAX, limit));
		List<Message> rows = jdbc.query("select " + COLUMNS + " from club_chat_message m join account a on a.id = m.author_account_id "
				+ "where m.club_id = :club and m.id < :before order by m.id desc limit " + (size + 1),
				new MapSqlParameterSource("club", clubId).addValue("before", before), (rs, n) -> message(rs, user, role));
		boolean more = rows.size() > size;
		List<Message> page = more ? rows.subList(0, size) : rows;
		return new History(page.stream().sorted(Comparator.comparing(Message::id)).toList(), more);
	}

	@Transactional
	public Message send(Long clubId, String rawBody, SessionUser user) {
		String body = rawBody == null ? "" : rawBody.strip();
		if (body.isEmpty() || body.length() > BODY_MAX) {
			throw new FieldValidationException(Map.of("body", "메시지는 1~1000자로 써 주세요."));
		}
		String role = member(clubId, user, true);
		lockClubChat(clubId);
		Instant now = Instant.now(clock);
		// shortcut: 동시에 여러 탭에서 보내면 한도를 조금 넘을 수 있다(DB 개수 확인), 남용이 보이면 계정 행 잠금으로 바꾼다
		Long recent = jdbc.queryForObject("select count(*) from club_chat_message where author_account_id = :me and created_at > :since",
				new MapSqlParameterSource("me", user.id()).addValue("since", now.minus(window).atOffset(ZoneOffset.UTC)), Long.class);
		if (recent != null && recent >= maxPerWindow) {
			throw new ApiException(ErrorCode.CHAT_TOO_FAST);
		}
		Long id = jdbc.queryForObject("""
				insert into club_chat_message (club_id, author_account_id, body, seq, created_at)
				values (:club, :me, :body, nextval('club_chat_seq'), :now) returning id
				""", new MapSqlParameterSource("club", clubId).addValue("me", user.id()).addValue("body", body)
			.addValue("now", now.atOffset(ZoneOffset.UTC)), Long.class);
		return one(clubId, id, user, role);
	}

	/** 본인 또는 모임 운영자. 내용을 비우고 삭제 표시·새 seq 를 남긴다(이미 지웠으면 그대로 성공). */
	@Transactional
	public void delete(Long clubId, Long messageId, SessionUser user) {
		String role = member(clubId, user, true);
		lockClubChat(clubId);
		Map<String, Object> row = jdbc.queryForList("select author_account_id, deleted_at from club_chat_message where id = :id and club_id = :club",
				new MapSqlParameterSource("id", messageId).addValue("club", clubId)).stream().findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.CHAT_MESSAGE_NOT_FOUND));
		boolean mine = user.id().equals(((Number) row.get("author_account_id")).longValue());
		if (!mine && !ClubRoles.MANAGER.equals(role)) {
			throw new ApiException(ErrorCode.CHAT_DELETE_FORBIDDEN);
		}
		if (row.get("deleted_at") != null) {
			return;
		}
		jdbc.update("""
				update club_chat_message set body = null, deleted_at = :now, deleted_by_account_id = :me, seq = nextval('club_chat_seq')
				where id = :id
				""", new MapSqlParameterSource("id", messageId).addValue("me", user.id())
			.addValue("now", Instant.now(clock).atOffset(ZoneOffset.UTC)));
	}

	private String member(Long clubId, SessionUser user, boolean lock) {
		String role = clubRoles.role(clubId, user.id(), lock);
		if (role == null) {
			throw new ApiException(ErrorCode.CLUB_MEMBERS_ONLY);
		}
		return role;
	}

	private void lockClubChat(Long clubId) {
		jdbc.queryForList("select pg_advisory_xact_lock(:ns, cast(mod(:club, 2147483647) as integer))",
				new MapSqlParameterSource("ns", LOCK_NAMESPACE).addValue("club", clubId));
	}

	private Message one(Long clubId, Long id, SessionUser user, String role) {
		return jdbc.queryForObject("select " + COLUMNS + " from club_chat_message m join account a on a.id = m.author_account_id "
				+ "where m.id = :id and m.club_id = :club", new MapSqlParameterSource("id", id).addValue("club", clubId),
				(rs, n) -> message(rs, user, role));
	}

	private static Message message(ResultSet rs, SessionUser user, String role) throws SQLException {
		boolean deleted = rs.getTimestamp("deleted_at") != null;
		boolean mine = user.id().equals(rs.getLong("author_account_id"));
		return new Message(rs.getLong("id"), new Author(rs.getLong("author_account_id"), rs.getString("label")), rs.getString("body"),
				deleted, mine, !deleted && (mine || ClubRoles.MANAGER.equals(role)), rs.getTimestamp("created_at").toInstant());
	}

}

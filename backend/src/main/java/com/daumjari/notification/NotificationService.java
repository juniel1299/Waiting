package com.daumjari.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.daumjari.auth.SessionUser;
import com.daumjari.board.BoardDtos.Author;
import com.daumjari.board.BoardService;
import com.daumjari.board.Mentions;
import com.daumjari.club.ClubService;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;
import com.daumjari.studysession.StudySessionService;

/**
 * 내 알림함: 최신 50건, 읽지 않은 수, 하나·모두 읽음, 열기(이동 전에 대상의 현재 조회 권한을 다시 확인한다).
 * 남의 알림은 없는 것처럼 404 로 답한다.
 */
@Service
public class NotificationService {

	/** shortcut: 쪽 나눔 없이 최신 50건만 보인다, 알림이 많이 쌓이면 쪽 나눔을 더한다. */
	static final int LIMIT = 50;

	public record NotificationView(Long id, String type, boolean read, Instant createdAt, Author actor,
			String sessionTitle, String postTitle, boolean onComment, String clubName) {
	}

	public record NotificationPage(List<NotificationView> items, long unreadCount) {
	}

	public record UnreadCount(long unreadCount) {
	}

	public record Opened(String href) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	private final Clock clock;

	private final BoardService posts;

	private final StudySessionService sessions;

	private final ClubService clubs;

	public NotificationService(NamedParameterJdbcTemplate jdbc, Clock clock, BoardService posts,
			StudySessionService sessions, ClubService clubs) {
		this.clubs = clubs;
		this.jdbc = jdbc;
		this.clock = clock;
		this.posts = posts;
		this.sessions = sessions;
	}

	@Transactional(readOnly = true)
	public NotificationPage list(SessionUser me) {
		List<NotificationView> items = jdbc.query("""
				select n.id, n.type, n.read_at, n.created_at, n.comment_id, s.title as session_title, p.title as post_title,
				       cl.name as club_name,
				       case when act.id is null then null else %s end as actor_label
				from notification n
				left join study_session s on s.id = n.session_id
				left join board_post p on p.id = n.post_id
				left join club cl on cl.id = n.club_id
				left join account act on act.id = n.actor_account_id
				where n.recipient_account_id = :me
				order by n.created_at desc, n.id desc
				limit :limit
				""".formatted(Mentions.labelSql("act")), new MapSqlParameterSource("me", me.id()).addValue("limit", LIMIT),
				NotificationService::view);
		return new NotificationPage(items, unread(me).unreadCount());
	}

	@Transactional(readOnly = true)
	public UnreadCount unread(SessionUser me) {
		return new UnreadCount(jdbc.queryForObject(
				"select count(*) from notification where recipient_account_id = :me and read_at is null",
				new MapSqlParameterSource("me", me.id()), Long.class));
	}

	@Transactional
	public void read(Long id, SessionUser me) {
		target(id, me);
		markRead("id = :id and recipient_account_id = :me", id, me);
	}

	@Transactional
	public void readAll(SessionUser me) {
		markRead("recipient_account_id = :me", null, me);
	}

	/** 대상의 현재 조회 권한을 확인한 뒤 읽음으로 바꾸고 이동할 주소를 준다. 권한이 없으면 그 오류(401·404)가 그대로 나간다. */
	@Transactional
	public Opened open(Long id, SessionUser me) {
		Map<String, Object> t = target(id, me);
		String href;
		if (t.get("club_id") != null) {
			href = clubs.hrefFor(((Number) t.get("club_id")).longValue(), me);
		}
		else if (t.get("session_id") != null) {
			Long sessionId = ((Number) t.get("session_id")).longValue();
			sessions.get(sessionId, me);
			href = "/sessions/" + sessionId;
		}
		else {
			Long postId = ((Number) t.get("post_id")).longValue();
			href = posts.pathOf(postId, me) + (t.get("comment_id") == null ? "" : "#comment-" + t.get("comment_id"));
		}
		markRead("id = :id and recipient_account_id = :me", id, me);
		return new Opened(href);
	}

	private Map<String, Object> target(Long id, SessionUser me) {
		return jdbc.queryForList("""
				select session_id, post_id, comment_id, club_id from notification where id = :id and recipient_account_id = :me
				""", new MapSqlParameterSource("id", id).addValue("me", me.id()))
			.stream()
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.NOTIFICATION_NOT_FOUND));
	}

	private void markRead(String where, Long id, SessionUser me) {
		jdbc.update("update notification set read_at = :now where " + where + " and read_at is null",
				new MapSqlParameterSource("id", id).addValue("me", me.id())
					.addValue("now", Instant.now(clock).atOffset(ZoneOffset.UTC)));
	}

	private static NotificationView view(ResultSet rs, int n) throws SQLException {
		String actor = rs.getString("actor_label");
		rs.getLong("comment_id");
		boolean onComment = !rs.wasNull();
		return new NotificationView(rs.getLong("id"), rs.getString("type"), rs.getTimestamp("read_at") != null,
				rs.getTimestamp("created_at").toInstant(), actor == null ? null : new Author(actor),
				rs.getString("session_title"), rs.getString("post_title"), onComment, rs.getString("club_name"));
	}

}

package com.daumjari.board;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.daumjari.auth.SessionUser;
import com.daumjari.board.BoardDtos.Author;
import com.daumjari.board.Mentions.MentionView;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;

/**
 * 댓글·한 단계 답글·추천 (회원 게시판·공지 공통).
 * - 조회: 글 조회 범위 그대로 (공지는 비회원도). 작성·추천: 일반 회원·운영자 (데모·비회원 불가). 수정: 본인. 삭제: 본인·운영자.
 * - 잠금: 작성은 글 행을 공유 잠금(답글이면 부모 댓글도), 글 삭제는 배타 잠금이라 둘이 겹치면 줄을 선다 → 고아 댓글 없음.
 *   댓글 삭제는 그 행을 배타 잠금, 답글 작성·추천은 부모를 공유 잠금. 지우면 답글·추천·멘션이 FK CASCADE 로 함께 지워진다.
 * - 추천은 PUT(추천)·DELETE(취소) 멱등. 계정당 한 번은 기본 키가 보장한다.
 */
@Service
public class CommentService {

	public record CommentResponse(Long id, Long postId, Long parentId, String body, Author author,
			List<MentionView> mentions, Instant createdAt, Instant updatedAt, boolean edited, long likeCount,
			boolean liked, long replyCount, boolean canEdit, boolean canDelete, List<CommentResponse> replies) {
	}

	public record CommentRequest(String body, Long parentId) {

		String validBody() {
			if (body == null || body.isBlank() || body.length() > 1000) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED);
			}
			return body;
		}

	}

	public record LikeResponse(long likeCount, boolean liked) {
	}

	private record Row(Long id, Long postId, Long parentId, String body, Long authorId, String authorLabel,
			Instant createdAt, Instant updatedAt, long likeCount, boolean liked) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	private final BoardService posts;

	private final Mentions mentions;

	private final Clock clock;

	public CommentService(NamedParameterJdbcTemplate jdbc, BoardService posts, Mentions mentions, Clock clock) {
		this.jdbc = jdbc;
		this.posts = posts;
		this.mentions = mentions;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<CommentResponse> list(Long postId, SessionUser viewer) {
		posts.readable(postId, viewer);
		List<Row> rows = jdbc.query("""
				select c.id, c.post_id, c.parent_id, c.body, c.author_account_id, c.created_at, c.updated_at,
				  %s as author_label,
				  (select count(*) from comment_like l where l.comment_id = c.id) as like_count,
				  exists (select 1 from comment_like l where l.comment_id = c.id and l.account_id = :viewer) as liked
				from comment c join account a on a.id = c.author_account_id
				where c.post_id = :post order by c.created_at, c.id
				""".formatted(Mentions.labelSql("a")),
				new MapSqlParameterSource("post", postId).addValue("viewer", viewer == null ? -1L : viewer.id()),
				(rs, n) -> {
					Timestamp updated = rs.getTimestamp("updated_at");
					long parent = rs.getLong("parent_id");
					Long parentId = rs.wasNull() ? null : parent; // wasNull 은 바로 앞에서 읽은 칸 기준
					return new Row(rs.getLong("id"), rs.getLong("post_id"), parentId,
							rs.getString("body"), rs.getLong("author_account_id"), rs.getString("author_label"),
							rs.getTimestamp("created_at").toInstant(), updated == null ? null : updated.toInstant(),
							rs.getLong("like_count"), rs.getBoolean("liked"));
				});
		Map<Long, List<MentionView>> mentioned = mentions.load("comment_id", rows.stream().map(Row::id).toList());
		Map<Long, List<CommentResponse>> replies = new LinkedHashMap<>();
		for (Row r : rows) {
			if (r.parentId() != null) {
				replies.computeIfAbsent(r.parentId(), k -> new ArrayList<>())
					.add(toResponse(r, viewer, mentioned, List.of()));
			}
		}
		return rows.stream()
			.filter(r -> r.parentId() == null)
			.map(r -> toResponse(r, viewer, mentioned, replies.getOrDefault(r.id(), List.of())))
			.toList();
	}

	@Transactional
	public CommentResponse create(Long postId, CommentRequest request, SessionUser user) {
		requireWriter(user);
		String body = request.validBody();
		posts.readable(postId, user);
		lockForShare("select id from board_post where id = :id for share", postId, ErrorCode.POST_NOT_FOUND);
		if (request.parentId() != null) {
			Map<String, Object> parent = jdbc.query(
					"select post_id, parent_id from comment where id = :id for share",
					new MapSqlParameterSource("id", request.parentId()), (rs, n) -> {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("post", rs.getLong("post_id"));
						long p = rs.getLong("parent_id");
						m.put("parent", rs.wasNull() ? null : p);
						return m;
					}).stream().findFirst().orElseThrow(() -> new ApiException(ErrorCode.COMMENT_NOT_FOUND));
			if (!postId.equals(parent.get("post"))) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED);
			}
			if (parent.get("parent") != null) {
				throw new ApiException(ErrorCode.REPLY_DEPTH_EXCEEDED);
			}
		}
		Instant now = Instant.now(clock);
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update("""
				insert into comment (post_id, parent_id, author_account_id, body, created_at)
				values (:post, :parent, :author, :body, :now)
				""", new MapSqlParameterSource("post", postId).addValue("parent", request.parentId())
			.addValue("author", user.id())
			.addValue("body", body)
			.addValue("now", now.atOffset(ZoneOffset.UTC)), key, new String[] { "id" });
		long id = key.getKey().longValue();
		mentions.sync("comment_id", id, body, now);
		return one(id, user);
	}

	@Transactional
	public CommentResponse edit(Long id, CommentRequest request, SessionUser user) {
		Long author = lockOwner(id);
		if (user.isDemo() || !author.equals(user.id())) {
			throw new ApiException(ErrorCode.NOT_COMMENT_AUTHOR);
		}
		String body = request.validBody();
		Instant now = Instant.now(clock);
		jdbc.update("update comment set body = :body, updated_at = :now where id = :id",
				new MapSqlParameterSource("id", id).addValue("body", body).addValue("now", now.atOffset(ZoneOffset.UTC)));
		mentions.sync("comment_id", id, body, now);
		return one(id, user);
	}

	@Transactional
	public void delete(Long id, SessionUser user) {
		Long author = lockOwner(id);
		if (user.isDemo() || !(author.equals(user.id()) || BoardService.isOperator(user))) {
			throw new ApiException(ErrorCode.NOT_COMMENT_AUTHOR);
		}
		jdbc.update("delete from comment where id = :id", new MapSqlParameterSource("id", id));
	}

	@Transactional
	public LikeResponse like(Long id, boolean on, SessionUser user) {
		requireWriter(user);
		lockForShare("select id from comment where id = :id for share", id, ErrorCode.COMMENT_NOT_FOUND);
		MapSqlParameterSource params = new MapSqlParameterSource("id", id).addValue("account", user.id())
			.addValue("now", Instant.now(clock).atOffset(ZoneOffset.UTC));
		if (on) {
			jdbc.update("insert into comment_like (comment_id, account_id, created_at) values (:id, :account, :now) "
					+ "on conflict do nothing", params);
		}
		else {
			jdbc.update("delete from comment_like where comment_id = :id and account_id = :account", params);
		}
		long count = jdbc.queryForObject("select count(*) from comment_like where comment_id = :id", params, Long.class);
		return new LikeResponse(count, on);
	}

	private static void requireWriter(SessionUser user) {
		if (user == null || user.isDemo()) {
			throw new ApiException(ErrorCode.COMMENT_WRITE_FORBIDDEN);
		}
	}

	private void lockForShare(String sql, Long id, ErrorCode missing) {
		if (jdbc.queryForList(sql, new MapSqlParameterSource("id", id), Long.class).isEmpty()) {
			throw new ApiException(missing);
		}
	}

	private Long lockOwner(Long id) {
		return jdbc.queryForList("select author_account_id from comment where id = :id for update",
				new MapSqlParameterSource("id", id), Long.class)
			.stream()
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.COMMENT_NOT_FOUND));
	}

	private CommentResponse one(Long id, SessionUser user) {
		Long postId = jdbc.queryForObject("select post_id from comment where id = :id", new MapSqlParameterSource("id", id),
				Long.class);
		return list(postId, user).stream()
			.flatMap(c -> java.util.stream.Stream.concat(java.util.stream.Stream.of(c), c.replies().stream()))
			.filter(c -> c.id().equals(id))
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.COMMENT_NOT_FOUND));
	}

	private static CommentResponse toResponse(Row r, SessionUser viewer, Map<Long, List<MentionView>> mentioned,
			List<CommentResponse> replies) {
		boolean member = viewer != null && !viewer.isDemo();
		boolean own = member && r.authorId().equals(viewer.id());
		return new CommentResponse(r.id(), r.postId(), r.parentId(), r.body(), new Author(r.authorLabel()),
				mentioned.getOrDefault(r.id(), List.of()), r.createdAt(), r.updatedAt(), r.updatedAt() != null,
				r.likeCount(), r.liked(), replies.size(), own, own || (member && BoardService.isOperator(viewer)),
				replies);
	}

}

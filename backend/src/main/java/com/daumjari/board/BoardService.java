package com.daumjari.board;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.daumjari.account.Role;
import com.daumjari.auth.SessionUser;
import com.daumjari.board.BoardDtos.Author;
import com.daumjari.board.BoardDtos.Board;
import com.daumjari.board.BoardDtos.PostPage;
import com.daumjari.board.BoardDtos.PostRequest;
import com.daumjari.board.BoardDtos.PostResponse;
import com.daumjari.board.BoardDtos.Search;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;

/**
 * 회원 게시판·서비스 공지. 권한의 근거는 여기다 (화면은 응답의 canWrite·canEdit·canDelete 를 따른다).
 * - 회원 게시판: 조회는 로그인한 누구나(데모 포함), 작성은 일반 회원·운영자, 수정은 작성자만, 삭제는 작성자·운영자.
 * - 서비스 공지: 조회는 비회원 포함 누구나, 작성·수정·삭제는 서비스 운영자.
 * 수정·삭제는 글 행을 FOR UPDATE 로 잠근 뒤 권한을 본다 (2단계 댓글 작성은 같은 행을 공유 잠금해 삭제와 줄을 선다).
 */
@Service
public class BoardService {

	private static final String COLUMNS = """
			p.id, p.board, p.title, p.body, p.author_account_id, p.created_at, p.updated_at,
			a.role as author_role, a.deleted_at as author_deleted
			""";

	private final NamedParameterJdbcTemplate jdbc;

	private final Clock clock;

	public BoardService(NamedParameterJdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** 글 한 줄 (권한 판단용 작성자 id 포함, 응답에는 내보내지 않는다). */
	private record Row(Long id, Board board, String title, String body, Long authorId, String authorRole,
			boolean authorDeleted, Instant createdAt, Instant updatedAt) {
	}

	@Transactional(readOnly = true)
	public PostPage list(String boardPath, String q, String field, String page, String size, SessionUser viewer) {
		Board board = Board.fromPath(boardPath);
		requireReadable(board, viewer);
		Search search = Search.parse(q, field, page, size);
		MapSqlParameterSource params = new MapSqlParameterSource("board", board.name());
		String where = "p.board = :board";
		if (!search.keyword().isEmpty()) {
			params.addValue("pattern", "%" + search.keyword().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
			where += switch (search.field()) {
				case TITLE -> " and p.title ilike :pattern escape '\\'";
				case BODY -> " and p.body ilike :pattern escape '\\'";
				case ALL -> " and (p.title ilike :pattern escape '\\' or p.body ilike :pattern escape '\\')";
			};
		}
		long total = jdbc.queryForObject("select count(*) from board_post p where " + where, params, Long.class);
		params.addValue("limit", search.size()).addValue("offset", search.offset());
		List<PostResponse> items = jdbc
			.query("select " + COLUMNS + " from board_post p join account a on a.id = p.author_account_id where "
					+ where + " order by p.created_at desc, p.id desc limit :limit offset :offset", params,
					BoardService::row)
			.stream()
			.map(r -> toResponse(r, viewer, false))
			.toList();
		return new PostPage(items, search.page(), search.size(), total, (int) ((total + search.size() - 1) / search.size()),
				canWrite(board, viewer));
	}

	@Transactional(readOnly = true)
	public PostResponse get(Long id, SessionUser viewer) {
		Row row = find(id, false).orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
		requireReadable(row.board(), viewer);
		return toResponse(row, viewer, true);
	}

	@Transactional
	public PostResponse create(String boardPath, PostRequest request, SessionUser author) {
		Board board = Board.fromPath(boardPath);
		if (!canWrite(board, author)) {
			throw new ApiException(ErrorCode.BOARD_WRITE_FORBIDDEN);
		}
		PostRequest valid = request.validated();
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update("""
				insert into board_post (board, author_account_id, title, body, created_at)
				values (:board, :author, :title, :body, :now)
				""", new MapSqlParameterSource("board", board.name()).addValue("author", author.id())
			.addValue("title", valid.title())
			.addValue("body", valid.body())
			.addValue("now", Instant.now(clock).atOffset(ZoneOffset.UTC)), key, new String[] { "id" });
		return get(key.getKey().longValue(), author);
	}

	@Transactional
	public PostResponse edit(Long id, PostRequest request, SessionUser user) {
		Row row = find(id, true).orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
		if (!canEdit(row, user)) {
			throw new ApiException(ErrorCode.NOT_POST_AUTHOR);
		}
		PostRequest valid = request.validated();
		jdbc.update("update board_post set title = :title, body = :body, updated_at = :now where id = :id",
				new MapSqlParameterSource("id", id).addValue("title", valid.title())
					.addValue("body", valid.body())
					.addValue("now", Instant.now(clock).atOffset(ZoneOffset.UTC)));
		return get(id, user);
	}

	/** 물리 삭제. 2단계부터 댓글·답글·추천·멘션·알림은 FK ON DELETE CASCADE 로 같은 문장에서 함께 지워진다. */
	@Transactional
	public void delete(Long id, SessionUser user) {
		Row row = find(id, true).orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
		if (!canDelete(row, user)) {
			throw new ApiException(ErrorCode.NOT_POST_AUTHOR);
		}
		jdbc.update("delete from board_post where id = :id", new MapSqlParameterSource("id", id));
	}

	private Optional<Row> find(Long id, boolean lock) {
		String sql = "select " + COLUMNS + " from board_post p join account a on a.id = p.author_account_id where p.id = :id"
				+ (lock ? " for update of p" : "");
		return jdbc.query(sql, new MapSqlParameterSource("id", id), BoardService::row).stream().findFirst();
	}

	/** 회원 게시판은 로그인해야 본다 (비회원 401). 공지는 누구나. */
	private static void requireReadable(Board board, SessionUser viewer) {
		if (board == Board.COMMUNITY && viewer == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
	}

	private static boolean isOperator(SessionUser user) {
		return user != null && user.role() == Role.OPERATOR;
	}

	static boolean canWrite(Board board, SessionUser user) {
		if (user == null || user.isDemo()) {
			return false;
		}
		return board == Board.COMMUNITY || isOperator(user);
	}

	/** 회원 게시판은 작성자만, 공지는 서비스 운영자 누구나 (공지는 운영자 공동 관리). */
	private static boolean canEdit(Row row, SessionUser user) {
		if (user == null || user.isDemo()) {
			return false;
		}
		return row.board() == Board.NOTICE ? isOperator(user) : row.authorId().equals(user.id());
	}

	private static boolean canDelete(Row row, SessionUser user) {
		if (user == null || user.isDemo()) {
			return false;
		}
		return isOperator(user) || (row.board() == Board.COMMUNITY && row.authorId().equals(user.id()));
	}

	private static PostResponse toResponse(Row row, SessionUser viewer, boolean withBody) {
		return new PostResponse(row.id(), row.board(), row.title(), withBody ? row.body() : null, author(row),
				row.createdAt(), row.updatedAt(), row.updatedAt() != null, 0, canEdit(row, viewer),
				canDelete(row, viewer));
	}

	private static Author author(Row row) {
		if (row.authorDeleted()) {
			return new Author("탈퇴한 회원");
		}
		return new Author(Role.OPERATOR.name().equals(row.authorRole()) ? "운영자" : "회원#" + row.authorId());
	}

	private static Row row(ResultSet rs, int n) throws SQLException {
		Timestamp updated = rs.getTimestamp("updated_at");
		return new Row(rs.getLong("id"), Board.valueOf(rs.getString("board")), rs.getString("title"),
				rs.getString("body"), rs.getLong("author_account_id"), rs.getString("author_role"),
				rs.getTimestamp("author_deleted") != null, rs.getTimestamp("created_at").toInstant(),
				updated == null ? null : updated.toInstant());
	}

}

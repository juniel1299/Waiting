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
import com.daumjari.club.ClubRoles;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;
import com.daumjari.notification.Notifier;

/**
 * 회원 게시판·서비스 공지·모임 게시판·모임 공지. 권한의 근거는 여기다 (화면은 응답의 canWrite·canEdit·canDelete 를 따른다).
 * - 회원 게시판: 조회는 로그인한 누구나(데모 포함), 작성은 일반 회원·운영자, 수정은 작성자만, 삭제는 작성자·운영자.
 * - 서비스 공지: 조회는 비회원 포함 누구나, 작성·수정·삭제는 서비스 운영자.
 * - 모임 게시판: 조회·작성은 그 모임 회원, 수정은 작성자, 삭제는 작성자·모임 운영자. 서비스 운영자는 조회·삭제만.
 * - 모임 공지: 조회는 모임 회원, 작성·수정·삭제는 모임 운영자. 서비스 운영자는 조회·삭제만.
 * 모임 글은 요청마다 지금 가입·역할을 다시 보고(ClubRoles), 쓰기·수정·삭제는 모임 → 글 순서로 잠근다.
 */
@Service
public class BoardService {

	private static final String COLUMNS = """
			p.id, p.board, p.club_id, p.title, p.body, p.author_account_id, p.created_at, p.updated_at,
			(select count(*) from comment c where c.post_id = p.id) as comment_count,
			""" + Mentions.labelSql("a") + " as author_label";

	private final NamedParameterJdbcTemplate jdbc;

	private final Clock clock;

	private final Mentions mentions;

	private final Notifier notifier;

	private final ClubRoles clubRoles;

	public BoardService(NamedParameterJdbcTemplate jdbc, Clock clock, Mentions mentions, Notifier notifier,
			ClubRoles clubRoles) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.mentions = mentions;
		this.notifier = notifier;
		this.clubRoles = clubRoles;
	}

	/** 글 한 줄 (권한 판단용 작성자 id 포함, 응답에는 내보내지 않는다). */
	record Row(Long id, Board board, Long clubId, String title, String body, Long authorId, String authorLabel,
			long commentCount, Instant createdAt, Instant updatedAt) {
	}

	/** 조회자의 모임 안 역할 (모임 글이 아니거나 회원이 아니면 null). */
	record Access(String clubRole) {

		boolean member() {
			return clubRole != null;
		}

		boolean manager() {
			return ClubRoles.MANAGER.equals(clubRole);
		}

	}

	@Transactional(readOnly = true)
	public PostPage list(String boardPath, String q, String field, String page, String size, SessionUser viewer) {
		return list(Board.fromPath(boardPath), null, q, field, page, size, viewer);
	}

	@Transactional(readOnly = true)
	public PostPage listClub(Long clubId, String boardPath, String q, String field, String page, String size,
			SessionUser viewer) {
		return list(Board.fromClubPath(boardPath), clubId, q, field, page, size, viewer);
	}

	private PostPage list(Board board, Long clubId, String q, String field, String page, String size, SessionUser viewer) {
		Access access = access(clubId, viewer, false);
		requireReadable(board, viewer, access);
		Search search = Search.parse(q, field, page, size);
		MapSqlParameterSource params = new MapSqlParameterSource("board", board.name()).addValue("club", clubId);
		String where = "p.board = :board" + (clubId == null ? "" : " and p.club_id = :club");
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
			.map(r -> toResponse(r, viewer, access, false, List.of()))
			.toList();
		return new PostPage(items, search.page(), search.size(), total, (int) ((total + search.size() - 1) / search.size()),
				canWrite(board, viewer, access));
	}

	@Transactional(readOnly = true)
	public PostResponse get(Long id, SessionUser viewer) {
		Row row = readable(id, viewer);
		return toResponse(row, viewer, access(row.clubId(), viewer, false), true,
				mentions.load("post_id", List.of(id)).getOrDefault(id, List.of()));
	}

	/** 글이 있고 이 사용자가 지금 읽을 수 있으면 그 글 (댓글 조회·작성이 같은 규칙을 쓴다). */
	Row readable(Long id, SessionUser viewer) {
		Row row = find(id, false).orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
		requireReadable(row.board(), viewer, access(row.clubId(), viewer, false));
		return row;
	}

	/**
	 * 댓글·추천 쓰기 전 확인: 모임 글이면 모임 행·회원 행을 공유 잠금한 뒤 지금 회원인지 본다 (서비스 운영자도 쓰지 못함).
	 * 반환값은 조회자의 모임 역할.
	 */
	Access lockForWrite(Long postId, SessionUser user, ErrorCode forbidden) {
		Access access = lockClubOf(postId, user);
		if (clubOf(postId) != null && !access.member()) {
			throw new ApiException(forbidden);
		}
		return access;
	}

	/** 모임 글이면 글을 잠그기 전에 모임 행·회원 행을 먼저 공유 잠금하고 읽을 권한을 본다 (모임 삭제와 같은 잠금 순서). */
	Access lockClubOf(Long postId, SessionUser user) {
		Row row = find(postId, false).orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
		Access access = access(row.clubId(), user, true);
		requireReadable(row.board(), user, access);
		return access;
	}

	/** 조회자의 모임 역할 (잠그지 않음). 모임 글이 아니면 빈 역할. */
	Access accessOf(Long postId, SessionUser viewer) {
		return access(clubOf(postId), viewer, false);
	}

	/** 글이 속한 모임 (모임 글이 아니면 null). 멘션은 이 모임 회원에게만 연결한다. */
	Long clubOf(Long postId) {
		return find(postId, false).map(Row::clubId).orElse(null);
	}

	/** 알림 열기: 지금 읽을 수 있으면 화면 주소 (아니면 그 오류 401·403·404). */
	@Transactional(readOnly = true)
	public String pathOf(Long id, SessionUser viewer) {
		Row row = readable(id, viewer);
		return switch (row.board()) {
			case COMMUNITY -> "/board/" + id;
			case NOTICE -> "/notices/" + id;
			case CLUB_BOARD -> "/clubs/" + row.clubId() + "/board/" + id;
			case CLUB_NOTICE -> "/clubs/" + row.clubId() + "/notices/" + id;
		};
	}

	@Transactional
	public PostResponse create(String boardPath, PostRequest request, SessionUser author) {
		return create(Board.fromPath(boardPath), null, request, author);
	}

	@Transactional
	public PostResponse createClub(Long clubId, String boardPath, PostRequest request, SessionUser author) {
		return create(Board.fromClubPath(boardPath), clubId, request, author);
	}

	private PostResponse create(Board board, Long clubId, PostRequest request, SessionUser author) {
		Access access = access(clubId, author, true);
		if (!canWrite(board, author, access)) {
			throw new ApiException(ErrorCode.BOARD_WRITE_FORBIDDEN);
		}
		PostRequest valid = request.validated();
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update("""
				insert into board_post (board, club_id, author_account_id, title, body, created_at)
				values (:board, :club, :author, :title, :body, :now)
				""", new MapSqlParameterSource("board", board.name()).addValue("club", clubId)
			.addValue("author", author.id())
			.addValue("title", valid.title())
			.addValue("body", valid.body())
			.addValue("now", Instant.now(clock).atOffset(ZoneOffset.UTC)), key, new String[] { "id" });
		long id = key.getKey().longValue();
		notifier.mentioned(id, null, mentions.sync("post_id", id, valid.body(), Instant.now(clock), clubId), author.id());
		return get(id, author);
	}

	@Transactional
	public PostResponse edit(Long id, PostRequest request, SessionUser user) {
		Access access = lockClubOf(id, user);
		Row row = find(id, true).orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
		if (!canEdit(row, user, access)) {
			throw new ApiException(ErrorCode.NOT_POST_AUTHOR);
		}
		PostRequest valid = request.validated();
		jdbc.update("update board_post set title = :title, body = :body, updated_at = :now where id = :id",
				new MapSqlParameterSource("id", id).addValue("title", valid.title())
					.addValue("body", valid.body())
					.addValue("now", Instant.now(clock).atOffset(ZoneOffset.UTC)));
		notifier.mentioned(id, null, mentions.sync("post_id", id, valid.body(), Instant.now(clock), row.clubId()), user.id());
		return get(id, user);
	}

	/** 물리 삭제. 댓글·답글·추천·멘션·알림은 FK ON DELETE CASCADE 로 같은 문장에서 함께 지워진다. */
	@Transactional
	public void delete(Long id, SessionUser user) {
		Access access = lockClubOf(id, user);
		Row row = find(id, true).orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
		if (!canDelete(row, user, access)) {
			throw new ApiException(ErrorCode.NOT_POST_AUTHOR);
		}
		jdbc.update("delete from board_post where id = :id", new MapSqlParameterSource("id", id));
	}

	private Access access(Long clubId, SessionUser viewer, boolean lock) {
		if (clubId == null) {
			return new Access(null);
		}
		return new Access(clubRoles.role(clubId, viewer == null ? null : viewer.id(), lock));
	}

	private Optional<Row> find(Long id, boolean lock) {
		String sql = "select " + COLUMNS + " from board_post p join account a on a.id = p.author_account_id where p.id = :id"
				+ (lock ? " for update of p" : "");
		return jdbc.query(sql, new MapSqlParameterSource("id", id), BoardService::row).stream().findFirst();
	}

	/** 회원 게시판은 로그인해야 본다 (비회원 401). 공지는 누구나. 모임 게시판은 그 모임 회원과 서비스 운영자 (403). */
	private static void requireReadable(Board board, SessionUser viewer, Access access) {
		if (board != Board.NOTICE && viewer == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		if (board.isClub() && !access.member() && !isOperator(viewer)) {
			throw new ApiException(ErrorCode.CLUB_MEMBERS_ONLY);
		}
	}

	static boolean isOperator(SessionUser user) {
		return user != null && user.role() == Role.OPERATOR;
	}

	private static boolean canWrite(Board board, SessionUser user, Access access) {
		if (user == null || user.isDemo()) {
			return false;
		}
		return switch (board) {
			case COMMUNITY -> true;
			case NOTICE -> isOperator(user);
			case CLUB_BOARD -> access.member();
			case CLUB_NOTICE -> access.manager();
		};
	}

	/** 회원·모임 게시판은 작성자만(모임 글은 지금 회원일 때), 공지는 운영자 공동 관리, 모임 공지는 모임 운영자 공동 관리. */
	private static boolean canEdit(Row row, SessionUser user, Access access) {
		if (user == null || user.isDemo()) {
			return false;
		}
		boolean author = row.authorId().equals(user.id());
		return switch (row.board()) {
			case COMMUNITY -> author;
			case NOTICE -> isOperator(user);
			case CLUB_BOARD -> author && access.member();
			case CLUB_NOTICE -> access.manager();
		};
	}

	private static boolean canDelete(Row row, SessionUser user, Access access) {
		if (user == null || user.isDemo()) {
			return false;
		}
		boolean author = row.authorId().equals(user.id());
		return isOperator(user) || switch (row.board()) {
			case COMMUNITY -> author;
			case NOTICE -> false;
			case CLUB_BOARD -> (author && access.member()) || access.manager();
			case CLUB_NOTICE -> access.manager();
		};
	}

	private static PostResponse toResponse(Row row, SessionUser viewer, Access access, boolean withBody,
			List<Mentions.MentionView> mentioned) {
		return new PostResponse(row.id(), row.board(), row.clubId(), row.title(), withBody ? row.body() : null,
				new Author(row.authorLabel()), mentioned, row.createdAt(), row.updatedAt(), row.updatedAt() != null,
				row.commentCount(), canEdit(row, viewer, access), canDelete(row, viewer, access));
	}

	private static Row row(ResultSet rs, int n) throws SQLException {
		Timestamp updated = rs.getTimestamp("updated_at");
		long club = rs.getLong("club_id");
		Long clubId = rs.wasNull() ? null : club;
		return new Row(rs.getLong("id"), Board.valueOf(rs.getString("board")), clubId, rs.getString("title"),
				rs.getString("body"), rs.getLong("author_account_id"), rs.getString("author_label"),
				rs.getLong("comment_count"), rs.getTimestamp("created_at").toInstant(),
				updated == null ? null : updated.toInstant());
	}

}

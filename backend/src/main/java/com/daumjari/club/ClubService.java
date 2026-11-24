package com.daumjari.club;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.daumjari.account.Role;
import com.daumjari.auth.SessionUser;
import com.daumjari.board.BoardDtos.Author;
import com.daumjari.board.BoardDtos.Search;
import com.daumjari.board.Mentions;
import com.daumjari.club.ClubDtos.ClubPage;
import com.daumjari.club.ClubDtos.ClubRequest;
import com.daumjari.club.ClubDtos.ClubResponse;
import com.daumjari.club.ClubDtos.Decision;
import com.daumjari.club.ClubDtos.Membership;
import com.daumjari.club.ClubDtos.Mode;
import com.daumjari.club.ClubDtos.MyClubs;
import com.daumjari.club.ClubDtos.Status;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;
import com.daumjari.notification.Notifier;

/**
 * 모임 신청·승인·가입. 권한의 근거는 여기다 (화면은 응답의 can* 를 따른다).
 * - 신청·수정·취소: 일반 회원·운영자(데모 403), 수정·취소는 생성자만. 삭제는 생성자·서비스 운영자.
 * - 승인·반려: 서비스 운영자, 화면에서 본 version 이 지금과 같고 승인 대기일 때만 (아니면 409 CLUB_CHANGED).
 * - 승인 모임: 로그인한 누구나 목록·소개, 가입·탈퇴는 데모 제외. 그 밖의 상태는 생성자·운영자만 (아니면 404).
 * 상태를 바꾸는 요청은 모임 행을 FOR UPDATE, 가입·탈퇴는 FOR SHARE 로 잠가 삭제·결정과 줄을 세운다.
 */
@Service
public class ClubService {

	private static final String COLUMNS = """
			c.id, c.name, c.intro, c.place, c.mode, c.status, c.reject_reason, c.version, c.creator_account_id,
			c.submitted_at, c.decided_at,
			(select count(*) from club_member m where m.club_id = c.id) as member_count,
			exists (select 1 from club_member m where m.club_id = c.id and m.account_id = :viewer) as joined,
			""" + Mentions.labelSql("a") + " as creator_label";

	private static final String FROM = " from club c join account a on a.id = c.creator_account_id ";

	record Row(Long id, String name, String intro, String place, Mode mode, Status status, String rejectReason,
			long version, Long creatorId, String creatorLabel, long memberCount, boolean joined, Instant submittedAt,
			Instant decidedAt) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	private final Clock clock;

	private final Notifier notifier;

	public ClubService(NamedParameterJdbcTemplate jdbc, Clock clock, Notifier notifier) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.notifier = notifier;
	}

	/** 승인된 모임 목록. sort=members 는 회원 많은 순(홍보 화면), 기본은 최근 승인 순. */
	@Transactional(readOnly = true)
	public ClubPage list(String q, String page, String size, String sort, SessionUser viewer) {
		return page("c.status = 'APPROVED'", q, page, size, "members".equals(sort) ? "member_count desc, c.decided_at desc"
				: "c.decided_at desc", viewer);
	}

	/** 운영자 신청 목록 (상태별, 기본 승인 대기, 먼저 낸 순). */
	@Transactional(readOnly = true)
	public ClubPage operatorList(String status, String q, String page, String size, SessionUser viewer) {
		Status s = parseStatus(status == null || status.isBlank() ? "PENDING" : status);
		return page("c.status = '" + s.name() + "'", q, page, size, "c.submitted_at", viewer);
	}

	private ClubPage page(String where, String q, String page, String size, String order, SessionUser viewer) {
		Search search = Search.parse(q, null, page, size);
		MapSqlParameterSource params = new MapSqlParameterSource("viewer", viewer.id());
		if (!search.keyword().isEmpty()) {
			params.addValue("pattern", "%" + search.keyword().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
			where += " and (c.name ilike :pattern escape '\\' or c.intro ilike :pattern escape '\\')";
		}
		long total = jdbc.queryForObject("select count(*) from club c where " + where, params, Long.class);
		params.addValue("limit", search.size()).addValue("offset", search.offset());
		List<ClubResponse> items = jdbc
			.query("select " + COLUMNS + FROM + "where " + where + " order by " + order + ", c.id desc limit :limit offset :offset",
					params, ClubService::row)
			.stream()
			.map(r -> toResponse(r, viewer))
			.toList();
		return new ClubPage(items, search.page(), search.size(), total, (int) ((total + search.size() - 1) / search.size()),
				!viewer.isDemo());
	}

	@Transactional(readOnly = true)
	public ClubResponse get(Long id, SessionUser viewer) {
		return toResponse(visible(id, viewer), viewer);
	}

	/** 알림 열기: 지금 볼 수 있으면 이동할 주소 (승인 모임은 소개, 그 밖에는 내 모임의 진행 단계). */
	@Transactional(readOnly = true)
	public String hrefFor(Long id, SessionUser viewer) {
		return visible(id, viewer).status() == Status.APPROVED ? "/clubs/" + id : "/me/clubs";
	}

	@Transactional(readOnly = true)
	public MyClubs mine(SessionUser viewer) {
		MapSqlParameterSource params = new MapSqlParameterSource("viewer", viewer.id());
		List<ClubResponse> applications = jdbc
			.query("select " + COLUMNS + FROM + "where c.creator_account_id = :viewer order by c.created_at desc, c.id desc",
					params, ClubService::row)
			.stream()
			.map(r -> toResponse(r, viewer))
			.toList();
		List<ClubResponse> joined = jdbc
			.query("select " + COLUMNS + FROM + "join club_member me on me.club_id = c.id and me.account_id = :viewer "
					+ "where c.status = 'APPROVED' order by me.joined_at desc, c.id desc", params, ClubService::row)
			.stream()
			.map(r -> toResponse(r, viewer))
			.toList();
		return new MyClubs(applications, joined);
	}

	@Transactional
	public ClubResponse apply(ClubRequest request, SessionUser user) {
		requireNotDemo(user);
		ClubRequest valid = request.validated();
		Instant now = Instant.now(clock);
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update("""
				insert into club (name, intro, place, mode, status, creator_account_id, created_at, submitted_at)
				values (:name, :intro, :place, :mode, 'PENDING', :creator, :now, :now)
				""", fields(valid).addValue("creator", user.id()).addValue("now", at(now)), key, new String[] { "id" });
		return get(key.getKey().longValue(), user);
	}

	/** 생성자 수정. 반려·취소된 신청은 고치면 다시 승인 대기, 대기 중은 그대로, 승인된 모임은 재승인 없이 바로 반영. */
	@Transactional
	public ClubResponse edit(Long id, ClubRequest request, SessionUser user) {
		Row row = lockOwned(id, user);
		ClubRequest valid = request.validated();
		boolean resubmit = row.status() == Status.REJECTED || row.status() == Status.CANCELLED;
		jdbc.update("update club set name = :name, intro = :intro, place = :place, mode = :mode, version = version + 1, "
				+ "updated_at = :now" + (resubmit ? ", status = 'PENDING', reject_reason = null, decided_at = null, submitted_at = :now" : "")
				+ " where id = :id", fields(valid).addValue("id", id).addValue("now", at(Instant.now(clock))));
		return get(id, user);
	}

	@Transactional
	public ClubResponse cancel(Long id, SessionUser user) {
		Row row = lockOwned(id, user);
		if (row.status() != Status.PENDING) {
			throw new ApiException(ErrorCode.CLUB_CHANGED);
		}
		jdbc.update("update club set status = 'CANCELLED', version = version + 1, updated_at = :now where id = :id",
				new MapSqlParameterSource("id", id).addValue("now", at(Instant.now(clock))));
		return get(id, user);
	}

	/** 물리 삭제. 회원·결과 알림은 FK ON DELETE CASCADE 로 같은 문장에서 함께 지워진다. */
	@Transactional
	public void delete(Long id, SessionUser user) {
		Row row = lock(id, "for update of c", user).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
		if (user.isDemo() || !(row.creatorId().equals(user.id()) || isOperator(user))) {
			throw new ApiException(ErrorCode.NOT_CLUB_CREATOR);
		}
		jdbc.update("delete from club where id = :id", new MapSqlParameterSource("id", id));
	}

	/** 승인·반려. 같은 트랜잭션에서 결과 알림(사건 열쇠: 결정 뒤 version)과, 승인이면 생성자의 모임 운영자 행을 남긴다. */
	@Transactional
	public ClubResponse decide(Long id, boolean approve, Decision decision, SessionUser operator) {
		String reason = approve ? null : decision.validReason();
		Row row = lock(id, "for update of c", operator).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
		if (row.status() != Status.PENDING || decision.version() == null || row.version() != decision.version()) {
			throw new ApiException(ErrorCode.CLUB_CHANGED);
		}
		Instant now = Instant.now(clock);
		jdbc.update("update club set status = :status, reject_reason = :reason, decided_at = :now, version = version + 1 "
				+ "where id = :id", new MapSqlParameterSource("id", id).addValue("status", approve ? "APPROVED" : "REJECTED")
					.addValue("reason", reason)
					.addValue("now", at(now)));
		if (approve) {
			jdbc.update("insert into club_member (club_id, account_id, role, joined_at) values (:id, :creator, 'MANAGER', :now) "
					+ "on conflict do nothing", new MapSqlParameterSource("id", id).addValue("creator", row.creatorId())
						.addValue("now", at(now)));
		}
		notifier.clubDecided(id, row.creatorId(), approve, row.version() + 1);
		return get(id, operator);
	}

	/** 즉시 가입. 이미 회원이면 그대로 (기본 키가 중복을 막는다). */
	@Transactional
	public Membership join(Long id, SessionUser user) {
		requireNotDemo(user);
		lockApprovedForShare(id);
		jdbc.update("insert into club_member (club_id, account_id, role, joined_at) values (:id, :me, 'MEMBER', :now) "
				+ "on conflict do nothing", new MapSqlParameterSource("id", id).addValue("me", user.id())
					.addValue("now", at(Instant.now(clock))));
		return membership(id, user);
	}

	/** 탈퇴. 생성자는 탈퇴할 수 없다 (모임 삭제, 5단계에서 생성자 이전). */
	@Transactional
	public Membership leave(Long id, SessionUser user) {
		requireNotDemo(user);
		Long creator = lockApprovedForShare(id);
		if (creator.equals(user.id())) {
			throw new ApiException(ErrorCode.CREATOR_CANNOT_LEAVE);
		}
		jdbc.update("delete from club_member where club_id = :id and account_id = :me",
				new MapSqlParameterSource("id", id).addValue("me", user.id()));
		return membership(id, user);
	}

	/** 회원 탈퇴: 만든 모임은 모두 삭제(결정 4 추천안 B), 가입한 모임에서는 빠진다. 호출한 탈퇴 트랜잭션 안에서만. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void forgetAccount(Long accountId) {
		MapSqlParameterSource params = new MapSqlParameterSource("id", accountId);
		jdbc.update("delete from club where id in (select id from club where creator_account_id = :id order by id for update)",
				params);
		jdbc.update("delete from club_member where account_id = :id", params);
	}

	private Membership membership(Long id, SessionUser user) {
		MapSqlParameterSource params = new MapSqlParameterSource("id", id).addValue("me", user.id());
		return new Membership(
				jdbc.queryForObject("select exists (select 1 from club_member where club_id = :id and account_id = :me)", params,
						Boolean.class),
				jdbc.queryForObject("select count(*) from club_member where club_id = :id", params, Long.class));
	}

	private Long lockApprovedForShare(Long id) {
		return jdbc.query("select creator_account_id from club where id = :id and status = 'APPROVED' for share",
				new MapSqlParameterSource("id", id), (rs, n) -> rs.getLong(1))
			.stream()
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
	}

	private Row visible(Long id, SessionUser viewer) {
		Row row = lock(id, "", viewer).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
		if (row.status() != Status.APPROVED && !row.creatorId().equals(viewer.id()) && !isOperator(viewer)) {
			throw new ApiException(ErrorCode.CLUB_NOT_FOUND);
		}
		return row;
	}

	private Row lockOwned(Long id, SessionUser user) {
		requireNotDemo(user);
		Row row = lock(id, "for update of c", user).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
		if (!row.creatorId().equals(user.id())) {
			throw new ApiException(ErrorCode.NOT_CLUB_CREATOR);
		}
		return row;
	}

	private Optional<Row> lock(Long id, String lockClause, SessionUser viewer) {
		return jdbc
			.query("select " + COLUMNS + FROM + "where c.id = :id " + lockClause,
					new MapSqlParameterSource("id", id).addValue("viewer", viewer.id()), ClubService::row)
			.stream()
			.findFirst();
	}

	private static void requireNotDemo(SessionUser user) {
		if (user.isDemo()) {
			throw new ApiException(ErrorCode.CLUB_FORBIDDEN);
		}
	}

	private static boolean isOperator(SessionUser user) {
		return user.role() == Role.OPERATOR;
	}

	private static Status parseStatus(String raw) {
		try {
			return Status.valueOf(raw.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
	}

	private static MapSqlParameterSource fields(ClubRequest valid) {
		return new MapSqlParameterSource("name", valid.name()).addValue("intro", valid.intro())
			.addValue("place", valid.place())
			.addValue("mode", valid.mode());
	}

	private static ClubResponse toResponse(Row row, SessionUser viewer) {
		boolean mine = row.creatorId().equals(viewer.id());
		boolean privileged = mine || isOperator(viewer);
		boolean approved = row.status() == Status.APPROVED;
		boolean member = !viewer.isDemo();
		return new ClubResponse(row.id(), row.name(), row.intro(), row.place(), row.mode(), row.status(),
				privileged ? row.rejectReason() : null, privileged ? row.version() : null, new Author(row.creatorLabel()),
				row.memberCount(), row.joined(), mine, row.submittedAt(), row.decidedAt(), mine && member,
				member && privileged, mine && member && row.status() == Status.PENDING,
				member && approved && !row.joined(), member && approved && row.joined() && !mine);
	}

	private static Row row(ResultSet rs, int n) throws SQLException {
		Timestamp decided = rs.getTimestamp("decided_at");
		return new Row(rs.getLong("id"), rs.getString("name"), rs.getString("intro"), rs.getString("place"),
				Mode.valueOf(rs.getString("mode")), Status.valueOf(rs.getString("status")), rs.getString("reject_reason"),
				rs.getLong("version"), rs.getLong("creator_account_id"), rs.getString("creator_label"),
				rs.getLong("member_count"), rs.getBoolean("joined"), rs.getTimestamp("submitted_at").toInstant(),
				decided == null ? null : decided.toInstant());
	}

	private static Object at(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}

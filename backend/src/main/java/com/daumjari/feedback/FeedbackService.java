package com.daumjari.feedback;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.daumjari.auth.LoginThrottle;
import com.daumjari.auth.SessionUser;
import com.daumjari.board.Mentions;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;
import com.daumjari.common.FieldValidationException;
import com.daumjari.feedback.FeedbackDtos.Category;
import com.daumjari.feedback.FeedbackDtos.ChangeRequest;
import com.daumjari.feedback.FeedbackDtos.Detail;
import com.daumjari.feedback.FeedbackDtos.History;
import com.daumjari.feedback.FeedbackDtos.Page;
import com.daumjari.feedback.FeedbackDtos.Status;
import com.daumjari.feedback.FeedbackDtos.SubmitRequest;
import com.daumjari.feedback.FeedbackDtos.Summary;
import com.daumjari.notification.Notifier;

/**
 * 개선사항. 회원이 접수(데모 불가, 계정·IP 당 횟수 제한), 작성자는 자기 것만 보고, 개발자는 전체를 찾고 상태를 바꾼다.
 * 상태 변경은 행을 잠그고 version 을 비교한다(낡으면 409). 검증 요청·보류는 사유 필수, 상태가 바뀌면 기록하고 작성자에게 알린다.
 */
@Service
public class FeedbackService {

	static final int PAGE_SIZE = 20;

	static final int TITLE_MAX = 100;

	static final int BODY_MAX = 2000;

	static final int REASON_MAX = 300;

	static final int PR_URL_MAX = 300;

	static final Pattern PR_URL = Pattern.compile("https://github\\.com/\\S+");

	private static final String SUMMARY_COLUMNS = "f.id, f.category, f.title, f.status, f.created_at, f.updated_at, "
			+ Mentions.labelSql("a") + " as author";

	private final NamedParameterJdbcTemplate jdbc;

	private final Clock clock;

	private final LoginThrottle throttle;

	private final Notifier notifier;

	private final int maxPerAccount;

	private final int maxPerIp;

	public FeedbackService(NamedParameterJdbcTemplate jdbc, Clock clock, LoginThrottle throttle, Notifier notifier,
			@Value("${daumjari.feedback.max-per-account:5}") int maxPerAccount,
			@Value("${daumjari.feedback.max-per-ip:30}") int maxPerIp) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.throttle = throttle;
		this.notifier = notifier;
		this.maxPerAccount = maxPerAccount;
		this.maxPerIp = maxPerIp;
	}

	public Detail submit(SubmitRequest request, SessionUser user, String clientIp) {
		if (user.isDemo()) {
			throw new ApiException(ErrorCode.FEEDBACK_FORBIDDEN);
		}
		Map<String, String> errors = new LinkedHashMap<>();
		Category category = parse(Category.class, request.category());
		String title = text(request.title());
		String body = text(request.body());
		if (category == null) {
			errors.put("category", "종류를 골라 주세요.");
		}
		if (title.isEmpty() || title.length() > TITLE_MAX) {
			errors.put("title", "제목은 1~100자로 써 주세요.");
		}
		if (body.isEmpty() || body.length() > BODY_MAX) {
			errors.put("body", "내용은 1~2000자로 써 주세요.");
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException(errors);
		}
		throttle.countFeedback(user.id(), clientIp, maxPerAccount, maxPerIp);
		MapSqlParameterSource params = new MapSqlParameterSource("author", user.id()).addValue("category", category.name())
			.addValue("title", title)
			.addValue("body", body)
			.addValue("page", pagePath(request.pagePath()))
			.addValue("now", Instant.now(clock).atOffset(ZoneOffset.UTC));
		Long id = jdbc.queryForObject("""
				insert into feedback (author_account_id, category, title, body, page_path, status, created_at, updated_at)
				values (:author, :category, :title, :body, :page, 'RECEIVED', :now, :now) returning id
				""", params, Long.class);
		return load(id);
	}

	@Transactional(readOnly = true)
	public Page mine(SessionUser user) {
		List<Summary> items = jdbc.query("select " + SUMMARY_COLUMNS + " from feedback f join account a on a.id = f.author_account_id "
				+ "where f.author_account_id = :me order by f.created_at desc, f.id desc limit 100",
				new MapSqlParameterSource("me", user.id()), FeedbackService::summary);
		return new Page(items, 0, items.size(), items.size());
	}

	/** 작성자 또는 개발자만. 그 밖에는 있는지도 알리지 않는다(404). */
	@Transactional(readOnly = true)
	public Detail get(Long id, SessionUser user) {
		Long author = jdbc.query("select author_account_id from feedback where id = :id", new MapSqlParameterSource("id", id),
				(rs, n) -> rs.getLong(1)).stream().findFirst().orElseThrow(() -> new ApiException(ErrorCode.FEEDBACK_NOT_FOUND));
		if (!author.equals(user.id()) && !isDeveloper(user.id())) {
			throw new ApiException(ErrorCode.FEEDBACK_NOT_FOUND);
		}
		return load(id);
	}

	@Transactional(readOnly = true)
	public Page search(String q, String status, String category, int page) {
		Status st = blankOr(status, Status.class);
		Category cat = blankOr(category, Category.class);
		if (page < 0) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		List<String> where = new ArrayList<>(List.of("true"));
		MapSqlParameterSource params = new MapSqlParameterSource("limit", PAGE_SIZE).addValue("offset", page * PAGE_SIZE);
		if (st != null) {
			where.add("f.status = :status");
			params.addValue("status", st.name());
		}
		if (cat != null) {
			where.add("f.category = :category");
			params.addValue("category", cat.name());
		}
		if (q != null && !q.isBlank()) {
			where.add("(f.title ilike :pattern escape '\\' or f.body ilike :pattern escape '\\')");
			params.addValue("pattern", "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
		}
		String from = " from feedback f join account a on a.id = f.author_account_id where " + String.join(" and ", where);
		List<Summary> items = jdbc.query("select " + SUMMARY_COLUMNS + from + " order by f.updated_at desc, f.id desc limit :limit offset :offset",
				params, FeedbackService::summary);
		return new Page(items, page, PAGE_SIZE, jdbc.queryForObject("select count(*)" + from, params, Long.class));
	}

	@Transactional
	public Detail change(Long id, ChangeRequest request, SessionUser developer) {
		Status to = parse(Status.class, request.status());
		String reason = request.reason() == null ? null : text(request.reason());
		if (to == null || request.version() == null) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		if (to.needsReason() && (reason == null || reason.isEmpty())) {
			throw new FieldValidationException(Map.of("reason", "검증 요청·보류에는 사유가 필요해요."));
		}
		if (reason != null && reason.length() > REASON_MAX) {
			throw new FieldValidationException(Map.of("reason", "사유는 300자 이하로 써 주세요."));
		}
		String resolution = request.resolution() == null ? null : text(request.resolution());
		if (resolution != null && resolution.length() > BODY_MAX) {
			throw new FieldValidationException(Map.of("resolution", "처리 내용은 2000자 이하로 써 주세요."));
		}
		String prUrl = request.prUrl() == null ? null : request.prUrl().strip();
		if (prUrl != null && !prUrl.isEmpty() && (prUrl.length() > PR_URL_MAX || !PR_URL.matcher(prUrl).matches())) {
			throw new FieldValidationException(Map.of("prUrl", "PR 링크는 https://github.com/ 로 시작해야 해요."));
		}
		Map<String, Object> row = jdbc.queryForList("select status, version, author_account_id from feedback where id = :id for update",
				new MapSqlParameterSource("id", id)).stream().findFirst().orElseThrow(() -> new ApiException(ErrorCode.FEEDBACK_NOT_FOUND));
		if (((Number) row.get("version")).longValue() != request.version()) {
			throw new ApiException(ErrorCode.FEEDBACK_CHANGED);
		}
		Status from = Status.valueOf((String) row.get("status"));
		long next = request.version() + 1;
		MapSqlParameterSource params = new MapSqlParameterSource("id", id).addValue("status", to.name())
			.addValue("version", next)
			.addValue("now", Instant.now(clock).atOffset(ZoneOffset.UTC))
			.addValue("resolution", resolution == null ? null : (resolution.isEmpty() ? "" : resolution))
			.addValue("keepResolution", resolution == null)
			.addValue("pr", prUrl == null ? null : (prUrl.isEmpty() ? "" : prUrl))
			.addValue("keepPr", prUrl == null)
			.addValue("reason", reason == null || reason.isEmpty() ? null : reason)
			.addValue("from", from.name())
			.addValue("actor", developer.id());
		jdbc.update("""
				update feedback set status = :status, version = :version, updated_at = :now,
				    resolution = case when :keepResolution then resolution else nullif(:resolution, '') end,
				    pr_url = case when :keepPr then pr_url else nullif(:pr, '') end
				where id = :id
				""", params);
		if (from != to) {
			jdbc.update("""
					insert into feedback_history (feedback_id, from_status, to_status, reason, actor_account_id, created_at)
					values (:id, :from, :status, :reason, :actor, :now)
					""", params);
			notifier.feedbackStatusChanged(id, ((Number) row.get("author_account_id")).longValue(), developer.id(), next);
		}
		return load(id);
	}

	private Detail load(Long id) {
		List<History> history = jdbc.query("""
				select from_status, to_status, reason, created_at from feedback_history where feedback_id = :id
				order by created_at desc, id desc
				""", new MapSqlParameterSource("id", id), (rs, n) -> new History(Status.valueOf(rs.getString(1)),
				Status.valueOf(rs.getString(2)), rs.getString(3), rs.getTimestamp(4).toInstant()));
		return jdbc.queryForObject("select f.*, " + Mentions.labelSql("a") + " as author from feedback f "
				+ "join account a on a.id = f.author_account_id where f.id = :id", new MapSqlParameterSource("id", id),
				(rs, n) -> new Detail(rs.getLong("id"), Category.valueOf(rs.getString("category")), rs.getString("title"),
						rs.getString("body"), rs.getString("page_path"), Status.valueOf(rs.getString("status")),
						rs.getString("resolution"), rs.getString("pr_url"), rs.getLong("version"), rs.getString("author"),
						rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), history));
	}

	private boolean isDeveloper(Long accountId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"select exists (select 1 from account_role where account_id = :id and role = 'DEVELOPER')",
				new MapSqlParameterSource("id", accountId), Boolean.class));
	}

	private static Summary summary(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new Summary(rs.getLong("id"), Category.valueOf(rs.getString("category")), rs.getString("title"),
				Status.valueOf(rs.getString("status")), rs.getString("author"), rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant());
	}

	private static String text(String raw) {
		return raw == null ? "" : raw.strip();
	}

	/** 접수 화면 경로만 남긴다(질의·조각은 토큰이 섞일 수 있어 버림). */
	private static String pagePath(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String path = raw.strip().replaceFirst("[?#].*$", "");
		return path.startsWith("/") ? path.substring(0, Math.min(path.length(), 200)) : null;
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String value) {
		try {
			return value == null ? null : Enum.valueOf(type, value);
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static <E extends Enum<E>> E blankOr(String value, Class<E> type) {
		if (value == null || value.isBlank()) {
			return null;
		}
		E parsed = parse(type, value);
		if (parsed == null) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		return parsed;
	}

}

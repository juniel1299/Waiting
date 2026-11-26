package com.daumjari.errorlog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.LoginThrottle;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;

/**
 * 화면 오류 보고(누구나, IP 당 횟수 제한)와 개발자용 에러 로그 목록(/api/dev 는 개발자 역할만, SecurityConfig).
 * 목록은 요약만: 출처·가린 메시지·경로 틀·배포 버전·횟수·처음/마지막 시각·마지막 추적 ID·Sentry 링크(설정했을 때).
 */
@RestController
public class ErrorLogController {

	record ClientError(String message, String path, String release) {
	}

	record ErrorGroupView(Long id, String source, String message, String path, String release, long occurrences,
			Instant firstSeenAt, Instant lastSeenAt, String lastTraceId, String sentryUrl) {
	}

	record ErrorPage(List<ErrorGroupView> items, int page, int size, long totalElements) {
	}

	static final int PAGE_SIZE = 20;

	private final ErrorLog errorLog;

	private final LoginThrottle throttle;

	private final NamedParameterJdbcTemplate jdbc;

	private final int maxReportsPerIp;

	private final String issueUrlTemplate;

	public ErrorLogController(ErrorLog errorLog, LoginThrottle throttle, NamedParameterJdbcTemplate jdbc,
			@Value("${daumjari.error-log.max-reports-per-ip:60}") int maxReportsPerIp,
			@Value("${daumjari.sentry.issue-url-template:}") String issueUrlTemplate) {
		this.errorLog = errorLog;
		this.throttle = throttle;
		this.jdbc = jdbc;
		this.maxReportsPerIp = maxReportsPerIp;
		this.issueUrlTemplate = issueUrlTemplate == null ? "" : issueUrlTemplate.strip();
	}

	/** 본문(글·채팅)·쿠키·저장소 값은 받지 않는다. 메시지·경로·배포 버전만, 저장 전에 가린다. */
	@PostMapping("/api/errors")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public void report(@RequestBody ClientError body, HttpServletRequest request) {
		throttle.countClientError(request.getRemoteAddr(), maxReportsPerIp);
		errorLog.recordFrontend(body.message(), body.path(), body.release(), ErrorLog.newTraceId());
	}

	@GetMapping("/api/dev/errors")
	@Transactional(readOnly = true)
	public ErrorPage list(@RequestParam(required = false) String q, @RequestParam(required = false) String source,
			@RequestParam(defaultValue = "0") int page) {
		if (page < 0 || (source != null && !source.isBlank() && !List.of("FRONTEND", "BACKEND").contains(source))) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		List<String> where = new ArrayList<>(List.of("true"));
		MapSqlParameterSource params = new MapSqlParameterSource("limit", PAGE_SIZE).addValue("offset", page * PAGE_SIZE);
		if (source != null && !source.isBlank()) {
			where.add("source = :source");
			params.addValue("source", source);
		}
		if (q != null && !q.isBlank()) {
			where.add("(message ilike :pattern escape '\\' or path ilike :pattern escape '\\' or last_trace_id = :q)");
			params.addValue("pattern", "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
				.addValue("q", q.strip());
		}
		String filter = String.join(" and ", where);
		List<ErrorGroupView> items = jdbc.query("select * from error_group where " + filter
				+ " order by last_seen_at desc, id desc limit :limit offset :offset", params,
				(rs, n) -> new ErrorGroupView(rs.getLong("id"), rs.getString("source"), rs.getString("message"), rs.getString("path"),
						rs.getString("release"), rs.getLong("occurrences"), rs.getTimestamp("first_seen_at").toInstant(),
						rs.getTimestamp("last_seen_at").toInstant(), rs.getString("last_trace_id"), sentryUrl(rs.getString("last_event_id"))));
		long total = jdbc.queryForObject("select count(*) from error_group where " + filter, params, Long.class);
		return new ErrorPage(items, page, PAGE_SIZE, total);
	}

	private String sentryUrl(String eventId) {
		return eventId == null || issueUrlTemplate.isEmpty() ? null : issueUrlTemplate.replace("{eventId}", eventId);
	}

}

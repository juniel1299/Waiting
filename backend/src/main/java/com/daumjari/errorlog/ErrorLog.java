package com.daumjari.errorlog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 오류 요약 수집. 같은 지문(출처 + 숫자를 지운 메시지 + 경로 틀)은 한 행에 횟수·처음/마지막 시각만 늘린다.
 * 새 오류이거나 마지막 알림에서 1시간이 지났으면 개발자에게 알림 + (DSN 이 있으면) Sentry 로 한 건 보낸다.
 * 이 기록이 실패해도 원래 요청에는 영향이 없다(예외를 삼키고 경고 로그만).
 */
@Component
public class ErrorLog {

	public enum Source {
		FRONTEND, BACKEND
	}

	static final Duration ALERT_INTERVAL = Duration.ofHours(1);

	static final Duration RETENTION = Duration.ofDays(30);

	static final int RELEASE_MAX = 40;

	private static final Logger log = LoggerFactory.getLogger(ErrorLog.class);

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final String UPSERT = """
			insert into error_group (fingerprint, source, message, path, release, last_trace_id, occurrences, first_seen_at, last_seen_at)
			values (:fp, :source, :message, :path, :release, :trace, 1, :now, :now)
			on conflict (fingerprint) do update set occurrences = error_group.occurrences + 1, last_seen_at = :now,
			    message = excluded.message, release = excluded.release, last_trace_id = excluded.last_trace_id
			returning id
			""";

	private static final String CLAIM_ALERT = """
			update error_group set last_alerted_at = :now
			where id = :id and (last_alerted_at is null or last_alerted_at <= :due)
			""";

	private static final String NOTIFY_DEVELOPERS = """
			insert into notification (recipient_account_id, type, error_group_id, event_key, created_at)
			select r.account_id, 'ERROR_ALERT', :id, 'ERROR_ALERT:' || :id || ':' || :stamp || ':' || r.account_id, :now
			from account_role r join account a on a.id = r.account_id
			where r.role = 'DEVELOPER' and a.deleted_at is null
			on conflict (event_key) do nothing
			""";

	private final NamedParameterJdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private final Clock clock;

	private final SentryForwarder sentry;

	private final String backendRelease;

	public ErrorLog(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock,
			SentryForwarder sentry, @Value("${daumjari.release:local}") String backendRelease) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
		this.sentry = sentry;
		this.backendRelease = release(backendRelease);
	}

	public static String newTraceId() {
		byte[] bytes = new byte[8];
		RANDOM.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	/** 서버에서 처리하지 못한 예외. */
	public void recordBackend(Throwable error, String rawPath, String traceId) {
		String message = error.getClass().getSimpleName() + ": " + error.getMessage();
		record(Source.BACKEND, message, rawPath, backendRelease, traceId);
	}

	/** 화면에서 보낸 오류 보고. */
	public void recordFrontend(String message, String rawPath, String release, String traceId) {
		record(Source.FRONTEND, message, rawPath, release(release), traceId);
	}

	private void record(Source source, String rawMessage, String rawPath, String release, String traceId) {
		try {
			String message = ErrorMasking.message(rawMessage);
			String path = ErrorMasking.path(rawPath);
			String fingerprint = fingerprint(source, message, path);
			Instant now = Instant.now(clock);
			Long alertedGroup = transaction.execute(status -> upsertAndClaim(source, message, path, release, traceId, fingerprint, now));
			if (alertedGroup != null) {
				String eventId = sentry.send(source.name(), message, path, release, traceId, fingerprint, now);
				if (eventId != null) {
					jdbc.update("update error_group set last_event_id = :e where id = :id",
							new MapSqlParameterSource("e", eventId).addValue("id", alertedGroup));
				}
			}
		}
		catch (RuntimeException failure) {
			log.warn("에러 로그 기록 실패 (원래 요청에는 영향 없음): {}", failure.toString());
		}
	}

	/** 행을 늘리고, 알림 차례를 가져왔으면(1시간에 한 번) 개발자 알림을 넣고 그 행 id 를 돌려준다. */
	private Long upsertAndClaim(Source source, String message, String path, String release, String traceId, String fingerprint,
			Instant now) {
		MapSqlParameterSource params = new MapSqlParameterSource("fp", fingerprint).addValue("source", source.name())
			.addValue("message", message)
			.addValue("path", path)
			.addValue("release", release)
			.addValue("trace", traceId)
			.addValue("now", now.atOffset(ZoneOffset.UTC))
			.addValue("due", now.minus(ALERT_INTERVAL).atOffset(ZoneOffset.UTC));
		Long id = jdbc.queryForObject(UPSERT, params, Long.class);
		params.addValue("id", id).addValue("stamp", now.toEpochMilli());
		if (jdbc.update(CLAIM_ALERT, params) == 0) {
			return null;
		}
		jdbc.update(NOTIFY_DEVELOPERS, params);
		return id;
	}

	/** 마지막으로 본 지 30일이 지난 오류를 지운다(알림은 FK cascade). 여러 인스턴스가 돌아도 같은 결과. */
	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
	public void purgeExpired() {
		jdbc.update("delete from error_group where last_seen_at < :cutoff",
				new MapSqlParameterSource("cutoff", Instant.now(clock).minus(RETENTION).atOffset(ZoneOffset.UTC)));
	}

	static String fingerprint(Source source, String message, String path) {
		String key = source + "|" + message.replaceAll("\\d+", "#") + "|" + path;
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String release(String raw) {
		String r = raw == null || raw.isBlank() ? "unknown" : raw.strip().replaceAll("[^A-Za-z0-9._-]", "");
		r = r.isEmpty() ? "unknown" : r;
		return r.length() > RELEASE_MAX ? r.substring(0, RELEASE_MAX) : r;
	}

}

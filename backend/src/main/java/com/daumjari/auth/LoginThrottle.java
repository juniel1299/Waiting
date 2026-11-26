package com.daumjari.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 로그인 시도 제한. 두 키로 센다.
 * <ul>
 * <li>계정+IP: 같은 아이디를 같은 클라이언트 IP 에서 시도한 수. 다른 IP 의 정상 사용자는 막지 않는다.</li>
 * <li>IP: 같은 클라이언트 IP 의 실패 수. 아이디를 바꿔 가며 대는 시도를 완화한다.</li>
 * </ul>
 * 시도는 비밀번호 확인 전에 DB 원자적 upsert 로 먼저 센다. 동시에 들어와도 빠지거나 한도를 넘겨 통과하지 않는다.
 * 존재하지 않는 아이디도 같은 키 규칙·같은 응답을 쓴다.
 */
@Component
public class LoginThrottle {

	private static final String COUNT_ATTEMPT = """
			insert into login_attempt (throttle_key, window_start, attempts) values (?, ?, 1)
			on conflict (throttle_key) do update set
			    attempts = case when login_attempt.window_start <= ? then 1 else login_attempt.attempts + 1 end,
			    window_start = case when login_attempt.window_start <= ? then excluded.window_start
			                        else login_attempt.window_start end
			returning window_start, attempts
			""";

	private final JdbcTemplate jdbc;

	private final LoginThrottleProperties properties;

	private final Clock clock;

	public LoginThrottle(JdbcTemplate jdbc, LoginThrottleProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.clock = clock;
	}

	/** 이번 시도를 센다. 한도를 넘으면 비밀번호 확인 없이 LoginThrottledException. */
	public void countAttempt(String loginId, String clientIp) {
		Instant now = Instant.now(clock);
		long retryAfter = Math.max(
				retryAfterSeconds(count(accountKey(loginId, clientIp), now), properties.maxPerAccountIp(), now),
				retryAfterSeconds(count(ipKey(clientIp), now), properties.maxPerIp(), now));
		if (retryAfter > 0) {
			throw new LoginThrottledException(retryAfter);
		}
	}

	/** 데모 체험 시작 남용 제한: 같은 클라이언트 IP 로 로그인 제한과 같은 창 안에 max 회까지. */
	public void countDemoStart(String clientIp, int max) {
		countPerIp("demo-start:", clientIp, max);
	}

	/** 회원가입 시도 남용 제한: 성공·실패와 무관하게 같은 클라이언트 IP 로 같은 창 안에 max 회까지. */
	public void countSignup(String clientIp, int max) {
		countPerIp("signup:", clientIp, max);
	}

	/** 화면 오류 보고 남용 제한: 같은 클라이언트 IP 로 같은 창 안에 max 회까지. */
	public void countClientError(String clientIp, int max) {
		countPerIp("client-error:", clientIp, max);
	}

	/** 개선사항 접수 남용 제한: 계정당 maxPerAccount, 같은 IP 로 maxPerIp (같은 창). */
	public void countFeedback(Long accountId, String clientIp, int maxPerAccount, int maxPerIp) {
		countPerIp("feedback-account:", String.valueOf(accountId), maxPerAccount);
		countPerIp("feedback-ip:", clientIp, maxPerIp);
	}

	private void countPerIp(String purpose, String clientIp, int max) {
		Instant now = Instant.now(clock);
		long retryAfter = retryAfterSeconds(count(sha256(purpose + clientIp), now), max, now);
		if (retryAfter > 0) {
			throw new LoginThrottledException(retryAfter);
		}
	}

	/** 로그인 성공: 계정+IP 카운터는 지우고, IP 카운터에서는 이번 시도(실패가 아니었음)만 뺀다. */
	public void onSuccess(String loginId, String clientIp) {
		jdbc.update("delete from login_attempt where throttle_key = ?", accountKey(loginId, clientIp));
		jdbc.update("update login_attempt set attempts = attempts - 1 where throttle_key = ? and attempts > 0",
				ipKey(clientIp));
	}

	/** 창이 지난 행 정리. 여러 인스턴스가 동시에 돌아도 같은 결과다. */
	@Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT10M")
	public void purgeExpired() {
		jdbc.update("delete from login_attempt where window_start <= ?",
				utc(Instant.now(clock).minus(properties.window())));
	}

	private record Window(Instant start, int attempts) {
	}

	private Window count(String key, Instant now) {
		OffsetDateTime expiredBefore = utc(now.minus(properties.window()));
		return jdbc.queryForObject(COUNT_ATTEMPT,
				(rs, row) -> new Window(rs.getObject(1, OffsetDateTime.class).toInstant(), rs.getInt(2)), key,
				utc(now), expiredBefore, expiredBefore);
	}

	private long retryAfterSeconds(Window window, int max, Instant now) {
		if (window.attempts() <= max) {
			return 0;
		}
		long millis = Duration.between(now, window.start().plus(properties.window())).toMillis();
		return Math.max(1, (millis + 999) / 1000);
	}

	private static String accountKey(String loginId, String clientIp) {
		return sha256("account:" + loginId.strip().toLowerCase(Locale.ROOT) + "|" + clientIp);
	}

	private static String ipKey(String clientIp) {
		return sha256("ip:" + clientIp);
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private static String sha256(String raw) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

}

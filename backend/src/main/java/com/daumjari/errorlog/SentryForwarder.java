package com.daumjari.errorlog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sentry 로 이미 가린 오류 요약 한 건을 보낸다(envelope API, SDK 없이). DSN(SENTRY_DSN)이 비어 있으면 아무것도 하지 않는다.
 * 보내기는 비동기이고 실패해도 무시한다. 무엇을 언제 보낼지는 ErrorLog 가 정한다(같은 오류 1시간에 한 번).
 */
@Component
public class SentryForwarder {

	private static final Logger log = LoggerFactory.getLogger(SentryForwarder.class);

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final URI endpoint;

	private final String auth;

	private final String dsn;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	public SentryForwarder(@Value("${daumjari.sentry.dsn:}") String dsn) {
		URI parsed = parse(dsn);
		this.dsn = parsed == null ? null : dsn.strip();
		this.endpoint = parsed == null ? null : URI.create(parsed.getScheme() + "://" + parsed.getHost()
				+ (parsed.getPort() == -1 ? "" : ":" + parsed.getPort()) + "/api" + parsed.getPath() + "/envelope/");
		this.auth = parsed == null ? null
				: "Sentry sentry_version=7, sentry_client=daumjari/1.0, sentry_key=" + parsed.getUserInfo().split(":")[0];
	}

	public boolean enabled() {
		return endpoint != null;
	}

	/** 보냈으면(보내기 시작했으면) Sentry 이벤트 id, 꺼져 있으면 null. */
	public String send(String source, String message, String path, String release, String traceId, String fingerprint, Instant at) {
		if (!enabled()) {
			return null;
		}
		String eventId = UUID.randomUUID().toString().replace("-", "");
		String event = "{\"event_id\":" + json(eventId) + ",\"timestamp\":" + json(at.toString()) + ",\"level\":\"error\""
				+ ",\"platform\":" + json("BACKEND".equals(source) ? "java" : "javascript") + ",\"release\":" + json(release)
				+ ",\"transaction\":" + json(path) + ",\"message\":{\"formatted\":" + json(message) + "}"
				+ ",\"fingerprint\":[" + json(fingerprint) + "],\"tags\":{\"source\":" + json(source) + ",\"trace_id\":" + json(traceId)
				+ ",\"path\":" + json(path) + "}}";
		String body = "{\"event_id\":" + json(eventId) + ",\"dsn\":" + json(dsn) + "}\n{\"type\":\"event\"}\n" + event + "\n";
		HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
			.header("Content-Type", "application/x-sentry-envelope")
			.header("X-Sentry-Auth", auth)
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, failure) -> {
			if (failure != null || response.statusCode() >= 300) {
				log.warn("Sentry 전송 실패: {}", failure != null ? failure.toString() : "HTTP " + response.statusCode());
			}
		});
		return eventId;
	}

	private static URI parse(String dsn) {
		if (dsn == null || dsn.isBlank()) {
			return null;
		}
		try {
			URI uri = URI.create(dsn.strip());
			if (uri.getUserInfo() == null || uri.getHost() == null || uri.getPath() == null || uri.getPath().length() < 2) {
				throw new IllegalArgumentException("형식");
			}
			return uri;
		}
		catch (IllegalArgumentException e) {
			log.warn("SENTRY_DSN 형식이 맞지 않아 Sentry 전송을 끕니다");
			return null;
		}
	}

	private static String json(String value) {
		StringBuilder out = new StringBuilder("\"");
		for (char c : value.toCharArray()) {
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					}
					else {
						out.append(c);
					}
				}
			}
		}
		return out.append('"').toString();
	}

}

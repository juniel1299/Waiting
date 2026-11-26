package com.daumjari.errorlog;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 에러 로그에 저장·전송하기 전에 지우는 값: 비밀번호·비밀·토큰 이름 뒤의 값, Bearer 토큰, JWT, 쿠키(세션·XSRF), 이메일,
 * 전화번호, 24자 이상 16진수·base64 덩어리. 메시지는 한 줄 300자, 경로는 질의·조각을 버리고 숫자·UUID 를 :id 로 바꾼 200자.
 * 요청·응답 본문(글·채팅)은 처음부터 받지 않는다.
 */
public final class ErrorMasking {

	static final int MESSAGE_MAX = 300;

	static final int PATH_MAX = 200;

	static final String MASK = "[가림]";

	private static final List<Pattern> SECRETS = List.of(
			Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+"),
			Pattern.compile("\\beyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]*"),
			Pattern.compile("(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|authorization|cookie|session|jsessionid|xsrf-token|csrf)"
					+ "(\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&]+)"),
			Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
			Pattern.compile("\\b01[016789][- ]?\\d{3,4}[- ]?\\d{4}\\b"),
			Pattern.compile("\\b[A-Za-z0-9_+/=-]{24,}\\b"));

	private static final Pattern NAMED = SECRETS.get(2);

	private static final Pattern ID_SEGMENT = Pattern
		.compile("/(\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?=/|$)");

	private ErrorMasking() {
	}

	public static String message(String raw) {
		if (raw == null || raw.isBlank()) {
			return "(메시지 없음)";
		}
		String text = raw;
		for (Pattern secret : SECRETS) {
			text = secret == NAMED ? secret.matcher(text).replaceAll("$1$2" + MASK) : secret.matcher(text).replaceAll(MASK);
		}
		text = text.replaceAll("\\s+", " ").strip();
		return text.length() > MESSAGE_MAX ? text.substring(0, MESSAGE_MAX) : text;
	}

	public static String path(String raw) {
		if (raw == null || raw.isBlank()) {
			return "/";
		}
		String path = raw.strip().replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*", "");
		path = path.replaceFirst("[?#].*$", "");
		if (!path.startsWith("/")) {
			path = "/" + path;
		}
		String previous;
		do {
			previous = path;
			path = ID_SEGMENT.matcher(path).replaceAll("/:id");
		}
		while (!path.equals(previous));
		return path.length() > PATH_MAX ? path.substring(0, PATH_MAX) : path;
	}

}

package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 사용자 한 명의 브라우저 역할: 자기 쿠키(JSESSIONID, XSRF-TOKEN)를 들고 실제 포트로 요청한다. */
final class HttpTestClient {
/** 사용자 한 명의 브라우저 역할: 자기 쿠키(JSESSIONID, XSRF-TOKEN)를 들고 요청한다. */


	private final String baseUrl;

	private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

	private final HttpClient http;

	private HttpTestClient(String baseUrl) {
		this.baseUrl = baseUrl;
		this.http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.cookieHandler(cookies)
			.connectTimeout(Duration.ofSeconds(5))
			.build();
	}

	static HttpTestClient login(String baseUrl, String loginId, String password) throws Exception {
		HttpTestClient client = new HttpTestClient(baseUrl);
		client.refreshXsrf();
		HttpResponse<String> response = client.post("/api/auth/login",
				"{\"loginId\":\"%s\",\"password\":\"%s\"}".formatted(loginId, password));
		assertThat(response.statusCode()).as("login %s: %s", loginId, response.body()).isEqualTo(200);
		// 로그인하면 토큰이 재발급되므로 다시 받는다
		client.refreshXsrf();
		return client;
	}

	/** 임의 요청 (커뮤니티 경합 테스트용). json 이 null 이면 본문 없음. */
	HttpResponse<String> send(String method, String path, String json) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
			.header("X-XSRF-TOKEN", xsrfToken())
			.method(method, json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json));
		if (json != null) {
			builder.header("Content-Type", "application/json");
		}
		return http.send(builder.build(), BodyHandlers.ofString());
	}

	/** multipart/form-data 로 파일 하나(field 이름 file)를 올린다. */
	HttpResponse<String> upload(String path, String filename, String contentType, byte[] bytes) throws Exception {
		String boundary = "----daumjari" + System.nanoTime();
		java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: "
				+ contentType + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		body.write(bytes);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.header("X-XSRF-TOKEN", xsrfToken())
			.POST(BodyPublishers.ofByteArray(body.toByteArray()))
			.build();
		return http.send(request, BodyHandlers.ofString());
	}

	HttpResponse<String> reserve(Long sessionId) throws Exception {
		return post("/api/sessions/" + sessionId + "/reservations", "");
	}

	private void refreshXsrf() throws Exception {
		http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/csrf")).GET().build(),
				BodyHandlers.discarding());
	}

	private HttpResponse<String> post(String path, String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
			.header("Content-Type", "application/json")
			.header("X-XSRF-TOKEN", xsrfToken())
			.POST(BodyPublishers.ofString(json))
			.build();
		return http.send(request, BodyHandlers.ofString());
	}

	/** 날 연결(Socket)로 보낼 때 쓰는 쿠키 헤더 값. */
	String cookieHeader() {
		return String.join("; ", cookies.getCookieStore().getCookies().stream().map(c -> c.getName() + "=" + c.getValue()).toList());
	}

	String xsrf() {
		return xsrfToken();
	}

	private String xsrfToken() {
		return cookies.getCookieStore()
			.getCookies()
			.stream()
			.filter(c -> c.getName().equals("XSRF-TOKEN"))
			.map(HttpCookie::getValue)
			.findFirst()
			.orElseThrow();
	}

	HttpResponse<String> waitlist(Long sessionId) throws Exception {
		return post("/api/sessions/" + sessionId + "/waitlist", "");
	}

	/** 운영자 계정 조치: action = suspend · unsuspend · withdraw. */
	HttpResponse<String> moderate(Long accountId, String action, String reason) throws Exception {
		return post("/api/operator/accounts/" + accountId + "/" + action, "{\"reason\":\"%s\"}".formatted(reason));
	}

	HttpResponse<String> withdraw(String password) throws Exception {
		return post("/api/me/withdraw", "{\"password\":\"%s\"}".formatted(password));
	}

	HttpResponse<String> deleteSession(Long sessionId) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/sessions/" + sessionId))
			.header("X-XSRF-TOKEN", xsrfToken())
			.DELETE()
			.build();
		return http.send(request, BodyHandlers.ofString());
	}

	HttpResponse<String> changeOwnCapacity(Long sessionId, int capacity) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/sessions/" + sessionId + "/capacity"))
			.header("Content-Type", "application/json")
			.header("X-XSRF-TOKEN", xsrfToken())
			.method("PATCH", BodyPublishers.ofString("{\"capacity\":" + capacity + "}"))
			.build();
		return http.send(request, BodyHandlers.ofString());
	}

	HttpResponse<String> cancel(Long reservationId) throws Exception {
		return post("/api/reservations/" + reservationId + "/cancel", "");
	}

	/**
	 * 새 브라우저처럼(쿠키 없음) 로그인 한 번을 시도한다. forwardedFor 가 있으면 X-Forwarded-For 로 보낸다
	 * (Tomcat 이 신뢰하는 프록시에서 온 요청이면 이 값이 클라이언트 IP 가 된다).
	 */
	static HttpResponse<String> attemptLogin(String baseUrl, String loginId, String password, String forwardedFor)
			throws Exception {
		HttpTestClient client = new HttpTestClient(baseUrl);
		client.refreshXsrf();
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/login"))
			.header("Content-Type", "application/json")
			.header("X-XSRF-TOKEN", client.xsrfToken())
			.POST(BodyPublishers.ofString("{\"loginId\":\"%s\",\"password\":\"%s\"}".formatted(loginId, password)));
		if (forwardedFor != null) {
			request.header("X-Forwarded-For", forwardedFor);
		}
		return client.http.send(request.build(), BodyHandlers.ofString());
	}

	HttpResponse<String> changeCapacity(Long sessionId, int capacity) throws Exception {
		HttpRequest request = HttpRequest
			.newBuilder(URI.create(baseUrl + "/api/operator/sessions/" + sessionId + "/capacity"))
			.header("Content-Type", "application/json")
			.header("X-XSRF-TOKEN", xsrfToken())
			.method("PATCH", BodyPublishers.ofString("{\"capacity\":" + capacity + "}"))
			.build();
		return http.send(request, BodyHandlers.ofString());
	}

	/** 두 요청을 CyclicBarrier 로 동시에 출발시킨다. 결과는 인자 순서대로. */
	static List<HttpResponse<String>> race(Callable<HttpResponse<String>> first,
			Callable<HttpResponse<String>> second) throws Exception {
		CyclicBarrier start = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<HttpResponse<String>> a = pool.submit(() -> {
				start.await();
				return first.call();
			});
			Future<HttpResponse<String>> b = pool.submit(() -> {
				start.await();
				return second.call();
			});
			return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
		}
		finally {
			pool.shutdownNow();
		}
	}

}

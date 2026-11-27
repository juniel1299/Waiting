package com.daumjari;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.test.context.DynamicPropertyRegistry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 카카오·네이버 대신 쓰는 가짜 OAuth 서버(JDK HttpServer, 새 의존성 없음).
 * 인가 코드가 곧 공급자 사용자 id 의 재료다: 코드 "u1001" → 카카오 id 1001, 네이버 id "n-u1001". 코드 "bad" 는 토큰 교환 실패.
 */
final class FakeOAuthServer {

	static final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();

	private static HttpServer server;

	private FakeOAuthServer() {
	}

	static synchronized void register(DynamicPropertyRegistry registry) throws IOException {
		if (server == null) {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/token", FakeOAuthServer::token);
			server.createContext("/kakao/me", exchange -> me(exchange, code -> "{\"id\":" + code.replaceAll("\\D", "") + ",\"properties\":{\"nickname\":\"카카오닉\"}}"));
			server.createContext("/naver/me", exchange -> me(exchange, code -> "{\"resultcode\":\"00\",\"message\":\"success\",\"response\":{\"id\":\"n-" + code + "\",\"nickname\":\"네이버닉\"}}"));
			server.start();
		}
		String base = "http://127.0.0.1:" + server.getAddress().getPort();
		registry.add("daumjari.oauth.redirect-base", () -> "http://localhost:3100");
		for (String p : new String[] { "kakao", "naver" }) {
			registry.add("daumjari.oauth." + p + ".client-id", () -> "test-" + p + "-client");
			registry.add("daumjari.oauth." + p + ".client-secret", () -> "test-" + p + "-secret");
			registry.add("daumjari.oauth." + p + ".authorize-uri", () -> base + "/" + p + "/authorize");
			registry.add("daumjari.oauth." + p + ".token-uri", () -> base + "/token");
			registry.add("daumjari.oauth." + p + ".user-uri", () -> base + "/" + p + "/me");
		}
	}

	private static void token(HttpExchange exchange) throws IOException {
		Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		tokenRequests.add(form);
		String code = form.getOrDefault("code", "");
		if (code.equals("bad") || !"authorization_code".equals(form.get("grant_type")) || form.get("client_secret") == null) {
			respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
			return;
		}
		respond(exchange, 200, "{\"access_token\":\"tok-" + code + "\",\"token_type\":\"bearer\"}");
	}

	private interface Body {

		String of(String code);

	}

	private static void me(HttpExchange exchange, Body body) throws IOException {
		String auth = exchange.getRequestHeaders().getFirst("Authorization");
		if (auth == null || !auth.startsWith("Bearer tok-")) {
			respond(exchange, 401, "{}");
			return;
		}
		respond(exchange, 200, body.of(auth.substring("Bearer tok-".length())));
	}

	private static void respond(HttpExchange exchange, int status, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private static Map<String, String> form(String body) {
		Map<String, String> out = new HashMap<>();
		for (String pair : body.split("&")) {
			int i = pair.indexOf('=');
			if (i > 0) {
				out.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
			}
		}
		return out;
	}

}

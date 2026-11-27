package com.daumjari.auth;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 카카오·네이버 OAuth 설정과 서버 간 호출(코드 → 액세스 토큰 → 공급자 사용자 id). 토큰은 이 호출 안에서만 쓰고 버린다.
 * 동의 항목(scope)은 요청하지 않는다: 공급자 기본 제공 값(id·닉네임) 중 id 만 쓴다.
 */
@Component
public class SocialProviders {

	public enum Provider {
		KAKAO, NAVER;

		public String path() {
			return name().toLowerCase(Locale.ROOT);
		}

		static Provider fromPath(String path) {
			for (Provider p : values()) {
				if (p.path().equals(path)) {
					return p;
				}
			}
			return null;
		}
	}

	record Config(String clientId, String clientSecret, String authorizeUri, String tokenUri, String userUri) {
	}

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final Map<Provider, Config> configs;

	private final String redirectBase;

	private final RestClient http;

	public SocialProviders(Environment env, @Value("${daumjari.oauth.redirect-base:}") String redirectBase) {
		this.configs = Map.of(Provider.KAKAO, config(env, "kakao"), Provider.NAVER, config(env, "naver"));
		this.redirectBase = redirectBase == null ? "" : redirectBase.strip().replaceAll("/+$", "");
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(TIMEOUT);
		factory.setReadTimeout(TIMEOUT);
		this.http = RestClient.builder().requestFactory(factory).build();
	}

	private static Config config(Environment env, String name) {
		String prefix = "daumjari.oauth." + name + ".";
		return new Config(env.getProperty(prefix + "client-id", ""), env.getProperty(prefix + "client-secret", ""),
				env.getProperty(prefix + "authorize-uri", ""), env.getProperty(prefix + "token-uri", ""),
				env.getProperty(prefix + "user-uri", ""));
	}

	/** 클라이언트 id 와 redirect-base 가 있어야 켜진다. 네이버는 토큰 교환에 시크릿이 꼭 필요하다. */
	public boolean enabled(Provider p) {
		Config c = configs.get(p);
		return !redirectBase.isEmpty() && !c.clientId().isBlank() && (p != Provider.NAVER || !c.clientSecret().isBlank());
	}

	String redirectUri(Provider p) {
		return redirectBase + "/api/auth/oauth/" + p.path() + "/callback";
	}

	String authorizeUrl(Provider p, String state) {
		Config c = configs.get(p);
		return UriComponentsBuilder.fromUriString(c.authorizeUri())
			.queryParam("response_type", "code")
			.queryParam("client_id", c.clientId())
			.queryParam("redirect_uri", redirectUri(p))
			.queryParam("state", state)
			.encode()
			.toUriString();
	}

	/** 인가 코드를 공급자 사용자 id 로 바꾼다. 실패하면 예외(호출한 쪽이 OAUTH_FAILED 로 돌린다). */
	@SuppressWarnings("unchecked")
	String providerUserId(Provider p, String code, String state) {
		Config c = configs.get(p);
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("client_id", c.clientId());
		if (!c.clientSecret().isBlank()) {
			form.add("client_secret", c.clientSecret());
		}
		form.add("redirect_uri", redirectUri(p));
		form.add("code", code);
		form.add("state", state);
		Map<String, Object> token = http.post().uri(c.tokenUri()).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
			.retrieve().body(Map.class);
		Object accessToken = token == null ? null : token.get("access_token");
		if (!(accessToken instanceof String bearer) || bearer.isBlank()) {
			throw new IllegalStateException("액세스 토큰 없음");
		}
		Map<String, Object> me = http.get().uri(c.userUri()).header("Authorization", "Bearer " + bearer).retrieve().body(Map.class);
		Object id = me == null ? null
				: p == Provider.KAKAO ? me.get("id") : me.get("response") instanceof Map<?, ?> r ? r.get("id") : null;
		String value = id == null ? "" : String.valueOf(id).strip();
		if (value.isEmpty() || value.length() > 100) {
			throw new IllegalStateException("공급자 사용자 id 없음");
		}
		return value;
	}

}

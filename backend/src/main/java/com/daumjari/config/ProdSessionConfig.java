package com.daumjari.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * prod: 세션을 PostgreSQL(spring_session, V6)에 저장한다. 무료 호스팅의 절전·재시작에도 로그인·데모 세션이 남는다.
 * 만료 세션 정리는 10분마다 (앱이 깨어 있을 때만 돈다).
 */
@Configuration
@Profile("prod")
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 1800, cleanupCron = "0 */10 * * * *")
public class ProdSessionConfig {

	@Bean
	CookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		// 도메인은 지정하지 않는다: 브라우저는 프런트 주소(같은 출처)의 쿠키로만 본다
		serializer.setCookieName("JSESSIONID");
		serializer.setCookiePath("/");
		serializer.setUseHttpOnlyCookie(true);
		serializer.setUseSecureCookie(secure);
		serializer.setSameSite("Lax");
		return serializer;
	}

}

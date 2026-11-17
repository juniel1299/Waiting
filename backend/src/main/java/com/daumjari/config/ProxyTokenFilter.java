package com.daumjari.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

import org.springframework.web.filter.OncePerRequestFilter;

import com.daumjari.common.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 공개 주소에 놓인 백엔드를 프런트 프록시만 쓰게 한다 (daumjari.proxy.shared-secret 이 있을 때만 동작).
 * 토큰이 맞으면 프록시가 알려준 X-Daumjari-Client-IP 를 클라이언트 IP(getRemoteAddr)로 쓴다. 다른 전달 헤더는 보지 않는다.
 */
public class ProxyTokenFilter extends OncePerRequestFilter {

	static final String TOKEN_HEADER = "X-Daumjari-Proxy-Token";

	static final String CLIENT_IP_HEADER = "X-Daumjari-Client-IP";

	private static final Pattern IP = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

	private final byte[] secret;

	public ProxyTokenFilter(String secret) {
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return secret.length == 0 || path.equals("/actuator/health") || path.startsWith("/actuator/health/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String token = request.getHeader(TOKEN_HEADER);
		// 길이가 달라도 상수 시간 비교
		if (token == null || !MessageDigest.isEqual(secret, token.getBytes(StandardCharsets.UTF_8))) {
			ErrorCode code = ErrorCode.PROXY_REQUIRED;
			response.setStatus(code.status().value());
			response.setContentType("application/json;charset=UTF-8");
			response.getWriter().write("{\"code\":\"" + code.name() + "\",\"message\":\"" + code.message() + "\"}");
			return;
		}
		String clientIp = request.getHeader(CLIENT_IP_HEADER);
		if (clientIp == null || !IP.matcher(clientIp).matches()) {
			chain.doFilter(request, response);
			return;
		}
		chain.doFilter(new HttpServletRequestWrapper(request) {
			@Override
			public String getRemoteAddr() {
				return clientIp;
			}
		}, response);
	}

}

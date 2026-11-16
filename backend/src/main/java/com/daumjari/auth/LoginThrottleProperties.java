package com.daumjari.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그인 실패 제한 설정 (application.yml daumjari.login-throttle).
 *
 * @param maxPerAccountIp 같은 아이디·같은 클라이언트 IP 로 창 안에 허용하는 시도 수
 * @param maxPerIp 같은 클라이언트 IP 로 창 안에 허용하는 실패 수 (여러 아이디를 돌려 대는 시도 완화)
 * @param window 집계 창 (고정 창, 첫 시도부터)
 */
@ConfigurationProperties("daumjari.login-throttle")
public record LoginThrottleProperties(int maxPerAccountIp, int maxPerIp, Duration window) {
}

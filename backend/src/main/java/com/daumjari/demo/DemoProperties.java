package com.daumjari.demo;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 데모 체험 설정 (application.yml daumjari.demo).
 *
 * @param ttl 체험 데이터 유지 시간. 지나면 요청은 410, 정리 작업이 지운다
 * @param maxStartsPerIp 같은 클라이언트 IP 의 체험 시작 수 (로그인 제한과 같은 창)
 */
@ConfigurationProperties("daumjari.demo")
public record DemoProperties(Duration ttl, int maxStartsPerIp) {
}

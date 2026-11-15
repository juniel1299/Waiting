package com.daumjari.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시각 판단은 모두 이 Clock 으로 한다. 테스트에서 고정 시계로 바꿔 경계를 검증한다. */
@Configuration
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}

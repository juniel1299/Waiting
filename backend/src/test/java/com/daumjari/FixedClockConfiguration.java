package com.daumjari;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 테스트에서 시각을 고정·이동할 수 있는 Clock. 매 시점은 Clock.fixed 로 고정된다. */
@TestConfiguration(proxyBeanMethods = false)
class FixedClockConfiguration {

	@Bean
	@Primary
	TestClock testClock() {
		return new TestClock();
	}

	static final class TestClock extends Clock {

		private volatile Clock fixed = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);

		void setInstant(Instant instant) {
			fixed = Clock.fixed(instant, ZoneOffset.UTC);
		}

		@Override
		public ZoneId getZone() {
			return fixed.getZone();
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return fixed.withZone(zone);
		}

		@Override
		public Instant instant() {
			return fixed.instant();
		}

	}

}

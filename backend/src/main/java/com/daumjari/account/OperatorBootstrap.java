package com.daumjari.account;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 환경 운영자 계정 일회성 생성. BOOTSTRAP_OPERATOR_LOGIN_ID·PASSWORD 가 있을 때만 동작한다.
 * 이미 있으면 바꾸지 않는다 (비밀번호도 그대로). 비밀번호는 로그에 남기지 않는다. 생성 뒤 환경변수를 지운다.
 */
@Component
public class OperatorBootstrap implements ApplicationRunner {

	static final int MIN_PASSWORD_LENGTH = 12;

	private static final Logger log = LoggerFactory.getLogger(OperatorBootstrap.class);

	private final AccountRepository accounts;

	private final PasswordEncoder passwordEncoder;

	private final Clock clock;

	private final String loginId;

	private final String password;

	public OperatorBootstrap(AccountRepository accounts, PasswordEncoder passwordEncoder, Clock clock,
			@Value("${daumjari.bootstrap-operator.login-id:}") String loginId,
			@Value("${daumjari.bootstrap-operator.password:}") String password) {
		this.accounts = accounts;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
		this.loginId = loginId.strip();
		this.password = password;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (loginId.isEmpty()) {
			return;
		}
		if (password.length() < MIN_PASSWORD_LENGTH) {
			throw new IllegalStateException(
					"BOOTSTRAP_OPERATOR_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters");
		}
		if (accounts.findByLoginId(loginId).isPresent()) {
			log.info("operator bootstrap skipped: account '{}' already exists", loginId);
			return;
		}
		accounts.save(Account.create(loginId, passwordEncoder.encode(password), "운영자", Role.OPERATOR,
				Instant.now(clock)));
		log.info("operator bootstrap created account '{}'", loginId);
	}

}

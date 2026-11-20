package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.OperatorBootstrap;
import com.daumjari.account.Role;
import com.daumjari.seed.LocalSeedData;

/** 공개 배포 프로필: 시드 없음, Secure 쿠키 기본, 운영자 일회성 생성. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "daumjari.bootstrap-operator.login-id=ops-admin",
		"daumjari.bootstrap-operator.password=correct-horse-battery",
		"daumjari.proxy.shared-secret=prod-profile-test-secret" })
@ActiveProfiles("prod")
@Testcontainers(disabledWithoutDocker = true)
class ProdProfileTests {

	@Autowired
	ApplicationContext context;

	@Autowired
	Environment environment;

	@Autowired
	AccountRepository accounts;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	OperatorBootstrap bootstrap;

	@Test
	void localSeedDoesNotRunInProd() {
		assertThat(context.getBeanProvider(LocalSeedData.class).getIfAvailable()).isNull();
		assertThat(accounts.findByLoginId("alice")).isEmpty();
		assertThat(accounts.findByLoginId("operator")).isEmpty();
	}

	@Test
	void sessionCookieIsSecureByDefault() {
		assertThat(environment.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
	}

	@Test
	void operatorIsCreatedOnceAndCanUseThePassword() {
		Account created = accounts.findByLoginId("ops-admin").orElseThrow();
		assertThat(created.getRole()).isEqualTo(Role.OPERATOR);
		assertThat(passwordEncoder.matches("correct-horse-battery", created.getPasswordHash())).isTrue();

		bootstrap.run(new DefaultApplicationArguments());

		assertThat(accounts.findAll()).filteredOn(a -> a.getLoginId().equals("ops-admin")).hasSize(1);
		assertThat(accounts.findByLoginId("ops-admin").orElseThrow().getPasswordHash())
			.isEqualTo(created.getPasswordHash());
	}

	@Test
	void shortBootstrapPasswordFailsFast() {
		OperatorBootstrap weak = new OperatorBootstrap(accounts, passwordEncoder, java.time.Clock.systemUTC(),
				"ops-weak", "short");
		assertThatThrownBy(() -> weak.run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageNotContaining("short");
		assertThat(accounts.findByLoginId("ops-weak")).isEmpty();
	}

}

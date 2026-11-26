package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V17 이관을 실제 PostgreSQL 에서: V16 까지 올린 DB 에 기존 회원을 넣고 V17 을 적용한다.
 * 닉네임 정규화, 대소문자만 다른 중복은 먼저 가입한 사람만 유지, 닉네임 없는 일반 회원에게 무작위 닉네임(탈퇴 회원 제외),
 * 그리고 이후 대소문자만 다른 닉네임은 유일 인덱스가 막는다.
 */
@Testcontainers(disabledWithoutDocker = true)
class NicknameMigrationTests {

	@Test
	void v17NormalizesDedupesAndBackfillsExistingNicknames() {
		try (PostgreSQLContainer db = new PostgreSQLContainer(DockerImageName.parse("postgres:16"))) {
			db.start();
			DriverManagerDataSource ds = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
			Flyway.configure().dataSource(ds).target("16").load().migrate();
			JdbcTemplate jdbc = new JdbcTemplate(ds);
			long first = insert(jdbc, "m1", "  한   Byeol ", null);
			long dup = insert(jdbc, "m2", "한 BYEOL", null);
			long empty = insert(jdbc, "m3", null, null);
			long withdrawn = insert(jdbc, "m4", null, "now()");

			Flyway.configure().dataSource(ds).load().migrate();

			List<String> names = jdbc.queryForList("select coalesce(nickname, '-') from account where id in (?, ?, ?, ?) order by id",
					String.class, first, dup, empty, withdrawn);
			assertThat(names.get(0)).isEqualTo("한 Byeol");
			assertThat(names.get(1)).isEqualTo("한 BYEOL" + dup);
			assertThat(names.get(2)).matches("[가-힣]+" + empty);
			assertThat(names.get(3)).isEqualTo("-");
			assertThatThrownBy(() -> jdbc.update("update account set nickname = '한 byeol' where id = ?", empty))
				.isInstanceOf(DuplicateKeyException.class);
		}
	}

	private static long insert(JdbcTemplate jdbc, String loginId, String nickname, String deletedAt) {
		return jdbc.queryForObject("insert into account (login_id, password_hash, display_name, role, created_at, nickname, deleted_at) "
				+ "values (?, 'x', ?, 'PARTICIPANT', now(), ?, " + (deletedAt == null ? "null" : deletedAt) + ") returning id",
				Long.class, loginId, loginId, nickname);
	}

}

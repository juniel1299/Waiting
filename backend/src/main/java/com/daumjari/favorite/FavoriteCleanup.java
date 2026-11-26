package com.daumjari.favorite;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** 탈퇴(본인·운영자 강제)하면 그 계정의 즐겨찾기를 지운다. 계정 행은 익명화만 하고 남으므로 FK cascade 가 닿지 않는다. */
@Component
public class FavoriteCleanup {

	private final NamedParameterJdbcTemplate jdbc;

	public FavoriteCleanup(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void forgetAccount(Long accountId) {
		jdbc.update("delete from favorite where account_id = :id", new MapSqlParameterSource("id", accountId));
	}

}

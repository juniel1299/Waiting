package com.daumjari.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.daumjari.auth.SocialProviders.Provider;

/** social_account 행 다루기. 유일성(한 소셜 계정은 한 회원, 회원당 공급자 하나)은 DB 유일 제약이 지킨다. */
@Component
public class SocialAccounts {

	public enum LinkResult {
		LINKED, ALREADY_LINKED_TO_OTHER, PROVIDER_ALREADY_LINKED
	}

	public record Linked(String provider, Instant linkedAt) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	private final Clock clock;

	public SocialAccounts(NamedParameterJdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** 넣어 보고(충돌이면 아무것도 안 함) 왜 안 들어갔는지 다시 읽는다. 동시에 같은 소셜 계정을 연결해도 한 행만 남는다. */
	LinkResult link(Long accountId, Provider provider, String providerUserId) {
		MapSqlParameterSource p = new MapSqlParameterSource("account", accountId).addValue("provider", provider.name())
			.addValue("puid", providerUserId)
			.addValue("now", Instant.now(clock).atOffset(ZoneOffset.UTC));
		if (jdbc.update("""
				insert into social_account (account_id, provider, provider_user_id, linked_at)
				values (:account, :provider, :puid, :now) on conflict do nothing
				""", p) == 1) {
			return LinkResult.LINKED;
		}
		Optional<Long> owner = accountFor(provider, providerUserId);
		if (owner.isPresent()) {
			return owner.get().equals(accountId) ? LinkResult.LINKED : LinkResult.ALREADY_LINKED_TO_OTHER;
		}
		return LinkResult.PROVIDER_ALREADY_LINKED;
	}

	Optional<Long> accountFor(Provider provider, String providerUserId) {
		return jdbc.queryForList("select account_id from social_account where provider = :provider and provider_user_id = :puid",
				new MapSqlParameterSource("provider", provider.name()).addValue("puid", providerUserId), Long.class)
			.stream().findFirst();
	}

	List<Linked> of(Long accountId) {
		return jdbc.query("select provider, linked_at from social_account where account_id = :id order by provider",
				new MapSqlParameterSource("id", accountId), (rs, n) -> new Linked(rs.getString(1), rs.getTimestamp(2).toInstant()));
	}

	boolean unlink(Long accountId, Provider provider) {
		return jdbc.update("delete from social_account where account_id = :id and provider = :provider",
				new MapSqlParameterSource("id", accountId).addValue("provider", provider.name())) > 0;
	}

	/** 탈퇴(본인·운영자 강제): 계정 행은 익명화되어 남으므로 연결 행을 여기서 지운다. */
	public void forgetAccount(Long accountId) {
		jdbc.update("delete from social_account where account_id = :id", new MapSqlParameterSource("id", accountId));
	}

}

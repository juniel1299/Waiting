package com.daumjari.auth;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.daumjari.account.Account;

/** 비밀번호 검증 동안만 쓰는 UserDetails. 세션에는 {@link SessionUser} 로 바꿔 저장한다. */
final class AccountUserDetails implements UserDetails {

	private final SessionUser user;

	private final String passwordHash;

	AccountUserDetails(Account account) {
		this.user = new SessionUser(account.getId(), account.getLoginId(), account.getDisplayName(), account.getRole(),
				account.getDemoTrialId());
		this.passwordHash = account.getPasswordHash();
	}

	SessionUser toSessionUser() {
		return user;
	}

	static List<GrantedAuthority> authoritiesOf(SessionUser user) {
		return authoritiesOf(user, false);
	}

	/** 기본 역할(ROLE_PARTICIPANT·ROLE_OPERATOR) + 더해진 역할(ROLE_DEVELOPER). */
	public static List<GrantedAuthority> authoritiesOf(SessionUser user, boolean developer) {
		return developer
				? List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()), new SimpleGrantedAuthority("ROLE_DEVELOPER"))
				: List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return authoritiesOf(user);
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return user.loginId();
	}

}

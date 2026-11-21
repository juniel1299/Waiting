package com.daumjari.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.daumjari.account.AccountRepository;

@Service
public class AccountUserDetailsService implements UserDetailsService {

	private final AccountRepository accounts;

	public AccountUserDetailsService(AccountRepository accounts) {
		this.accounts = accounts;
	}

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String loginId) {
		// 데모 계정은 비밀번호로 로그인할 수 없다 (없는 계정과 같은 응답)
		return accounts.findByLoginId(loginId)
			.filter(account -> account.getDemoTrialId() == null && !account.isDeleted())
			.map(AccountUserDetails::new)
			.orElseThrow(() -> new UsernameNotFoundException("account not found"));
	}

}

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
		return accounts.findByLoginId(loginId)
			.map(AccountUserDetails::new)
			.orElseThrow(() -> new UsernameNotFoundException("account not found"));
	}

}

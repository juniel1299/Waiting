package com.daumjari.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;
import com.daumjari.common.FieldValidationException;

/**
 * 회원가입. 역할은 항상 참가자이고, 아이디는 소문자로 정규화한다.
 * 유일성은 DB unique(login_id)가 보장한다: 사전 조회는 안내용이고, 동시 가입이 제약에 걸리면 같은 409 로 바꾼다.
 */
@Service
public class SignupService {

	/** 소문자·숫자·밑줄만: 데모(demo-…)·탈퇴(deleted-…) 계정의 하이픈 아이디와 겹치지 않는다. */
	static final Pattern LOGIN_ID = Pattern.compile("[a-z0-9_]{4,20}");

	static final int PASSWORD_MIN_LENGTH = 8;

	/** BCrypt 는 72바이트까지만 쓴다 (그 이상은 Spring Security 가 거부). */
	static final int PASSWORD_MAX_BYTES = 72;

	private final AccountRepository accounts;

	private final PasswordEncoder passwordEncoder;

	private final LoginThrottle throttle;

	private final Clock clock;

	private final int maxPerIp;

	public SignupService(AccountRepository accounts, PasswordEncoder passwordEncoder, LoginThrottle throttle,
			Clock clock, @Value("${daumjari.signup.max-per-ip:5}") int maxPerIp) {
		this.accounts = accounts;
		this.passwordEncoder = passwordEncoder;
		this.throttle = throttle;
		this.clock = clock;
		this.maxPerIp = maxPerIp;
	}

	public Account signup(String rawLoginId, String password, String passwordConfirm, String clientIp) {
		throttle.countSignup(clientIp, maxPerIp);
		String loginId = rawLoginId == null ? "" : rawLoginId.strip().toLowerCase(Locale.ROOT);
		Map<String, String> errors = validate(loginId, password, passwordConfirm);
		if (!errors.isEmpty()) {
			throw new FieldValidationException(errors);
		}
		if (accounts.existsByLoginIdIgnoreCase(loginId)) {
			throw new ApiException(ErrorCode.LOGIN_ID_TAKEN);
		}
		try {
			return accounts.saveAndFlush(Account.create(loginId, passwordEncoder.encode(password), loginId,
					Role.PARTICIPANT, Instant.now(clock)));
		}
		catch (DataIntegrityViolationException e) {
			// 사전 조회 뒤 같은 아이디가 먼저 들어갔다 (동시 가입)
			throw new ApiException(ErrorCode.LOGIN_ID_TAKEN);
		}
	}

	private static Map<String, String> validate(String loginId, String password, String passwordConfirm) {
		Map<String, String> errors = new LinkedHashMap<>();
		if (!LOGIN_ID.matcher(loginId).matches()) {
			errors.put("loginId", "아이디는 영문 소문자·숫자·밑줄(_)로 4~20자여야 해요.");
		}
		if (password == null || password.isBlank() || password.length() < PASSWORD_MIN_LENGTH) {
			errors.put("password", "비밀번호는 8자 이상이어야 해요.");
		}
		else if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
			errors.put("password", "비밀번호가 너무 길어요. 영문 기준 72자 이하로 입력해 주세요.");
		}
		if (passwordConfirm == null || !passwordConfirm.equals(password)) {
			errors.put("passwordConfirm", "비밀번호 확인이 일치하지 않아요.");
		}
		return errors;
	}

}

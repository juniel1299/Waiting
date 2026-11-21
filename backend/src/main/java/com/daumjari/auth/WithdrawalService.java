package com.daumjari.auth;

import java.time.Clock;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;
import com.daumjari.reservation.ReservationService;

/**
 * 회원탈퇴. 비밀번호 재확인 시도는 로그인 시도 제한(아이디+IP)에 함께 센다: 훔친 세션으로 비밀번호를 대입하지 못하게.
 * 시도는 먼저 따로 커밋하고, 나머지는 한 트랜잭션에서 계정 행 잠금 → 신청 정리(회차 id 순 잠금·승격) → 익명화.
 * 어디서 실패해도 전부 롤백된다.
 */
@Service
public class WithdrawalService {

	private final AccountRepository accounts;

	private final ReservationService reservations;

	private final PasswordEncoder passwordEncoder;

	private final LoginThrottle throttle;

	private final TransactionTemplate transaction;

	private final Clock clock;

	public WithdrawalService(AccountRepository accounts, ReservationService reservations,
			PasswordEncoder passwordEncoder, LoginThrottle throttle, TransactionTemplate transaction, Clock clock) {
		this.accounts = accounts;
		this.reservations = reservations;
		this.passwordEncoder = passwordEncoder;
		this.throttle = throttle;
		this.transaction = transaction;
		this.clock = clock;
	}

	public void withdraw(SessionUser user, String password, String clientIp) {
		// 운영자는 운영을 이어가야 하고, 체험 계정은 만료되면 통째로 정리된다
		if (user.isDemo() || user.role() == Role.OPERATOR) {
			throw new ApiException(ErrorCode.WITHDRAWAL_NOT_ALLOWED);
		}
		throttle.countAttempt(user.loginId(), clientIp);
		transaction.executeWithoutResult(status -> {
			Account account = accounts.findByIdForUpdate(user.id())
				.filter(found -> !found.isDeleted())
				.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
			if (password == null || !passwordEncoder.matches(password, account.getPasswordHash())) {
				throw new ApiException(ErrorCode.PASSWORD_MISMATCH);
			}
			Instant now = Instant.now(clock);
			reservations.releaseAllOf(account.getId(), now);
			account.withdraw(now);
		});
	}

}

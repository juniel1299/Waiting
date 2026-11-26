package com.daumjari.moderation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.daumjari.favorite.FavoriteCleanup;
import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;
import com.daumjari.auth.SessionUser;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;
import com.daumjari.moderation.AccountAdminQuery.Counts;
import com.daumjari.moderation.AccountAdminQuery.Found;
import com.daumjari.moderation.ModerationDtos.AccountPage;
import com.daumjari.moderation.ModerationDtos.AccountStatus;
import com.daumjari.moderation.ModerationDtos.AccountSummary;
import com.daumjari.moderation.ModerationDtos.Action;
import com.daumjari.moderation.ModerationDtos.ActionEntry;
import com.daumjari.moderation.ModerationDtos.ActionPage;
import com.daumjari.moderation.ModerationDtos.Impact;
import com.daumjari.moderation.ModerationDtos.Paging;
import com.daumjari.reservation.ReservationService;
import com.daumjari.reservation.ReservationService.Release;

/**
 * 운영자 계정 조치: 이용 정지·해제·강제탈퇴. 한 트랜잭션에서
 * 활성 운영자 행 잠금(id 순) → 처리자가 아직 활성 운영자인지 확인 → 대상 계정 행 잠금 → 상태 확인 → 처리 → 조치 기록.
 * 강제탈퇴는 본인 탈퇴와 같은 정리(ReservationService.releaseAllOf)와 익명화를 쓴다. 어디서 실패해도 전부 롤백된다.
 * 기존 세션 차단은 WithdrawnAccountFilter 가 요청마다 계정 상태(정지·탈퇴)를 확인해 처리한다.
 */
@Service
public class AccountModerationService {

	private static final String SELF_BLOCKED = "본인 계정에는 조치할 수 없어요.";

	private static final String WITHDRAWN_BLOCKED = "이미 탈퇴한 계정이에요.";

	private final AccountRepository accounts;

	private final ReservationService reservations;

	private final AccountAdminQuery query;

	private final Clock clock;

	private final FavoriteCleanup favorites;

	public AccountModerationService(AccountRepository accounts, ReservationService reservations, AccountAdminQuery query,
			Clock clock, FavoriteCleanup favorites) {
		this.favorites = favorites;
		this.accounts = accounts;
		this.reservations = reservations;
		this.query = query;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public AccountPage search(String q, String status, String page, String size) {
		String keyword = q == null ? "" : q.strip();
		if (keyword.length() > 100) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		Paging paging = Paging.parse(page, size);
		Found<AccountSummary> found = query.search(keyword, ModerationDtos.statusFilter(status), paging);
		return new AccountPage(found.items(), paging.page(), paging.size(), found.total(), paging.totalPages(found.total()));
	}

	@Transactional(readOnly = true)
	public Impact impact(Long targetId, SessionUser operator) {
		AccountSummary target = query.summary(targetId).orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND));
		Counts c = query.impact(targetId, Instant.now(clock));
		boolean self = targetId.equals(operator.id());
		boolean withdrawn = target.status() == AccountStatus.WITHDRAWN;
		String blocked = self ? SELF_BLOCKED : withdrawn ? WITHDRAWN_BLOCKED : null;
		String label = withdrawn ? "탈퇴한 회원 #" + targetId : target.loginId();
		return new Impact(targetId, label, target.role(), target.status(), c.upcomingConfirmed(), c.startedConfirmedKept(),
				c.waiting(), c.promotions(), c.ownedSessions(), c.ownedSessionReservations(),
				!self && target.status() == AccountStatus.ACTIVE, !self && target.status() == AccountStatus.SUSPENDED,
				!self && !withdrawn, blocked);
	}

	@Transactional
	public AccountSummary suspend(Long targetId, String reason, SessionUser operator) {
		Account target = lockTarget(targetId, operator, true);
		if (target.isDeleted() || target.isSuspended()) {
			throw new ApiException(ErrorCode.ACCOUNT_STATE_CONFLICT);
		}
		Instant now = Instant.now(clock);
		target.suspend(now);
		query.record(operator.id(), targetId, Action.SUSPEND, reason, 0, 0, now);
		return summaryAfterFlush(targetId);
	}

	@Transactional
	public AccountSummary unsuspend(Long targetId, String reason, SessionUser operator) {
		Account target = lockTarget(targetId, operator, false);
		if (target.isDeleted() || !target.isSuspended()) {
			throw new ApiException(ErrorCode.ACCOUNT_STATE_CONFLICT);
		}
		Instant now = Instant.now(clock);
		target.unsuspend();
		query.record(operator.id(), targetId, Action.UNSUSPEND, reason, 0, 0, now);
		return summaryAfterFlush(targetId);
	}

	/** 본인 탈퇴와 같은 정리 + 익명화. 정지된 계정도 탈퇴시킬 수 있다. */
	@Transactional
	public AccountSummary forceWithdraw(Long targetId, String reason, SessionUser operator) {
		Account target = lockTarget(targetId, operator, true);
		if (target.isDeleted()) {
			throw new ApiException(ErrorCode.ACCOUNT_STATE_CONFLICT);
		}
		Instant now = Instant.now(clock);
		Release release = reservations.releaseAllOf(targetId, now);
		favorites.forgetAccount(targetId);
		// 본인 회차 삭제(deleteRowById)가 영속성 컨텍스트를 비워 target 이 분리되므로 다시 읽어 익명화한다 (행 잠금은 유지됨)
		accounts.findById(targetId).orElseThrow().withdraw(now);
		query.record(operator.id(), targetId, Action.FORCE_WITHDRAW, reason, release.cancelledReservations(),
				release.deletedSessions(), now);
		return summaryAfterFlush(targetId);
	}

	@Transactional(readOnly = true)
	public ActionPage actions(String targetId, String page, String size) {
		Long target;
		try {
			target = targetId == null || targetId.isBlank() ? null : Long.valueOf(targetId.strip());
		}
		catch (NumberFormatException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		Paging paging = Paging.parse(page, size);
		Found<ActionEntry> found = query.actions(target, paging);
		return new ActionPage(found.items(), paging.page(), paging.size(), found.total(), paging.totalPages(found.total()));
	}

	/**
	 * 잠금 순서: 활성 운영자 행(id 순) → 대상 계정 행 → (강제탈퇴면) 회차 id 순. 운영자끼리의 동시 조치가 첫 잠금에서 줄을 서므로,
	 * 앞선 조치로 정지·탈퇴된 처리자는 여기서 401 이 되고 운영자가 0명이 되는 일이 없다.
	 */
	private Account lockTarget(Long targetId, SessionUser operator, boolean removesAccess) {
		if (targetId.equals(operator.id())) {
			throw new ApiException(ErrorCode.SELF_MODERATION_NOT_ALLOWED);
		}
		List<Long> activeOperators = accounts.lockActiveOperatorIds();
		if (!activeOperators.contains(operator.id())) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		Account target = accounts.findByIdForUpdate(targetId)
			.filter(account -> account.getDemoTrialId() == null)
			.orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND));
		boolean lastOperator = target.getRole() == Role.OPERATOR && activeOperators.size() == 1
				&& activeOperators.contains(targetId);
		if (removesAccess && lastOperator) {
			throw new ApiException(ErrorCode.LAST_OPERATOR);
		}
		return target;
	}

	private AccountSummary summaryAfterFlush(Long targetId) {
		accounts.flush();
		return query.summary(targetId).orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND));
	}

}

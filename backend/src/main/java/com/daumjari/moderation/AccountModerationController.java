package com.daumjari.moderation;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.SessionUser;
import com.daumjari.moderation.ModerationDtos.AccountPage;
import com.daumjari.moderation.ModerationDtos.AccountSummary;
import com.daumjari.moderation.ModerationDtos.ActionPage;
import com.daumjari.moderation.ModerationDtos.Impact;
import com.daumjari.moderation.ModerationDtos.ReasonRequest;

import jakarta.validation.Valid;

/** OPERATOR 전용 계정 관리 (SecurityConfig 의 /api/operator/** 규칙). README "운영자 계정 관리". */
@RestController
public class AccountModerationController {

	private final AccountModerationService service;

	public AccountModerationController(AccountModerationService service) {
		this.service = service;
	}

	@GetMapping("/api/operator/accounts")
	public AccountPage search(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) String page, @RequestParam(required = false) String size) {
		return service.search(q, status, page, size);
	}

	@GetMapping("/api/operator/accounts/{accountId}/impact")
	public Impact impact(@PathVariable Long accountId, @AuthenticationPrincipal SessionUser operator) {
		return service.impact(accountId, operator);
	}

	@PostMapping("/api/operator/accounts/{accountId}/suspend")
	public AccountSummary suspend(@PathVariable Long accountId, @Valid @RequestBody ReasonRequest body,
			@AuthenticationPrincipal SessionUser operator) {
		return service.suspend(accountId, body.validReason(), operator);
	}

	@PostMapping("/api/operator/accounts/{accountId}/unsuspend")
	public AccountSummary unsuspend(@PathVariable Long accountId, @Valid @RequestBody ReasonRequest body,
			@AuthenticationPrincipal SessionUser operator) {
		return service.unsuspend(accountId, body.validReason(), operator);
	}

	@PostMapping("/api/operator/accounts/{accountId}/withdraw")
	public AccountSummary withdraw(@PathVariable Long accountId, @Valid @RequestBody ReasonRequest body,
			@AuthenticationPrincipal SessionUser operator) {
		return service.forceWithdraw(accountId, body.validReason(), operator);
	}

	@GetMapping("/api/operator/actions")
	public ActionPage actions(@RequestParam(required = false) String targetId,
			@RequestParam(required = false) String page, @RequestParam(required = false) String size) {
		return service.actions(targetId, page, size);
	}

}

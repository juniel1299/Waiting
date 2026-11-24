package com.daumjari.club;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.SessionUser;
import com.daumjari.club.ClubDtos.ClubPage;
import com.daumjari.club.ClubDtos.ClubRequest;
import com.daumjari.club.ClubDtos.ClubResponse;
import com.daumjari.club.ClubDtos.Decision;
import com.daumjari.club.ClubDtos.Membership;
import com.daumjari.club.ClubDtos.MyClubs;

/** 모임 신청·조회·가입 API 와 운영자 승인·반려 API. /api/operator/** 는 SecurityConfig 가 운영자만 들인다. */
@RestController
public class ClubController {

	private final ClubService clubs;

	public ClubController(ClubService clubs) {
		this.clubs = clubs;
	}

	@GetMapping("/api/clubs")
	public ClubPage list(@RequestParam(required = false) String q, @RequestParam(required = false) String page,
			@RequestParam(required = false) String size, @RequestParam(required = false) String sort,
			@AuthenticationPrincipal SessionUser user) {
		return clubs.list(q, page, size, sort, user);
	}

	@GetMapping("/api/clubs/{id}")
	public ClubResponse get(@PathVariable Long id, @AuthenticationPrincipal SessionUser user) {
		return clubs.get(id, user);
	}

	@GetMapping("/api/me/clubs")
	public MyClubs mine(@AuthenticationPrincipal SessionUser user) {
		return clubs.mine(user);
	}

	@PostMapping("/api/clubs")
	@ResponseStatus(HttpStatus.CREATED)
	public ClubResponse apply(@RequestBody ClubRequest request, @AuthenticationPrincipal SessionUser user) {
		return clubs.apply(request, user);
	}

	@PatchMapping("/api/clubs/{id}")
	public ClubResponse edit(@PathVariable Long id, @RequestBody ClubRequest request,
			@AuthenticationPrincipal SessionUser user) {
		return clubs.edit(id, request, user);
	}

	@PostMapping("/api/clubs/{id}/cancel")
	public ClubResponse cancel(@PathVariable Long id, @AuthenticationPrincipal SessionUser user) {
		return clubs.cancel(id, user);
	}

	@DeleteMapping("/api/clubs/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @AuthenticationPrincipal SessionUser user) {
		clubs.delete(id, user);
	}

	@PostMapping("/api/clubs/{id}/join")
	public Membership join(@PathVariable Long id, @AuthenticationPrincipal SessionUser user) {
		return clubs.join(id, user);
	}

	@PostMapping("/api/clubs/{id}/leave")
	public Membership leave(@PathVariable Long id, @AuthenticationPrincipal SessionUser user) {
		return clubs.leave(id, user);
	}

	@GetMapping("/api/operator/clubs")
	public ClubPage operatorList(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
			@RequestParam(required = false) String page, @RequestParam(required = false) String size,
			@AuthenticationPrincipal SessionUser user) {
		return clubs.operatorList(status, q, page, size, user);
	}

	@PostMapping("/api/operator/clubs/{id}/approve")
	public ClubResponse approve(@PathVariable Long id, @RequestBody Decision decision,
			@AuthenticationPrincipal SessionUser user) {
		return clubs.decide(id, true, decision, user);
	}

	@PostMapping("/api/operator/clubs/{id}/reject")
	public ClubResponse reject(@PathVariable Long id, @RequestBody Decision decision,
			@AuthenticationPrincipal SessionUser user) {
		return clubs.decide(id, false, decision, user);
	}

}

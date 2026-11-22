package com.daumjari.studysession;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import com.daumjari.studysession.SessionDtos.CapacityChangeResponse;
import com.daumjari.studysession.SessionDtos.ChangeCapacityRequest;
import com.daumjari.studysession.SessionDtos.CreateSessionRequest;
import com.daumjari.studysession.SessionDtos.EditSessionRequest;
import com.daumjari.studysession.SessionDtos.RosterEntry;
import com.daumjari.studysession.SessionDtos.SessionPage;
import com.daumjari.studysession.SessionDtos.SessionResponse;

import jakarta.validation.Valid;

/**
 * 회차 조회와 생성자 관리. 생성자는 요청 본문이 아니라 로그인 세션에서 정하고, 관리 권한은 서비스가 회차 행을 잠근 뒤 확인한다
 * (생성자: 수정·정원·명단·삭제, 운영자: 명단·삭제).
 */
@RestController
public class StudySessionController {

	private final StudySessionService service;

	public StudySessionController(StudySessionService service) {
		this.service = service;
	}

	@GetMapping("/api/sessions")
	public List<SessionResponse> list(@AuthenticationPrincipal SessionUser user) {
		return service.list(user);
	}

	/** 검색·필터·정렬·페이지네이션 (README "회차 검색"). 값이 틀리면 400 VALIDATION_FAILED. */
	@GetMapping("/api/sessions/search")
	public SessionPage search(@RequestParam(required = false) String scope, @RequestParam(required = false) String q,
			@RequestParam(required = false) String field, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(required = false) String status,
			@RequestParam(required = false) String sort, @RequestParam(required = false) String page,
			@RequestParam(required = false) String size, @AuthenticationPrincipal SessionUser user) {
		return service.search(SessionSearch.parse(scope, q, field, from, to, status, sort, page, size), user);
	}

	@GetMapping("/api/sessions/{sessionId}")
	public SessionResponse get(@PathVariable Long sessionId, @AuthenticationPrincipal SessionUser user) {
		return service.get(sessionId, user);
	}

	@PostMapping("/api/sessions")
	public ResponseEntity<SessionResponse> create(@Valid @RequestBody CreateSessionRequest request,
			@AuthenticationPrincipal SessionUser user) {
		SessionResponse created = service.create(request, user);
		return ResponseEntity.created(URI.create("/api/sessions/" + created.id())).body(created);
	}

	@PatchMapping("/api/sessions/{sessionId}")
	public SessionResponse edit(@PathVariable Long sessionId, @Valid @RequestBody EditSessionRequest request,
			@AuthenticationPrincipal SessionUser user) {
		return service.edit(sessionId, request, user);
	}

	@PatchMapping("/api/sessions/{sessionId}/capacity")
	public CapacityChangeResponse changeCapacity(@PathVariable Long sessionId,
			@Valid @RequestBody ChangeCapacityRequest request, @AuthenticationPrincipal SessionUser user) {
		return service.changeCapacity(sessionId, request.capacity(), user);
	}

	@GetMapping("/api/sessions/{sessionId}/roster")
	public List<RosterEntry> roster(@PathVariable Long sessionId, @AuthenticationPrincipal SessionUser user) {
		return service.roster(sessionId, user);
	}

	@DeleteMapping("/api/sessions/{sessionId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long sessionId, @AuthenticationPrincipal SessionUser user) {
		service.delete(sessionId, user);
	}

	/** 내가 만든 회차. */
	@GetMapping("/api/me/sessions")
	public List<SessionResponse> mine(@AuthenticationPrincipal SessionUser user) {
		return service.mine(user);
	}

}

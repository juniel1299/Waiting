package com.daumjari.studysession;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.SessionUser;
import com.daumjari.studysession.SessionDtos.SessionResponse;

@RestController
@RequestMapping("/api/sessions")
public class StudySessionController {

	private final StudySessionService service;

	public StudySessionController(StudySessionService service) {
		this.service = service;
	}

	@GetMapping
	public List<SessionResponse> list(@AuthenticationPrincipal SessionUser user) {
		return service.list(user.id());
	}

	@GetMapping("/{sessionId}")
	public SessionResponse get(@PathVariable Long sessionId, @AuthenticationPrincipal SessionUser user) {
		return service.get(sessionId, user.id());
	}

}

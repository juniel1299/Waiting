package com.daumjari.studysession;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.studysession.SessionDtos.CreateSessionRequest;
import com.daumjari.studysession.SessionDtos.RosterEntry;
import com.daumjari.studysession.SessionDtos.SessionResponse;

import jakarta.validation.Valid;

/** OPERATOR 전용 (SecurityConfig 의 /api/operator/** 규칙). */
@RestController
@RequestMapping("/api/operator/sessions")
public class OperatorSessionController {

	private final StudySessionService service;

	public OperatorSessionController(StudySessionService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<SessionResponse> create(@Valid @RequestBody CreateSessionRequest request) {
		SessionResponse created = service.create(request);
		return ResponseEntity.created(URI.create("/api/sessions/" + created.id())).body(created);
	}

	@GetMapping("/{sessionId}/roster")
	public List<RosterEntry> roster(@PathVariable Long sessionId) {
		return service.roster(sessionId);
	}

}
